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
 * trades on a PAPER day are not ours to reconcile), and its exits and adds are closed as NOT_HELD. An idle account named
 * in {@link #probeOnly(java.util.Set)} keeps one read-only connection while it has today's session: its READ_LIVE probe
 * reads the real books, and nothing is ever sent through it ({@link BrokerConnections#probeOnly}).
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
    /** Every account's cost model when there are no broker rules (tests); null with them. */
    private final CostModel costs;
    /**
     * Each broker's trading-day rules (16 Nov 2026, A1): its accounts' costs (A3), last LIVE entry and square-off time.
     * Null (the constructors without them, tests): one cost model for every account, no LIVE entry, and only the
     * all-broker {@link #squareOff(LocalDate)}.
     */
    private final BrokerRules brokerRules;
    private final QuoteSource quotes;
    private final BrokerConnections connections;
    /** The account's own address as text ("-" for none): part of its route's signature ({@link #egress}). */
    private volatile java.util.function.LongFunction<String> egressOf = id -> "-";
    /** One per broker: each charges with its broker's costs (an account's state lives in its broker's reconciler). */
    private final Map<com.autotrade.account.BrokerType, BrokerReconciler> reconcilers = new ConcurrentHashMap<>();
    private final TradingDay tradingDay;
    /** The trader's manual buys ({@link ManualOrders}); tests set its clock. */
    private final ManualOrders manualOrders;
    private final Map<Long, ExecutorService> lanes = new ConcurrentHashMap<>();
    private final Map<Long, Future<?>> busy = new ConcurrentHashMap<>();
    private final Map<Long, AccountLease> leases = new ConcurrentHashMap<>();
    private final Map<Long, FollowerSimulator> simulators = new ConcurrentHashMap<>();
    private final Map<Long, Instant> reconciled = new ConcurrentHashMap<>();
    private final Map<Long, FollowerRisk> risks = new ConcurrentHashMap<>();
    private final Map<Long, String> signatures = new ConcurrentHashMap<>();
    private final Map<Long, AccountStatus> status = new ConcurrentHashMap<>();
    /** Idle accounts that keep a READ_LIVE probe (autotrade.execution.read-live-probe.accounts); none by default. */
    private volatile java.util.Set<Long> probeOnly = java.util.Set.of();
    /** When a probe that failed to open may be tried again (a broker down must not be retried every second). */
    private final Map<Long, Instant> probeRetry = new ConcurrentHashMap<>();
    static final java.time.Duration PROBE_RETRY = java.time.Duration.ofMinutes(1);
    /**
     * The square-off's day and, per broker, its first attempt that day while it has not left the broker's accounts flat
     * (16 Nov 2026, A1: each broker squares off at its own time, so each is retried, and given up, on its own clock).
     */
    private final Object squareOffState = new Object();
    private volatile LocalDate squareOffDay;
    private final Map<com.autotrade.account.BrokerType, Instant> squareOffPending = new ConcurrentHashMap<>();
    /** The brokers whose square-off time was reached on {@link #squareOffDay} ({@link #squareOffDue}: once each). */
    private final java.util.Set<com.autotrade.account.BrokerType> squareOffDueRun = ConcurrentHashMap.newKeySet();
    private volatile Instant squareOffLast = Instant.MIN;
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
        this(dataSource, tenant, copyGroups, workerId, costs, quotes, brokerFunds,
                List.of(new ZerodhaDryRunAdapter(dataSource, brokerFunds, sessions, kiteApiBase)));
    }

    /** @param adapters one order adapter per broker (8 Nov 2026: Kite, then Kotak, Upstox, Groww as each is built) */
    public ExecutionLoop(DataSource dataSource, String tenant, String copyGroups, String workerId, CostModel costs,
                         QuoteSource quotes, double brokerFunds, List<BrokerAdapter> adapters) {
        this(dataSource, tenant, copyGroups, workerId, costs, null, quotes, brokerFunds, adapters);
    }

    /**
     * The execution worker's loop (16 Nov 2026, A1/A3): each account is charged with its broker's costs file, held to its
     * broker's last LIVE entry and squared off at its broker's time ({@link #squareOffDue}).
     */
    public ExecutionLoop(DataSource dataSource, String tenant, String copyGroups, String workerId, BrokerRules rules,
                         QuoteSource quotes, double brokerFunds, List<BrokerAdapter> adapters) {
        this(dataSource, tenant, copyGroups, workerId, null, java.util.Objects.requireNonNull(rules, "broker rules"),
                quotes, brokerFunds, adapters);
    }

    private ExecutionLoop(DataSource dataSource, String tenant, String copyGroups, String workerId, CostModel costs,
                          BrokerRules rules, QuoteSource quotes, double brokerFunds, List<BrokerAdapter> adapters) {
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
        this.brokerRules = rules;
        this.quotes = quotes;
        this.connections = new BrokerConnections(dataSource, brokerFunds, adapters);
        this.tradingDay = new TradingDay(dataSource);
        this.manualOrders = new ManualOrders(dataSource);
    }

    /**
     * The idle accounts that keep a READ_LIVE probe (user 8 Nov 2026: a member's Kotak account on PAPER days). Reads only:
     * the probe connection is never a route, so it sends nothing, real or simulated.
     */
    /** The brokers configured LIVE: a LIVE route opens only for those (review 16 Nov 2026). */
    public ExecutionLoop liveBrokers(LiveBrokers live) {
        connections.liveBrokers(live::live);
        return this;
    }

    public ExecutionLoop probeOnly(java.util.Set<Long> accountIds) {
        this.probeOnly = java.util.Set.copyOf(accountIds);
        return this;
    }

    /**
     * The accounts' own static IPs (18 Nov 2026): an account whose address comes up, changes or goes away gets a fresh
     * route and READ_LIVE probe, like a changed mode or risk file. A broker client built before the address existed kept
     * sending from the shared route (seen on GCP: account 21's Kotak probe after its IP was provisioned). The registry
     * never moves an address under a LIVE or busy account, so this never rebuilds a route that holds anything.
     */
    public ExecutionLoop egress(AccountEgress egress) {
        java.util.Objects.requireNonNull(egress);
        this.egressOf = id -> egress.configured(id).map(java.net.InetAddress::getHostAddress).orElse("-");
        return this;
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
        if (!day.equals(squareOffDay) || squareOffPending.isEmpty()
                || squareOffLast.plus(squareOffRetry).isAfter(Instant.now())
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
        java.util.Set<com.autotrade.account.BrokerType> again = java.util.EnumSet.noneOf(
                com.autotrade.account.BrokerType.class);
        java.util.Set<com.autotrade.account.BrokerType> expired = java.util.EnumSet.noneOf(
                com.autotrade.account.BrokerType.class);
        synchronized (squareOffState) {
            if (!day.equals(squareOffDay)) {
                return;
            }
            squareOffPending.forEach((broker, started) -> (Instant.now().isAfter(started.plus(SQUARE_OFF_RETRY_FOR))
                    ? expired : again).add(broker));
            expired.forEach(squareOffPending::remove);
        }
        for (long accountId : List.copyOf(leases.keySet())) {
            BrokerAccount account = accounts.byId(accountId).orElse(null);
            int open = account == null || !expired.contains(account.brokerType()) ? 0 : openPositions(accountId);
            if (open > 0) {
                new KillSwitches(dataSource).audit(account, workerId, "EOD_SQUARE_OFF", Map.of("positions",
                        List.of(open + " position(s) STILL OPEN after " + SQUARE_OFF_RETRY_FOR.toMinutes()
                                + " min of square-off attempts: close them in the broker's app")));
                log.error("account {} ({}): {} position(s) still open after the square-off attempts", accountId,
                        account.displayName(), open);
            }
        }
        if (!again.isEmpty()) {
            squareOff(day, again).forEach(line -> log.warn("end-of-day square-off (again): {}", line));
        }
    }

    /** Tests: the square-off retry interval. */
    ExecutionLoop squareOffRetry(java.time.Duration every) {
        this.squareOffRetry = every;
        return this;
    }

    /** Tests: runs {@code work} on the account's own lane (a lane kept busy stands for a slow or stuck broker). */
    Future<?> onLane(long accountId, Runnable work) {
        return lanes.computeIfAbsent(accountId, id -> Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("exec-account-" + id).daemon(true).factory())).submit(work);
    }

    /** Tests: the account is reconciled at its next turn. */
    void reconcileNextTurn(long accountId) {
        reconciled.remove(accountId);
    }

    /**
     * An idle account's READ_LIVE probe: open while the account has today's ACTIVE session (a new login opens a fresh one),
     * closed otherwise; a failed open is tried again after {@link #PROBE_RETRY}. Returns the status line.
     */
    private String probe(BrokerAccount account, LocalDate day) {
        if (!day.equals(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) {
            connections.closeProbe(account.id());
            return "READ_LIVE probe: only today's session, not " + day;
        }
        Boolean session = jdbc.queryForObject("select exists (select 1 from acct.broker_session where broker_account_id = ? "
                + "and status = 'ACTIVE' and expires_at > now())", Boolean.class, account.id());
        if (!Boolean.TRUE.equals(session)) {
            connections.closeProbe(account.id());
            return "READ_LIVE probe waits for today's login";
        }
        Instant retry = probeRetry.get(account.id());
        if (retry != null && Instant.now().isBefore(retry)) {
            return "READ_LIVE probe did not open; tried again after " + retry;
        }
        try {
            FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
            String line = connections.probeOnly(account, risk);
            probeRetry.remove(account.id());
            return line;
        } catch (RuntimeException e) {
            probeRetry.put(account.id(), Instant.now().plus(PROBE_RETRY));
            log.warn("account {} ({}): READ_LIVE probe did not open: {}", account.id(), account.displayName(),
                    e.getMessage());
            return "READ_LIVE probe did not open: " + e.getMessage();
        }
    }

    private int openPositions(long accountId) {
        Integer open = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? "
                + "and status = 'OPEN' and quantity_open > 0", Integer.class, accountId);
        return open == null ? 0 : open;
    }

    private void turn(BrokerAccount account, List<Long> groupIds, LocalDate day) {
        try {
            // an account whose mode, broker, risk file or own address changed gets a fresh risk, route and simulator
            String signature = account.tradeMode() + "/" + account.brokerType() + "/" + account.riskFile() + "/"
                    + egressOf.apply(account.id());
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
                record(account, null, null, "IDLE", tradingDay.notTargetReason(account) + "; this account holds nothing "
                        + "and sends no orders; " + (probeOnly.contains(account.id()) ? probe(account, day)
                        : "no broker connection")
                        + (closed > 0 ? " (" + closed + " exit/add closed NOT_HELD)" : ""));
                return;
            }
            if (connections.probing(account.id())) {
                connections.closeProbe(account.id());                // it trades now: its route reads on its own
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
                        BrokerReconciler.Check c = reconciler(account).check(account, route.connection());
                        check = c.matched() ? "" : ", RECONCILIATION " + c.differences();
                    } catch (BrokerException e) {
                        if (e.isNoMarketData()) {
                            // 23 Nov 2026: the app may not read prices; the login is valid and stays (see BrokerException)
                            new KillSwitches(dataSource).noMarketData(account, workerId,
                                    Map.of("message", String.valueOf(e.getMessage()), "during", "reconciliation"));
                            tradingDay.noMarketData(account, workerId);
                            check = ", NO MARKET DATA (" + e.getMessage() + ")";
                        } else if (e.kind() == BrokerException.Kind.SESSION) {
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
            FollowerSimulator simulator = simulators.computeIfAbsent(account.id(), id -> follower(account, risk, route,
                    epoch));
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
     * and under its lease, every open position is exited (broker stops off first). A simulated venue prices it as a
     * person's exit ({@link #flattenPrice}: never the entry price; refused without a bid or a signal price today).
     */
    public List<String> flatten(long accountId, String actor, String reason, LocalDate day) throws Exception {
        return flatten(accountId, actor, reason, day, Map.of());
    }

    /** As above, with {@code audit} added to the FLATTEN audit detail (an admin's flatten: by_admin, consent). */
    public List<String> flatten(long accountId, String actor, String reason, LocalDate day, Map<String, ?> audit)
            throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        // second review 20 Nov 2026: an admin's flatten marks its kill by_admin too (the member is told once, by AdminExits)
        new KillSwitches(dataSource).engageAccount(account, "FLATTEN by " + actor + ": " + reason, actor,
                Boolean.TRUE.equals(audit.get("by_admin")) ? Map.of("by_admin", true) : Map.of());
        return flattenOnLane(account, day, "FLATTEN", null, null, withActor(actor, audit));
    }

    /** The audit detail of a person's exit: who asked (the simulator's audit row is written as "flatten"). */
    private static Map<String, Object> withActor(String actor, Map<String, ?> audit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actor", actor);
        out.putAll(audit);
        return out;
    }

    /**
     * The exits a person asked for (admin exit desk, user 20 Nov 2026): a simulated venue sells these only at a bid of the
     * last 5 minutes and refuses without one (never at the entry price). The automated ones (the end-of-day square-off)
     * keep the old fallback; every exit records where its price came from in the audit detail.
     */
    static final java.util.Set<String> PERSON_EXITS = java.util.Set.of("TRADER_EXIT", "ADMIN_EXIT", "FLATTEN",
            PositionTargets.EXIT_REASON);

    /**
     * The square-off of every broker whose time ({@link BrokerRules#squareOff}) has come at {@code now} (IST) and that was
     * not yet run today (16 Nov 2026, A1: the worker calls this every minute from 15:00 to 15:30 instead of one 15:20
     * square-off for every broker). A broker whose accounts are not left flat is retried like {@link #squareOff}.
     */
    public List<String> squareOffDue(LocalDate day, java.time.LocalTime now) {
        if (brokerRules == null) {
            throw new IllegalStateException("no broker rules: the brokers' square-off times are not known");
        }
        java.util.Set<com.autotrade.account.BrokerType> due;
        synchronized (squareOffState) {
            newSquareOffDay(day);
            due = brokerRules.squareOffDue(now);
            due.removeAll(squareOffDueRun);
            squareOffDueRun.addAll(due);
        }
        if (due.isEmpty()) {
            return List.of();
        }
        log.warn("end-of-day square-off of {} accounts ({} IST, {})", due, now.withNano(0), brokerRules.version());
        return squareOff(day, due);
    }

    private void newSquareOffDay(LocalDate day) {
        if (!day.equals(squareOffDay)) {
            squareOffPending.clear();
            squareOffDueRun.clear();
            squareOffDay = day;
        }
    }

    /**
     * End-of-day square-off of every broker's accounts now (drills, tests); the worker runs each broker's at its own time
     * ({@link #squareOffDue}).
     */
    public List<String> squareOff(LocalDate day) {
        return squareOff(day, java.util.EnumSet.allOf(com.autotrade.account.BrokerType.class));
    }

    /**
     * End-of-day square-off (code review 24 Oct 2026) of the accounts of {@code brokers}: a follower closes only on its
     * signal source's EXIT decisions, so when the signal source stopped mid-day its positions would stay open overnight
     * (and block the next day's entries for that strategy). At the square-off time every account this worker holds that
     * still has an open position is flattened like the Flatten button, but without a kill switch (the next day trades
     * normally); audited EOD_SQUARE_OFF. Every strategy of the live set is flat by 15:15, so this is a safety net. Review
     * 8 Nov 2026: tried again from each tick until flat ({@link #SQUARE_OFF_RETRY}), per broker since 16 Nov 2026.
     */
    public List<String> squareOff(LocalDate day, java.util.Set<com.autotrade.account.BrokerType> brokers) {
        synchronized (squareOffState) {
            // the pending brokers and the time first, then a tick reading them finds them set for this day
            newSquareOffDay(day);
            Instant now = Instant.now();
            brokers.forEach(b -> squareOffPending.putIfAbsent(b, now));
            squareOffLast = now;
        }
        List<String> lines = new ArrayList<>();
        // validation 16 Nov 2026: every account at once, each on its own lane (one slow or stuck broker account no longer
        // holds the others back toward the broker's own MIS cut-off); the results are gathered in account order
        Map<Long, java.util.concurrent.Future<List<String>>> runs = new java.util.LinkedHashMap<>();
        try (ExecutorService all = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long accountId : List.copyOf(leases.keySet())) {
                BrokerAccount account = accounts.byId(accountId).orElse(null);
                if (account == null || !brokers.contains(account.brokerType()) || openPositions(accountId) == 0) {
                    continue;
                }
                runs.put(accountId, all.submit(() -> {
                    List<String> out = new ArrayList<>();
                    try {
                        List<String> done = flattenOnLane(account, day, "EOD_SQUARE_OFF");
                        new KillSwitches(dataSource).audit(account, workerId, "EOD_SQUARE_OFF",
                                java.util.Map.of("positions", done));
                        done.forEach(line -> out.add(account.displayName() + ": " + line));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        out.add(account.displayName() + ": square-off interrupted");
                    } catch (Exception e) {
                        out.add(account.displayName() + ": square-off failed (" + e.getMessage() + ")");
                    }
                    return out;
                }));
            }
        }                                                           // close() waits for every account
        java.util.Set<com.autotrade.account.BrokerType> open = java.util.EnumSet.noneOf(
                com.autotrade.account.BrokerType.class);
        // review 16 Nov 2026 (D9): a LIVE account holding positions that no worker leases (its route was refused: no
        // turn, so no lease) was skipped in silence, and its broker dropped from the retries; now it is said at once (the
        // EOD_SQUARE_OFF alert, once a day) and its broker stays pending, so a route that comes back is squared off
        for (BrokerAccount stranded : unleasedLive(brokers)) {
            int held = openPositions(stranded.id());
            if (held == 0) {
                continue;
            }
            String line = "NOT squared off: no worker trades this account now (its LIVE route refused, or no worker serves "
                    + "it); " + held + " position(s) open: close them in the broker's app";
            lines.add(stranded.displayName() + ": " + line);
            open.add(stranded.brokerType());
            if (strandedSaid.add(day + ":" + stranded.id())) {
                new KillSwitches(dataSource).audit(stranded, workerId, "EOD_SQUARE_OFF", Map.of("positions",
                        List.of(line)));
                log.error("account {} ({}): {}", stranded.id(), stranded.displayName(), line);
            }
        }
        for (Map.Entry<Long, java.util.concurrent.Future<List<String>>> run : runs.entrySet()) {
            try {
                lines.addAll(run.getValue().get());
            } catch (Exception e) {
                lines.add(accounts.byId(run.getKey()).map(BrokerAccount::displayName).orElse("account " + run.getKey())
                        + ": square-off failed (" + e.getMessage() + ")");
            }
            if (openPositions(run.getKey()) > 0) {
                accounts.byId(run.getKey()).ifPresent(a -> open.add(a.brokerType()));
            }
        }
        synchronized (squareOffState) {
            if (day.equals(squareOffDay)) {
                for (com.autotrade.account.BrokerType b : brokers) {
                    if (!open.contains(b)) {
                        squareOffPending.remove(b);                 // flat: no more attempts today
                    }
                }
            }
        }
        return lines;
    }

    /**
     * LIVE accounts of {@code brokers} holding open positions that neither this worker nor any other leases now (no lease
     * heartbeat for {@link #LEASE_SILENT}): nothing would square them off.
     */
    private List<BrokerAccount> unleasedLive(java.util.Set<com.autotrade.account.BrokerType> brokers) {
        List<BrokerAccount> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("select a.id from acct.broker_account a where a.trade_mode = 'LIVE' and exists "
                + "(select 1 from exec.position p where p.broker_account_id = a.id and p.status = 'OPEN' and "
                + "p.quantity_open > 0) and not exists (select 1 from exec.account_lease l where l.broker_account_id = a.id "
                + "and l.heartbeat_at > now() - make_interval(secs => ?)) order by a.id", Long.class,
                (double) LEASE_SILENT.toSeconds())) {
            if (!leases.containsKey(id)) {
                accounts.byId(id).filter(a -> brokers.contains(a.brokerType())).ifPresent(out::add);
            }
        }
        return out;
    }

    /** How long another worker's lease may go without a heartbeat before its accounts count as nobody's. */
    static final java.time.Duration LEASE_SILENT = java.time.Duration.ofMinutes(2);
    /** The unleased LIVE accounts said at the square-off, once a day each. */
    private final java.util.Set<String> strandedSaid = ConcurrentHashMap.newKeySet();

    /**
     * Exit one open position now (the trader's per-position Exit, 7 Nov 2026): like {@link #flatten} for that position
     * alone (stop off first, sold at the broker or at the stored bid), audited POSITION_EXIT; no kill switch, the
     * account keeps trading.
     */
    public List<String> exitPosition(long accountId, long positionId, String actor, LocalDate day) throws Exception {
        return exitPosition(accountId, positionId, actor, day, null);
    }

    /** {@code quantity}: sell this many (whole lots) and keep the rest open; null: all of it (17 Nov 2026). */
    public List<String> exitPosition(long accountId, long positionId, String actor, LocalDate day, Long quantity)
            throws Exception {
        return exitPosition(accountId, positionId, actor, day, quantity, false, Map.of());
    }

    /**
     * As above; {@code byAdmin} (admin exit desk, user 20 Nov 2026): someone other than the account's owner asked (the
     * platform admin), so the position closes as ADMIN_EXIT instead of TRADER_EXIT ("exited by you" would be wrong on the
     * member's page); {@code audit} is added to the POSITION_EXIT audit detail (reason, consent, batch).
     */
    public List<String> exitPosition(long accountId, long positionId, String actor, LocalDate day, Long quantity,
                                     boolean byAdmin, Map<String, ?> audit) throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        if (account.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        List<String> lines = flattenOnLane(account, day, byAdmin ? "ADMIN_EXIT" : "TRADER_EXIT", positionId, quantity,
                withActor(actor, audit));
        return lines.isEmpty() ? List.of("position " + positionId + " is not open") : lines;
    }

    /**
     * The trader's manual target sells one whole position ({@link PositionTargets}, user 24 Nov 2026): the per-position Exit
     * path (stop off first, sold at the broker or at a fresh stored bid, never the entry price), exit reason
     * {@link PositionTargets#EXIT_REASON}; no kill switch, the account keeps trading.
     */
    public List<String> exitAtTarget(long accountId, long positionId, LocalDate day, Map<String, ?> audit) throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        if (account.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        List<String> lines = flattenOnLane(account, day, PositionTargets.EXIT_REASON, positionId, null,
                withActor(PositionTargets.ACTOR, audit));
        return lines.isEmpty() ? List.of("position " + positionId + " is not open") : lines;
    }

    /**
     * Whether another worker holds the account now (a lease of another owner with a heartbeat in the last
     * {@link #LEASE_SILENT}): that worker sells its targets ({@link PositionTargets.Exiter#mine}).
     */
    public boolean leasedElsewhere(long accountId) {
        if (leases.containsKey(accountId)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from exec.account_lease where "
                + "broker_account_id = ? and owner <> ? and heartbeat_at > now() - make_interval(secs => ?))", Boolean.class,
                accountId, workerId, (double) LEASE_SILENT.toSeconds()));
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
            return new ManualExit(dataSource, costsOf(account)).book(account, positionId, price, at, actor, holding,
                    unchecked);
        });
    }

    private ManualOrders manual() {
        return manualOrders;
    }

    /** Tests: the manual orders' clock. */
    ExecutionLoop manualClock(java.time.Clock clock) {
        manual().withClock(clock);
        return this;
    }

    /**
     * The contract a manual BUY CE / BUY PE on the account would take now (the confirmation shows it); see
     * {@link ManualOrders#pick}.
     */
    public ManualOrders.Pick manualQuote(long accountId, String underlying, String side, LocalDate day) {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        return manual().pick(account, underlying, side, day);
    }

    /**
     * The trader's BUY CE / BUY PE on one follower account ({@link ManualOrders}): written as a decision of strategy
     * {@link ManualOrders#STRATEGY} in a copy group this worker serves and the account follows, with one intent for the
     * account only; the decision's notification runs the account's turn at once. IllegalArgumentException when refused.
     */
    public ManualOrders.Placed manualBuy(long accountId, String underlying, String side, String expectedSymbol, String actor,
                                         LocalDate day) {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        if (account.tradeMode() == TradeMode.LIVE) {
            // review 9 Nov 2026: refused here, not as a REJECTED intent after the fact (the file as it is now)
            FollowerRisk risk = FollowerRisk.load(Path.of(account.riskFile()));
            if (risk.live() == null || !risk.live().strategies().contains(ManualOrders.STRATEGY)) {
                throw new IllegalArgumentException(account.displayName() + "'s risk file " + account.riskFile()
                        + " does not allow manual LIVE orders (follower-risk v13 does: v11 plus manual): nothing sent");
            }
        }
        return manual().buy(account, manualGroup(account), underlying, side, expectedSymbol, actor, day);
    }

    /** {@link #manualGroup(BrokerAccount)} by account id (a chart order is armed in it). */
    public long manualGroup(long accountId) {
        return manualGroup(accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId)));
    }

    /**
     * A served copy group the account follows (its turn runs only those groups' intents): its own tenant's first (review
     * 9 Nov 2026: never a group shared to it by another tenant when its own will do).
     */
    long manualGroup(BrokerAccount account) {
        List<Long> served = groups();
        List<Long> followed = jdbc.queryForList("select g.id from acct.copy_group g where exists (select 1 from "
                + "acct.strategy_subscription s where s.copy_group_id = g.id and s.broker_account_id = ? and s.enabled) "
                + "order by (g.tenant_id = ?) desc, g.id", Long.class, account.id(), account.tenantId());
        for (long group : followed) {
            if (served.contains(group)) {
                return group;
            }
        }
        throw new IllegalArgumentException(account.displayName() + " follows no copy group this worker serves: "
                + "subscribe it to a strategy first");
    }

    /**
     * The trader's EXIT of manual orders ({@code underlying} null = every index), on the account's own lane: a manual
     * buy whose turn has not run yet is cancelled (nothing sent), then every open manual position is sold as the
     * per-position Exit sells it (stop off first, the exit chased; a broker-routed account's entry still working ends its
     * turn first, so what filled of it is what is sold), audited POSITION_EXIT. The account keeps trading.
     */
    public List<String> manualExit(long accountId, String underlying, String actor, LocalDate day) throws Exception {
        return manualExit(accountId, underlying, actor, day, null);
    }

    /**
     * As {@link #manualExit}, of the one buy {@code entryIntent} and its position when given: a chart order's Exit now
     * (second review 16 Nov 2026: it ran the index's whole manual EXIT, so a panel buy or position of the index went too).
     */
    public List<String> manualExit(long accountId, String underlying, String actor, LocalDate day, Long entryIntent)
            throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        if (account.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        String u = underlying == null || underlying.isBlank() ? null : underlying.trim().toUpperCase(java.util.Locale.ROOT);
        FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
        try {
            return onLane(account, (epoch, borrowed) -> {
                List<String> lines = new ArrayList<>();
                lines.addAll(manual().cancelNotRun(account, u, actor, entryIntent));
                List<Long> open = manual().openPositions(account.id(), u, entryIntent);
                if (open.isEmpty()) {
                    // code review 9 Nov 2026: no broker route is opened for nothing to sell (an idle workspace account
                    // has none by design, and a route that cannot open was a 500 instead of this answer)
                    if (lines.isEmpty()) {
                        lines.add("no manual " + (u == null ? "" : u + " ") + "position or buy to exit");
                    }
                    return lines;
                }
                BrokerConnections.Route route = connections.route(account, risk);
                if (route.venue() == null) {
                    lines.add("not exited: " + route.description());
                    return lines;
                }
                for (long positionId : open) {
                    lines.addAll(flattenHere(account, risk, route, epoch, borrowed, day, "TRADER_EXIT", positionId, null,
                            withActor(actor, Map.of())));
                }
                return lines;
            });
        } catch (java.util.concurrent.TimeoutException e) {
            // review 9 Nov 2026: the EXIT is still queued on the account's lane and runs when its turn ends; it is not a
            // failure the trader should answer by selling in the broker's app too
            return List.of("EXIT queued behind the account's running turn: it sells when that turn ends; watch the orders "
                    + "here before doing anything in the broker's app");
        } catch (IllegalStateException e) {
            if (!String.valueOf(e.getMessage()).startsWith("another worker")) {
                throw e;
            }
            return List.of("not exited here: " + e.getMessage());
        }
    }

    private List<String> flattenOnLane(BrokerAccount account, LocalDate day, String reason) throws Exception {
        return flattenOnLane(account, day, reason, null, null, Map.of());
    }

    private List<String> flattenOnLane(BrokerAccount account, LocalDate day, String reason, Long positionId, Long quantity,
                                       Map<String, ?> audit) throws Exception {
        FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
        BrokerConnections.Route route = connections.route(account, risk);
        if (route.venue() == null) {
            return List.of("not flattened: " + route.description());
        }
        try {
            return onLane(account, (epoch, borrowed) -> flattenHere(account, risk, route, epoch, borrowed, day, reason,
                    positionId, quantity, audit));
        } catch (IllegalStateException e) {
            if (!String.valueOf(e.getMessage()).startsWith("another worker")) {
                throw e;
            }
            return List.of("not flattened here: " + e.getMessage());
        }
    }

    /** A flatten already on the account's lane, under its lease ({@code borrowed}: a simulator of its own). */
    private List<String> flattenHere(BrokerAccount account, FollowerRisk risk, BrokerConnections.Route route, long epoch,
                                     boolean borrowed, LocalDate day, String reason, Long positionId, Long quantity,
                                     Map<String, ?> audit) {
        // main 16 Nov 2026 (multi-broker): the account's own costs and the broker's rules, as every follower is built
        FollowerSimulator simulator = borrowed ? follower(account, risk, route, epoch)
                : simulators.computeIfAbsent(account.id(), id -> follower(account, risk, route, epoch));
        boolean person = PERSON_EXITS.contains(reason);
        return simulator.flatten(account, day, position -> flattenPrice(position, person), reason, positionId, quantity,
                audit);
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

    /** How old a stored bid may be to count as fresh for a simulated exit (else a person's exit says how old it is). */
    static final java.time.Duration EXIT_QUOTE_MAX_AGE = java.time.Duration.ofMinutes(5);
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    /**
     * A simulated exit's price. Automated exits (the end-of-day square-off): the stored bid of the last 5 minutes, else the
     * contract's last signal price, else the entry price, as before. A person's exit ({@code person}; admin exit desk
     * review, user 20 Nov 2026), never the entry price: the stored bid of the last 5 minutes; else today's latest stored bid
     * of any age ("stale-bid", its age said in the line and the audit); else the contract's last signal price today
     * ("signal"); else refused. A broker venue prices from the broker and ignores all of this.
     */
    private FollowerSimulator.ExitPrice flattenPrice(Map<String, Object> position, boolean person) {
        long token = ((Number) position.get("instrument_token")).longValue();
        List<Double> bid = jdbc.queryForList("select bid_px[1] from md.option_tick where instrument_token = ? "
                + "and recv_ts > now() - make_interval(secs => ?) and bid_px[1] > 0 order by recv_ts desc limit 1",
                Double.class, token, (double) EXIT_QUOTE_MAX_AGE.toSeconds());
        if (!bid.isEmpty() && bid.getFirst() != null) {
            return new FollowerSimulator.ExitPrice(bid.getFirst(), "bid", null);
        }
        if (!person) {
            List<Double> signal = jdbc.queryForList("select reference_price from exec.signal_decision where instrument_token "
                    + "= ? and reference_price > 0 order by decided_at desc limit 1", Double.class, token);
            boolean fromSignal = !signal.isEmpty() && signal.getFirst() != null;
            return new FollowerSimulator.ExitPrice(fromSignal ? signal.getFirst()
                    : ((Number) position.get("average_entry")).doubleValue(), fromSignal ? "signal" : "entry", null);
        }
        java.sql.Timestamp dayStart = java.sql.Timestamp.from(LocalDate.now(IST).atStartOfDay(IST).toInstant());
        Map<String, Object> stale = todaysLastBid(position, token, dayStart);
        if (stale != null) {
            long minutes = Math.max(0, java.time.Duration.between(((java.sql.Timestamp) stale.get("recv_ts")).toInstant(),
                    Instant.now()).toMinutes());
            return new FollowerSimulator.ExitPrice(((Number) stale.get("bid")).doubleValue(), "stale-bid", null,
                    "last bid " + minutes + " min old");
        }
        // only a signal decided after the position opened (an EXIT or an ADD of the contract): the latest signal of the day
        // is usually the position's own ENTER, whose price would book a fake zero P&L (principal review, 20 Nov 2026)
        Object openedAt = position.get("opened_at");
        java.sql.Timestamp after = openedAt instanceof java.sql.Timestamp t && t.after(dayStart) ? t : dayStart;
        List<Double> signal = jdbc.queryForList("select reference_price from exec.signal_decision where instrument_token = ? "
                + "and reference_price > 0 and decided_at > ? order by decided_at desc limit 1", Double.class, token, after);
        if (!signal.isEmpty() && signal.getFirst() != null) {
            return new FollowerSimulator.ExitPrice(signal.getFirst(), "signal", null,
                    "no bid today: the price of a signal after the entry");
        }
        return new FollowerSimulator.ExitPrice(Double.NaN, "none", "no bid today and no signal since the entry: "
                + "a paper exit is never booked at the entry price; nothing sold");
    }

    /**
     * Today's newest stored bid of the position's contract (bid, recv_ts), read through the contract index (underlying,
     * expiry, strike, type, time) when ref.instrument knows the key, else by token within today; null when none.
     */
    private Map<String, Object> todaysLastBid(Map<String, Object> position, long token, java.sql.Timestamp dayStart) {
        String key = (String) position.get("instrument_key");
        List<Map<String, Object>> contract = key == null ? List.of() : jdbc.queryForList("select expiry, strike, type "
                + "from ref.instrument where instrument_key = ? and type in ('CE', 'PE') order by snapshot_id desc limit 1",
                InstrumentKeys.normalize(key, token));
        List<Map<String, Object>> rows = contract.isEmpty()
                ? jdbc.queryForList("select bid_px[1] bid, recv_ts from md.option_tick where instrument_token = ? "
                        + "and recv_ts >= ? and bid_px[1] > 0 order by recv_ts desc limit 1", token, dayStart)
                : jdbc.queryForList("select bid_px[1] bid, recv_ts from md.option_tick where underlying = ? and expiry = ? "
                        + "and strike = ? and option_type = ? and recv_ts >= ? and bid_px[1] > 0 order by recv_ts desc "
                        + "limit 1", position.get("underlying"), contract.getFirst().get("expiry"),
                        contract.getFirst().get("strike"), contract.getFirst().get("type"), dayStart);
        return rows.isEmpty() || rows.getFirst().get("bid") == null ? null : rows.getFirst();
    }

    /**
     * One position's exit judged on the account's own lane (admin exit desk review, user 20 Nov 2026): the position is read
     * there just before the exit (closed by then, e.g. by its strategy: ALREADY_CLOSED, nothing sent), the exit runs as
     * {@link #exitPosition(long, long, String, LocalDate, Long, boolean, Map)} does, and the position and the exit orders
     * it wrote are read after it ({@link ExitOutcome#classify}): an order that may still work is UNKNOWN, never REFUSED.
     */
    public ExitOutcome exitPositionOutcome(long accountId, long positionId, String actor, LocalDate day, boolean byAdmin,
                                           Map<String, ?> audit) throws Exception {
        BrokerAccount account = accounts.byId(accountId).orElseThrow(() -> new IllegalArgumentException("no account "
                + accountId));
        if (account.role() == com.autotrade.account.AccountRole.SIGNAL_SOURCE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: its positions are not orders");
        }
        FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
        BrokerConnections.Route route = connections.route(account, risk);
        if (route.venue() == null) {
            // plan phase D2 (20 Nov 2026): a LIVE account the worker cannot reach (logged out, no static IP, ...) says what
            // to do instead; its broker stop was never touched
            return ExitOutcome.refused(List.of("not exited: " + route.description()), route.description()
                    + (account.tradeMode() == TradeMode.LIVE ? "; nothing sent, its broker stop untouched: ask the holder "
                    + "to exit in the broker app, then book a hand exit here" : ""));
        }
        Map<String, ?> detail = withActor(actor, audit);
        try {
            return onLane(account, (epoch, borrowed) -> {
                Map<String, Object> before = jdbc.queryForList("select status, quantity_open, entry_trade_intent_id from "
                        + "exec.position where id = ? and broker_account_id = ?", positionId, accountId).stream().findFirst()
                        .orElse(null);
                if (before == null) {
                    return ExitOutcome.refused(List.of(), "no position " + positionId + " in " + account.displayName());
                }
                long open = ((Number) before.get("quantity_open")).longValue();
                if ("CLOSED".equals(before.get("status")) || open <= 0) {
                    return new ExitOutcome(ExitOutcome.ALREADY_CLOSED, open, open, List.of(), "closed before the exit "
                            + "got its turn (its strategy, a stop or another exit)");
                }
                if (!"OPEN".equals(before.get("status"))) {
                    // second review 20 Nov 2026: RECONCILIATION_REQUIRED (our record and the broker's disagree): the flatten
                    // sells only OPEN positions, so nothing is sent; at a broker what it holds is not known
                    String why = "nothing sent: the position is " + before.get("status") + " (our record and the broker's "
                            + "disagree); reconcile it first";
                    return route.venue().broker() ? new ExitOutcome(ExitOutcome.UNKNOWN, open, open, List.of(), why + ": "
                            + ExitOutcome.UNKNOWN_ADVICE) : new ExitOutcome(ExitOutcome.REFUSED, open, open, List.of(), why);
                }
                long intent = ((Number) before.get("entry_trade_intent_id")).longValue();
                long mark = jdbc.queryForObject("select coalesce(max(id), 0) from exec.order_execution", Long.class);
                List<String> lines;
                RuntimeException failure = null;
                try {
                    lines = flattenHere(account, risk, route, epoch, borrowed, day, byAdmin ? "ADMIN_EXIT" : "TRADER_EXIT",
                            positionId, null, detail);
                } catch (RuntimeException e) {
                    failure = e;
                    lines = List.of("the exit failed: " + e.getMessage());
                    log.error("account {} ({}): exit of position {} failed", account.id(), account.displayName(), positionId,
                            e);
                }
                try {
                    Map<String, Object> after = jdbc.queryForMap("select status, quantity_open from exec.position where id = ?",
                            positionId);
                    List<String> orders = jdbc.queryForList("select status from exec.order_execution where trade_intent_id = ? "
                            + "and role = 'EMERGENCY_EXIT' and id > ? order by id", String.class, intent, mark);
                    return ExitOutcome.classify(open, ((Number) after.get("quantity_open")).longValue(),
                            "CLOSED".equals(after.get("status")), lines, orders, failure, route.venue().broker());
                } catch (RuntimeException e) {
                    return new ExitOutcome(ExitOutcome.UNKNOWN, open, null, lines, "the exit was sent but its result could "
                            + "not be read (" + e.getMessage() + "): " + ExitOutcome.UNKNOWN_ADVICE);
                }
            });
        } catch (java.util.concurrent.TimeoutException e) {
            return ExitOutcome.unknown(List.of(), "the exit is still queued behind the account's running turn and may "
                    + "still sell");
        } catch (IllegalStateException e) {
            if (!String.valueOf(e.getMessage()).startsWith("another worker")) {
                throw e;
            }
            return ExitOutcome.refused(List.of("not exited here: " + e.getMessage()), e.getMessage());
        }
    }

    /** End-of-day broker reconciliation for every broker-routed account this worker holds; returns the ledger rows. */
    public List<BrokerReconciler.EndOfDay> endOfDay(LocalDate day) {
        List<BrokerReconciler.EndOfDay> days = new ArrayList<>();
        for (long accountId : leases.keySet()) {
            BrokerAccount account = null;
            try {
                account = accounts.byId(accountId).orElse(null);
                BrokerConnections.Route route = account == null ? null
                        : connections.route(account, FollowerRisk.load(Path.of(account.riskFile())));
                if (route != null && route.connection() != null) {
                    days.add(reconciler(account).endOfDay(account, route.connection(), day));
                }
            } catch (RuntimeException e) {
                // review 8 Nov 2026: one account's failure (a lost session, a failed read) stopped the loop, so the
                // accounts after it got no ledger row (which a LIVE account needs acknowledged before its next start)
                log.error("account {}: end-of-day reconciliation failed", accountId, e);
                if (account != null) {
                    try {
                        new KillSwitches(dataSource).audit(account, workerId, "END_OF_DAY_FAILED", Map.of("day",
                                day.toString(), "error", String.valueOf(e.getMessage())));
                    } catch (RuntimeException a) {
                        log.error("account {}: END_OF_DAY_FAILED not audited: {}", accountId, a.getMessage());
                    }
                }
            }
        }
        return days;
    }

    /** The cost model that charges the account: its broker's costs file (A3), else the loop's one model. */
    private CostModel costsOf(BrokerAccount account) {
        return brokerRules == null ? costs : brokerRules.costs(account.brokerType());
    }

    private BrokerReconciler reconciler(BrokerAccount account) {
        return reconcilers.computeIfAbsent(account.brokerType(), b -> new BrokerReconciler(dataSource, costsOf(account)));
    }

    /** The account's simulator on this worker: its broker's costs and, with the broker rules, its last LIVE entry. */
    private FollowerSimulator follower(BrokerAccount account, FollowerRisk risk, BrokerConnections.Route route,
                                       long epoch) {
        return new FollowerSimulator(dataSource, costsOf(account), risk, route.venue(), quotes)
                .withBrokerRules(brokerRules).asWorker(workerId, epoch);
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
