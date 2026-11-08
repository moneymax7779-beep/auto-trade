package com.autotrade.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.BrokerException;
import com.autotrade.sim.CostModel;

/**
 * The execution workers' loop (run by app-execution every second). Each tick fans out every served copy group's new
 * decisions once (a transaction-scoped advisory lock per group: one fan-out at a time across workers), then gives every
 * follower account its turn on its own lane (a single-thread executor per account, so a slow account or broker delays
 * only itself; a lane still busy is skipped). One turn runs every served copy group the account follows.
 * <p>
 * <b>Served groups</b> ({@code copyGroups}): one name of {@code tenant}, a comma-separated list, or {@code *} = every
 * enabled copy group of every tenant (several users, each with their own tenant, accounts and subscriptions).
 * <p>
 * <b>Routes</b> ({@link BrokerConnections}): PAPER and DRY_RUN-without-order-rules accounts fill in place; a ZERODHA
 * DRY_RUN account with order rules trades through the Kite adapter against its own local simulator; a ZERODHA account the
 * user switched LIVE, on a risk file with live limits, trades the real Kite behind a {@link LiveGuard}, today only.
 * An account trades only under its {@link AccountLease}. A broker-routed account is reconciled against its broker when
 * the lease is taken and every {@link #RECONCILE_EVERY} (a difference kills its entries).
 * <p>
 * <b>Idle accounts</b> (DAILY_SWITCH workspaces, V29): a real broker account that is not the day's entry target and holds
 * nothing ({@link TradingDay#idle}) gets no turn: no broker connection, no lease, no reconciliation (a member's own hand
 * trades on a PAPER day are not ours to reconcile), and its exits and adds are closed as NOT_HELD.
 */
