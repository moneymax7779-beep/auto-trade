package com.autotrade.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.autotrade.sim.CostModel;

/**
 * Live execution L1: the execution workers' loop (run by app-execution every second). Each tick fans out the copy
 * group's new decisions once (a transaction-scoped advisory lock: one fan-out at a time across workers), then gives every
 * follower account its turn on its own lane (a single-thread executor per account, so a slow account delays only
 * itself; a lane still busy is skipped). An account trades only under its {@link AccountLease}. PAPER and DRY_RUN
 * accounts run through {@link FollowerSimulator} as workers (claimed intents, lease epoch on every order); a LIVE
 * account is refused in L1 (no broker adapter yet).
 */
public final class ExecutionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLoop.class);
    private static final int FANOUT_LOCK_CLASS = 7102;

    /** One account's last turn, for the status page. */
    public record AccountStatus(long accountId, String account, String mode, Long leaseEpoch, String state,
                                Instant lastRun, String lastResult) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    private final FanOut fanOut;
    private final String tenant;
    private final String copyGroup;
    private final String workerId;
    private final CostModel costs;
    private final QuoteSource quotes;
    private final double brokerFunds;
    private final Map<Long, ExecutorService> lanes = new ConcurrentHashMap<>();
    private final Map<Long, Future<?>> busy = new ConcurrentHashMap<>();
    private final Map<Long, AccountLease> leases = new ConcurrentHashMap<>();
    private final Map<Long, FollowerSimulator> simulators = new ConcurrentHashMap<>();
    private final Map<Long, AccountStatus> status = new ConcurrentHashMap<>();

    public ExecutionLoop(DataSource dataSource, String tenant, String copyGroup, String workerId, CostModel costs,
                         QuoteSource quotes, double brokerFunds) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                dataSource));
        this.accounts = new AccountStore(dataSource);
        this.fanOut = new FanOut(dataSource);
        this.tenant = tenant;
        this.copyGroup = copyGroup;
        this.workerId = workerId;
        this.costs = costs;
        this.quotes = quotes;
        this.brokerFunds = brokerFunds;
    }

    /** One tick for {@code day}; waits up to {@code waitMs} for the accounts' turns and returns how many ran. */
    public int tick(LocalDate day, long waitMs) {
        Optional<Long> group = accounts.copyGroupId(accounts.ensureTenant(tenant), copyGroup);
        if (group.isEmpty()) {
            return 0;
        }
        long groupId = group.get();
        transactions.executeWithoutResult(tx -> {
            Boolean mine = jdbc.queryForObject("select pg_try_advisory_xact_lock(?, ?)", Boolean.class, FANOUT_LOCK_CLASS,
                    (int) groupId);
            if (Boolean.TRUE.equals(mine)) {
                fanOut.fanOut(groupId, day);
            }
        });
        List<Future<?>> turns = new ArrayList<>();
        for (BrokerAccount account : accounts.followers(groupId)) {
            Future<?> running = busy.get(account.id());
            if (running != null && !running.isDone()) {
                continue;                                              // its lane is still on the previous turn
            }
            Future<?> turn = lanes.computeIfAbsent(account.id(), id -> Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("exec-account-" + id).daemon(true).factory()))
                    .submit(() -> turn(account, groupId, day));
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

    private void turn(BrokerAccount account, long groupId, LocalDate day) {
        String mode = account.tradeMode().name();
        try {
            if (account.tradeMode() == TradeMode.LIVE) {
                record(account, null, "LIVE_REFUSED", "live orders need a broker adapter (L2); nothing sent");
                return;
            }
            AccountLease lease = leases.get(account.id());
            if (lease != null && !lease.heartbeat()) {
                lease.close();
                leases.remove(account.id());
                simulators.remove(account.id());
                log.error("lease lost for account {} ({}); it stops trading here", account.id(), account.displayName());
                lease = null;
            }
            if (lease == null) {
                Optional<AccountLease> acquired = AccountLease.tryAcquire(dataSource, account.id(), workerId);
                if (acquired.isEmpty()) {
                    record(account, null, "LEASE_HELD_ELSEWHERE", "another worker trades this account");
                    return;
                }
                lease = acquired.get();
                leases.put(account.id(), lease);
                log.info("lease taken for account {} ({}), epoch {}", account.id(), account.displayName(), lease.epoch());
            }
            long epoch = lease.epoch();
            FollowerSimulator simulator = simulators.computeIfAbsent(account.id(), id -> new FollowerSimulator(dataSource,
                    costs, FollowerRisk.load(Path.of(account.riskFile())), brokerFunds, quotes).asWorker(workerId, epoch));
            FollowerSimulator.DayResult result = simulator.run(account, groupId, day);
            String summary = result.executed() + result.skipped() + result.rejected() == 0 ? "nothing new"
                    : "executed " + result.executed() + ", skipped " + result.skipped() + ", rejected " + result.rejected()
                    + " " + result.reasons();
            record(account, epoch, "RUNNING", summary + ", day net " + Math.round(result.net()));
        } catch (Exception e) {
            record(account, null, "ERROR", String.valueOf(e.getMessage()));
            log.error("account {} turn failed: {}", account.id(), e.getMessage());
        }
    }

    private void record(BrokerAccount account, Long epoch, String state, String result) {
        status.put(account.id(), new AccountStatus(account.id(), account.displayName(), account.tradeMode().name(), epoch,
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
    }
}
