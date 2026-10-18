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
 * DRY_RUN account with order rules trades through the Kite adapter against its own local simulator; LIVE is refused.
 * An account trades only under its {@link AccountLease}. A broker-routed account is reconciled against its broker when
 * the lease is taken and every {@link #RECONCILE_EVERY} (a difference kills its entries).
 */
public final class ExecutionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLoop.class);
    private static final int FANOUT_LOCK_CLASS = 7102;
    static final java.time.Duration RECONCILE_EVERY = java.time.Duration.ofSeconds(30);

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
    private final Map<Long, ExecutorService> lanes = new ConcurrentHashMap<>();
    private final Map<Long, Future<?>> busy = new ConcurrentHashMap<>();
    private final Map<Long, AccountLease> leases = new ConcurrentHashMap<>();
    private final Map<Long, FollowerSimulator> simulators = new ConcurrentHashMap<>();
    private final Map<Long, Instant> reconciled = new ConcurrentHashMap<>();
    private final Map<Long, FollowerRisk> risks = new ConcurrentHashMap<>();
    private final Map<Long, String> signatures = new ConcurrentHashMap<>();
    private final Map<Long, AccountStatus> status = new ConcurrentHashMap<>();

    public ExecutionLoop(DataSource dataSource, String tenant, String copyGroups, String workerId, CostModel costs,
                         QuoteSource quotes, double brokerFunds) {
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
        this.connections = new BrokerConnections(dataSource, brokerFunds);
        this.reconciler = new BrokerReconciler(dataSource, costs);
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
        return turns.size();
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
                log.info("account {} ({}) changed ({} -> {}): route rebuilt", account.id(), account.displayName(), before,
                        signature);
            }
            FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
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
                        if (e.kind() != BrokerException.Kind.SESSION) {
                            throw e;
                        }
                        // the session is gone: the simulator below refuses entries and keeps exits for the next login
                        jdbc.update("update acct.broker_account set connection_status = 'TOKEN_EXPIRED', updated_at = now() "
                                + "where id = ?", account.id());
                        check = ", BROKER SESSION LOST (" + e.getMessage() + ")";
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
        FollowerRisk risk = risks.computeIfAbsent(account.id(), id -> FollowerRisk.load(Path.of(account.riskFile())));
        BrokerConnections.Route route = connections.route(account, risk);
        if (route.venue() == null) {
            return List.of("not flattened: " + route.description());
        }
        java.util.concurrent.Callable<List<String>> task = () -> {
            AccountLease lease = leases.get(account.id());
            boolean borrowed = lease == null;                  // not an account this worker trades: given back after
            if (borrowed) {
                java.util.Optional<AccountLease> acquired = AccountLease.tryAcquire(dataSource, account.id(), workerId);
                if (acquired.isEmpty()) {
                    return List.of("not flattened here: another worker holds the account (flatten there)");
                }
                lease = acquired.get();
            }
            try {
                long epoch = lease.epoch();
                FollowerSimulator simulator = borrowed
                        ? new FollowerSimulator(dataSource, costs, risk, route.venue(), quotes).asWorker(workerId, epoch)
                        : simulators.computeIfAbsent(account.id(), id -> new FollowerSimulator(dataSource, costs, risk,
                                route.venue(), quotes).asWorker(workerId, epoch));
                return simulator.flatten(account, day, this::flattenPrice, "FLATTEN");
            } finally {
                if (borrowed) {
                    lease.close();
                }
            }
        };
        return lanes.computeIfAbsent(account.id(), id -> Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("exec-account-" + id).daemon(true).factory())).submit(task)
                .get(120, TimeUnit.SECONDS);
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
        lanes.values().forEach(ExecutorService::shutdownNow);
        leases.values().forEach(AccountLease::close);
        leases.clear();
        connections.close();
    }
}