public final class ExecutionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLoop.class);
    private static final int FANOUT_LOCK_CLASS = 7102;
    static final java.time.Duration RECONCILE_EVERY = java.time.Duration.ofSeconds(30);
    /**
     * Review 8 Nov 2026: the end-of-day square-off was one attempt (a stop whose cancel did not show, an exit the chase
     * could not fill: only logged). It is tried again at the end of every tick, this far apart, until nothing this worker
     * holds is open, for at most {@link #SQUARE_OFF_RETRY_FOR} after the first attempt (then a last EOD_SQUARE_OFF alert).
     */
    static final java.time.Duration SQUARE_OFF_RETRY = java.time.Duration.ofSeconds(30);
    static final java.time.Duration SQUARE_OFF_RETRY_FOR = java.time.Duration.ofMinutes(9);

    /** One account's last turn, for the status page. */
    public record AccountStatus(long accountId, long tenantId, String account, String mode, String broker, String route,
                                Long leaseEpoch, String state, Instant lastRun, String lastResult) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    private final FanOut fanOut;
    private final String tenant;
    private final String copyGroups;
    private final String workerId;
    private final CostModel costs;
    private final QuoteSource quotes;
    private final BrokerConnections connections;
    private final BrokerReconciler reconciler;
    private final TradingDay tradingDay;
    private final Map<Long, ExecutorService> lanes = new ConcurrentHashMap<>();
    private final Map<Long, Future<?>> busy = new ConcurrentHashMap<>();
    private final Map<Long, AccountLease> leases = new ConcurrentHashMap<>();
    private final Map<Long, FollowerSimulator> simulators = new ConcurrentHashMap<>();
    private final Map<Long, Instant> reconciled = new ConcurrentHashMap<>();
    private final Map<Long, FollowerRisk> risks = new ConcurrentHashMap<>();
    private final Map<Long, String> signatures = new ConcurrentHashMap<>();
    private final Map<Long, AccountStatus> status = new ConcurrentHashMap<>();
    private volatile LocalDate squareOffDay;                 // the day whose square-off has not left everything flat
    private volatile Instant squareOffStarted;
    private volatile Instant squareOffLast;
    private java.time.Duration squareOffRetry = SQUARE_OFF_RETRY;
    /** Review 8 Nov 2026: square-off retries run here, one at a time, never on the tick (a flatten can take minutes). */
    private final ExecutorService squareOffs = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("exec-square-off").daemon(true).factory());
    private final java.util.concurrent.atomic.AtomicBoolean squareOffRunning = new java.util.concurrent.atomic.AtomicBoolean();

    public ExecutionLoop(DataSource dataSource, String tenant, String copyGroups, String workerId, CostModel costs,
                         QuoteSource quotes, double brokerFunds) {
        this(dataSource, tenant, copyGroups, workerId, costs, quotes, brokerFunds, BrokerSessions.NONE,
                com.autotrade.zerodha.KiteConfig.REAL_API);
    }

    /**
     * @param sessions    the accounts' real broker sessions, read by DRY_RUN accounts whose risk file reads the broker's
     *                    market ({@code orders.dry_run_market: BROKER})
     * @param kiteApiBase Kite's API for those reads
     */
    public ExecutionLoop(DataSource dataSource, String tenant, String copyGroups, String workerId, CostModel costs,
                         QuoteSource quotes, double brokerFunds, BrokerSessions sessions, java.net.URI kiteApiBase) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                dataSource));
        this.accounts = new AccountStore(dataSource);
        this.fanOut = new FanOut(dataSource);
        this.tenant = tenant;
        this.copyGroups = copyGroups;
        this.workerId = workerId;
        this.costs = costs;
        this.quotes = quotes;
        this.connections = new BrokerConnections(dataSource, brokerFunds,
                List.of(new ZerodhaDryRunAdapter(dataSource, brokerFunds, sessions, kiteApiBase)));
        this.reconciler = new BrokerReconciler(dataSource, costs);
        this.tradingDay = new TradingDay(dataSource);
    }

    /** The copy groups this worker serves now (ids). */
    List<Long> groups() {
        if (copyGroups.trim().equals("*")) {
            return jdbc.queryForList("select id from acct.copy_group where enabled order by id", Long.class);
        }
        long tenantId = accounts.ensureTenant(tenant);
        List<Long> ids = new ArrayList<>();
        for (String name : copyGroups.split(",")) {
            accounts.copyGroupId(tenantId, name.trim()).ifPresent(ids::add);
        }
        return ids;
    }

    /** One tick for {@code day}; waits up to {@code waitMs} for the accounts' turns and returns how many ran. */
    public int tick(LocalDate day, long waitMs) {
        List<Long> groups = groups();
        if (groups.isEmpty()) {
            return 0;
        }
        Map<Long, BrokerAccount> followers = new LinkedHashMap<>();
        Map<Long, List<Long>> groupsOf = new LinkedHashMap<>();
        for (long groupId : groups) {
            transactions.executeWithoutResult(tx -> {
                Boolean mine = jdbc.queryForObject("select pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                        FANOUT_LOCK_CLASS, (int) groupId);
                if (Boolean.TRUE.equals(mine)) {
                    fanOut.fanOut(groupId, day);
                }
            });
            for (BrokerAccount account : accounts.followers(groupId)) {
                followers.putIfAbsent(account.id(), account);
                groupsOf.computeIfAbsent(account.id(), k -> new ArrayList<>()).add(groupId);
            }
        }
        List<Future<?>> turns = new ArrayList<>();
        for (BrokerAccount account : followers.values()) {
            Future<?> running = busy.get(account.id());
            if (running != null && !running.isDone()) {
                continue;                                              // its lane is still on the previous turn
            }
            List<Long> its = groupsOf.get(account.id());
            Future<?> turn = lanes.computeIfAbsent(account.id(), id -> Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("exec-account-" + id).daemon(true).factory()))
                    .submit(() -> turn(account, its, day));
            busy.put(account.id(), turn);
            turns.add(turn);
        }
        long deadline = System.currentTimeMillis() + waitMs;
        for (Future<?> turn : turns) {
            try {
                turn.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // a slow or failed turn is reported by the account's status, never stops the others
            }
        }
        squareOffAgain(day);
        return turns.size();
    }

    /** The square-off tried again when the last one did not leave everything flat (see {@link #SQUARE_OFF_RETRY}). */
    private void squareOffAgain(LocalDate day) {
        if (!day.equals(squareOffDay) || squareOffLast.plus(squareOffRetry).isAfter(Instant.now())
                || !squareOffRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            squareOffs.submit(() -> {
                try {
                    retrySquareOff(day);
                } catch (RuntimeException e) {
                    log.error("end-of-day square-off retry failed", e);
                } finally {
                    squareOffRunning.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            squareOffRunning.set(false);                            // closed
        }
    }

    private void retrySquareOff(LocalDate day) {
        if (Instant.now().isAfter(squareOffStarted.plus(SQUARE_OFF_RETRY_FOR))) {
            squareOffDay = null;
            for (long accountId : List.copyOf(leases.keySet())) {
                BrokerAccount account = accounts.byId(accountId).orElse(null);
                int open = account == null ? 0 : openPositions(accountId);
                if (open > 0) {
                    new KillSwitches(dataSource).audit(account, workerId, "EOD_SQUARE_OFF", Map.of("positions",
                            List.of(open + " position(s) STILL OPEN after " + SQUARE_OFF_RETRY_FOR.toMinutes()
                                    + " min of square-off attempts: close them in the broker's app")));
                    log.error("account {} ({}): {} position(s) still open after the square-off attempts", accountId,
                            account.displayName(), open);
                }
            }
            return;
        }
        squareOff(day).forEach(line -> log.warn("end-of-day square-off (again): {}", line));
    }

    /** Tests: the square-off retry interval. */
    ExecutionLoop squareOffRetry(java.time.Duration every) {
        this.squareOffRetry = every;
        return this;
    }

    /** Tests: the account is reconciled at its next turn. */
    void reconcileNextTurn(long accountId) {
        reconciled.remove(accountId);
    }

    private int openPositions(long accountId) {
        Integer open = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? "
                + "and status = 'OPEN' and quantity_open > 0", Integer.class, accountId);
        return open == null ? 0 : open;
    }

    private void turn(BrokerAccount account, List<Long> groupIds, LocalDate day) {
        try {
            // an account whose mode, broker or risk file changed gets a fresh risk, route and simulator
            String signature = account.tradeMode() + "/" + account.brokerType() + "/" + account.riskFile();
            String before = signatures.put(account.id(), signature);
            if (before != null && !before.equals(signature)) {
                risks.remove(account.id());
                simulators.remove(account.id());
                connections.drop(account.id());
                reconciled.remove(account.id());                     // the new route is reconciled before it trades
                log.info("account {} ({}) changed ({} -> {}): route rebuilt", account.id(), account.displayName(), before,
                        signature);
            }
            if (tradingDay.idle(account)) {
                int closed = tradingDay.closeIdleIntents(account.id());
                record(account, null, null, "IDLE", "not the day's broker and holds nothing; no broker connection"
                        + (closed > 0 ? " (" + closed + " exit/add closed NOT_HELD)" : ""));
                return;
            }
            FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
            if ((account.tradeMode() == TradeMode.LIVE || risk.orders() != null && risk.orders().brokerMarket())
                    && !day.equals(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) {
                // the broker's market is today's: a drill replaying another day would price its decisions at today's book
                // (and a LIVE account would send them)
                record(account, null, null, "REFUSED", "reads the broker's live market, so it serves only today's session, "
                        + "not " + day + "; nothing sent");
                return;
            }
            BrokerConnections.Route route = connections.route(account, risk);
            if (route.venue() == null) {
                record(account, route, null, "REFUSED", route.description() + "; nothing sent");
                return;
            }
            AccountLease lease = leases.get(account.id());
            if (lease != null && !lease.heartbeat()) {
                lease.close();
                leases.remove(account.id());
                simulators.remove(account.id());
                risks.remove(account.id());                          // read again by the next holder of the lease
                connections.drop(account.id());
                log.error("lease lost for account {} ({}); it stops trading here", account.id(), account.displayName());
                lease = null;
            }
            if (lease == null) {
                java.util.Optional<AccountLease> acquired = AccountLease.tryAcquire(dataSource, account.id(), workerId);
                if (acquired.isEmpty()) {
                    record(account, route, null, "LEASE_HELD_ELSEWHERE", "another worker trades this account");
                    return;
                }
                lease = acquired.get();
                leases.put(account.id(), lease);
                reconciled.remove(account.id());                     // recovery: reconcile before the first order
                log.info("lease taken for account {} ({}), epoch {}", account.id(), account.displayName(), lease.epoch());
            }
            long epoch = lease.epoch();
            String check = "";
            if (route.connection() != null) {
                Instant last = reconciled.get(account.id());
                if (last == null || last.plus(RECONCILE_EVERY).isBefore(Instant.now())) {
                    try {
                        BrokerReconciler.Check c = reconciler.check(account, route.connection());
                        check = c.matched() ? "" : ", RECONCILIATION " + c.differences();
                    } catch (BrokerException e) {
                        if (e.kind() == BrokerException.Kind.SESSION) {
                            // the session is gone: the simulator below refuses entries and keeps exits for the next login
                            new KillSwitches(dataSource).loseSession(account, workerId,
                                    Map.of("message", String.valueOf(e.getMessage()), "during", "reconciliation"));
                            tradingDay.sessionLost(account, workerId);     // a DAILY_SWITCH LIVE day goes back to PAPER
                            check = ", BROKER SESSION LOST (" + e.getMessage() + ")";
                        } else {
                            // review 8 Nov 2026: a failed read (positions, orders) was rethrown, so the turn skipped the
                            // simulator and no exit went out while the read kept failing. Now it is noted and the turn
                            // goes on; the next check is RECONCILE_EVERY later
                            log.warn("account {}: reconciliation not done ({}): {}", account.id(), e.kind(), e.getMessage());
                            check = ", RECONCILIATION NOT DONE (" + e.kind() + ": " + e.getMessage() + ")";
                        }
                    }
                    reconciled.put(account.id(), Instant.now());
                }
            }
            FollowerSimulator simulator = simulators.computeIfAbsent(account.id(), id -> new FollowerSimulator(dataSource,
                    costs, risk, route.venue(), quotes).asWorker(workerId, epoch));
            int executed = 0, skipped = 0, rejected = 0;
            double net = 0;
            Map<String, Integer> reasons = new LinkedHashMap<>();
            for (long groupId : groupIds) {
                FollowerSimulator.DayResult result = simulator.run(account, groupId, day);
                executed += result.executed();
                skipped += result.skipped();
                rejected += result.rejected();
                net = result.net();
                result.reasons().forEach((k, v) -> reasons.merge(k, v, Integer::sum));
            }
            String summary = executed + skipped + rejected == 0 && reasons.isEmpty() ? "nothing new"
                    : "executed " + executed + ", skipped " + skipped + ", rejected " + rejected + " " + reasons;
            record(account, route, epoch, "RUNNING", summary + ", day net " + Math.round(net) + check);
        } catch (BrokerException e) {
            record(account, null, null, "BROKER_" + e.kind(), String.valueOf(e.getMessage()));
            log.error("account {} broker error ({}): {}", account.id(), e.kind(), e.getMessage());
        } catch (Exception e) {
            record(account, null, null, "ERROR", String.valueOf(e.getMessage()));
            log.error("account {} turn failed", account.id(), e);
        }
    }

    /**
     * Flatten one account now (UI / API, with a confirmation): its kill switch is engaged, then on the account's own lane
     * and under its lease, every open position is exited (broker stops off first). A simulated venue exits at the
     * latest stored bid (own capture, last 5 minutes), else the contract's last signal price today, else the entry price.
     */
    public List<String> flatten(long accountId, String actor, String reason, LocalDate day) throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        new KillSwitches(dataSource).engageAccount(account, "FLATTEN by " + actor + ": " + reason, actor);
        return flattenOnLane(account, day, "FLATTEN");
    }

    /**
     * End-of-day square-off (code review 24 Oct 2026): a follower closes only on its signal source's EXIT decisions, so
     * when the signal source stopped mid-day its positions would stay open overnight (and block the next day's entries
     * for that strategy). At the square-off time every account this worker holds that still has an open position is
     * flattened like the Flatten button, but without a kill switch (the next day trades normally); audited
     * EOD_SQUARE_OFF. Every strategy of the live set is flat by 15:15, so this is a safety net. Review 8 Nov 2026: tried
     * again from each tick until flat ({@link #SQUARE_OFF_RETRY}).
     */
    public List<String> squareOff(LocalDate day) {
        // the times first, then the day: a tick reading the day always finds its times set
        if (!day.equals(squareOffDay)) {
            squareOffStarted = Instant.now();
        }
        squareOffLast = Instant.now();
        squareOffDay = day;
        List<String> lines = new ArrayList<>();
        boolean flat = true;
        for (long accountId : List.copyOf(leases.keySet())) {
            BrokerAccount account = accounts.byId(accountId).orElse(null);
            int open = account == null ? 0 : openPositions(accountId);
            if (open == 0) {
                continue;
            }
            try {
                List<String> done = flattenOnLane(account, day, "EOD_SQUARE_OFF");
                new KillSwitches(dataSource).audit(account, workerId, "EOD_SQUARE_OFF", java.util.Map.of("positions", done));
                done.forEach(line -> lines.add(account.displayName() + ": " + line));
            } catch (Exception e) {
                lines.add(account.displayName() + ": square-off failed (" + e.getMessage() + ")");
            }
            flat &= openPositions(accountId) == 0;
        }
        if (flat) {
            squareOffDay = null;                                    // done: no more attempts today
        }
        return lines;
    }

    /**
     * Exit one open position now (the trader's per-position Exit, 7 Nov 2026): like {@link #flatten} for that position
     * alone (stop off first, sold at the broker or at the stored bid), audited POSITION_EXIT; no kill switch, the
     * account keeps trading.
     */
    public List<String> exitPosition(long accountId, long positionId, String actor, LocalDate day) throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        if (account.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        List<String> lines = flattenOnLane(account, day, "TRADER_EXIT", positionId);
        return lines.isEmpty() ? List.of("position " + positionId + " is not open") : lines;
    }

    /**
     * Book a hand exit of one position at the trader's price and time ({@link ManualExit}): on the account's lane, with
     * what the broker holds of the contract read first when there is a broker session.
     */
    public String bookManualExit(long accountId, long positionId, double price, Instant at, String actor, boolean unchecked)
            throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        if (account.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
        BrokerConnections.Route route = connections.route(account, risk);
        return onLane(account, (epoch, borrowed) -> {
            String key = jdbc.queryForList("select instrument_key from exec.position where id = ? and broker_account_id = ?",
                    String.class, positionId, accountId).stream().findFirst().orElse(null);
            ManualExit.BrokerHolding holding;
            if (route.connection() == null) {
                holding = new ManualExit.BrokerHolding(null, route.description());
            } else {
                try {
                    holding = new ManualExit.BrokerHolding(route.connection().positions().stream()
                            .filter(x -> key != null && key.equals(x.instrumentKey() == null ? x.symbol() : x.instrumentKey()))
                            .mapToLong(x -> x.netQuantity()).sum(), null);
                } catch (RuntimeException e) {
                    holding = new ManualExit.BrokerHolding(null, String.valueOf(e.getMessage()));
                }
            }
            return new ManualExit(dataSource, costs).book(account, positionId, price, at, actor, holding, unchecked);
        });
    }

    private List<String> flattenOnLane(BrokerAccount account, LocalDate day, String reason) throws Exception {
        return flattenOnLane(account, day, reason, null);
    }

    private List<String> flattenOnLane(BrokerAccount account, LocalDate day, String reason, Long positionId) throws Exception {
        FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
        BrokerConnections.Route route = connections.route(account, risk);
        if (route.venue() == null) {
            return List.of("not flattened: " + route.description());
        }
        try {
            return onLane(account, (epoch, borrowed) -> {
                FollowerSimulator simulator = borrowed
                        ? new FollowerSimulator(dataSource, costs, risk, route.venue(), quotes).asWorker(workerId, epoch)
                        : simulators.computeIfAbsent(account.id(), id -> new FollowerSimulator(dataSource, costs, risk,
                                route.venue(), quotes).asWorker(workerId, epoch));
                return simulator.flatten(account, day, this::flattenPrice, reason, positionId);
            });
        } catch (IllegalStateException e) {
            if (!String.valueOf(e.getMessage()).startsWith("another worker")) {
                throw e;
            }
            return List.of("not flattened here: " + e.getMessage());
        }
    }

    /** Work on an account's own lane, under its lease (borrowed for the call when this worker does not hold it). */
    private interface LaneWork<T> {
        T run(long epoch, boolean borrowed) throws Exception;
    }

    private <T> T onLane(BrokerAccount account, LaneWork<T> work) throws Exception {
        java.util.concurrent.Callable<T> task = () -> {
            AccountLease lease = leases.get(account.id());
            boolean borrowed = lease == null;                  // not an account this worker trades: given back after
            if (borrowed) {
                java.util.Optional<AccountLease> acquired = AccountLease.tryAcquire(dataSource, account.id(), workerId);
                if (acquired.isEmpty()) {
                    throw new IllegalStateException("another worker holds the account (do it there)");
                }
                lease = acquired.get();
            }
            try {
                return work.run(lease.epoch(), borrowed);
            } finally {
                if (borrowed) {
                    lease.close();
                }
            }
        };
        try {
            return lanes.computeIfAbsent(account.id(), id -> Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("exec-account-" + id).daemon(true).factory())).submit(task)
                    .get(120, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    private double flattenPrice(Map<String, Object> position) {
        long token = ((Number) position.get("instrument_token")).longValue();
        List<Double> bid = jdbc.queryForList("select bid_px[1] from md.option_tick where instrument_token = ? "
                + "and recv_ts > now() - interval '5 minutes' and bid_px[1] > 0 order by recv_ts desc limit 1", Double.class,
                token);
        if (!bid.isEmpty() && bid.getFirst() != null) {
            return bid.getFirst();
        }
        List<Double> signal = jdbc.queryForList("select reference_price from exec.signal_decision where instrument_token = ? "
                + "and reference_price > 0 order by decided_at desc limit 1", Double.class, token);
        return !signal.isEmpty() && signal.getFirst() != null ? signal.getFirst()
                : ((Number) position.get("average_entry")).doubleValue();
    }

    /** End-of-day broker reconciliation for every broker-routed account this worker holds; returns the ledger rows. */
    public List<BrokerReconciler.EndOfDay> endOfDay(LocalDate day) {
        List<BrokerReconciler.EndOfDay> days = new ArrayList<>();
        for (long accountId : leases.keySet()) {
            BrokerAccount account = accounts.byId(accountId).orElse(null);
            BrokerConnections.Route route = account == null ? null
                    : connections.route(account, FollowerRisk.load(Path.of(account.riskFile())));
            if (route != null && route.connection() != null) {
                days.add(reconciler.endOfDay(account, route.connection(), day));
            }
        }
        return days;
    }

    /** The account's route (for drills and tests): its venue and broker connection. */
    BrokerConnections connections() {
        return connections;
    }

    private void record(BrokerAccount account, BrokerConnections.Route route, Long epoch, String state, String result) {
        status.put(account.id(), new AccountStatus(account.id(), account.tenantId(), account.displayName(),
                account.tradeMode().name(), account.brokerType().name(), route == null ? null : route.description(), epoch,
                state, Instant.now(), result));
    }

    public List<AccountStatus> status() {
        return List.copyOf(new LinkedHashMap<>(status).values());
    }

    @Override
    public void close() {
        squareOffs.shutdownNow();
        lanes.values().forEach(ExecutorService::shutdownNow);
        leases.values().forEach(AccountLease::close);
        leases.clear();
        connections.close();
    }
}
