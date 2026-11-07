package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Trader workspaces (V29): the day's broker and its PAPER/LIVE switch decide where a DAILY_SWITCH workspace's entries go,
 * as of each decision's time, once per decision; exits and adds reach whoever holds the position; idle real accounts get
 * no turn; a lost session sends the day back to PAPER; the member's kill switch and LIVE loss limit; ACCOUNT_MODE
 * workspaces unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
class TradingDayIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static int seq;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    /** A workspace: the owner's signal group shared with a DAILY_SWITCH member tenant holding a real broker account. */
    private record Workspace(long group, long tenant, long member, long real, long twin) {
    }

    private static Workspace workspace(String name, LocalDate day, boolean liveEligible) {
        AccountStore accounts = new AccountStore(dataSource);
        long owner = accounts.ensureTenant("owner-" + name);
        long ownerUser = accounts.ensureUser("kc-owner-" + name, "owner");
        accounts.ensureMembership(owner, ownerUser, "ADMIN");
        long signal = accounts.ensureSignalSourceAccount(owner, ownerUser, "SIG-" + name, 500_000, day,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(owner, "SET-" + name, signal);
        long tenant = accounts.ensureTenant("family-" + name);
        long member = accounts.ensureUser("kc-" + name, name);
        accounts.ensureMembership(tenant, member, "OWNER");
        accounts.shareCopyGroup(group, tenant, ownerUser);
        jdbc.update("update acct.tenant set trading_control = 'DAILY_SWITCH' where id = ?", tenant);
        long real = accounts.createFollowerAccount(tenant, member, name + " Zerodha", BrokerType.ZERODHA, TradeMode.PAPER,
                300_000, EquityMode.FIXED, day, "../config/risk/follower-risk.v7.yaml", 0);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'USER_PRIVATE', ?, 'env:KITE_K', 'env:KITE_S') "
                + "returning id", Long.class, tenant, "app-" + name);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, external_broker_user_id = 'AB1234' "
                + "where id = ?", profile, real);
        if (liveEligible) {
            jdbc.update("update acct.broker_account set trade_mode = 'LIVE', live_trading_enabled = true, "
                    + "connection_status = 'ACTIVE' where id = ?", real);
            // the day's login, before any LIVE switch of the tests (LIVE counts only when switched during the session)
            jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status, "
                    + "created_at) values (?, 'session-file:kite-x.token', ?, now() + interval '1 day', 'ACTIVE', ?)", real,
                    Timestamp.from(ist(day, "08:00:00")), Timestamp.from(ist(day, "08:00:00")));
        }
        new FanOut(dataSource).subscribe(group, real, List.of("ma-cross"), 1.0);
        long twin = accounts.ensurePaperTwin(real);
        accounts.syncPaperTwin(real);
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '30 days' where copy_group_id = ?",
                group);
        jdbc.update("update acct.tenant set default_broker_account_id = ? where id = ?", real, tenant);
        return new Workspace(group, tenant, member, real, twin);
    }

    private static Instant ist(LocalDate day, String time) {
        return LocalDateTime.of(day, java.time.LocalTime.parse(time)).atZone(IST).toInstant();
    }

    private static void event(Workspace w, Long account, String mode, Instant at) {
        jdbc.update("insert into acct.trading_day_event (tenant_id, broker_account_id, mode, reason, changed_by, changed_at) "
                + "values (?, ?, ?, 'IT', ?, ?)", w.tenant(), account, mode, w.member(), Timestamp.from(at));
    }

    private static void decide(Workspace w, LocalDate day, Instant at, SignalDecision.Action action) {
        seq++;
        new SignalDecisionStore(dataSource).insert(new SignalDecision(w.group(), 9300, day, at, "ma-cross", 2, "NIFTY",
                action, "CE", null, "CONFIRMED", action.name(), 77, null, "NIFTY 23000 CE", 65, 100, 100_000, 500_000, 10,
                "TD:" + seq, Map.of("strategy_lots", 1.0, "intended_lots", 1.0)));
    }

    /** account:mode of the workspace's intents for one action, in decision order. */
    private static List<String> intents(Workspace w, String action) {
        return jdbc.queryForList("select case when i.broker_account_id = ? then 'REAL' else 'TWIN' end || ':' "
                + "|| i.execution_mode from exec.trade_intent i join exec.trade_batch b on b.id = i.trade_batch_id "
                + "join exec.signal_decision d on d.id = b.signal_decision_id where i.tenant_id = ? and d.action = ? "
                + "order by d.decided_at, i.broker_account_id", String.class, w.real(), w.tenant(), action);
    }

    @Test
    void entriesFollowTheDaysSwitchAsOfTheirDecisionTimeAndExitsReachBothAccounts() {
        Workspace w = workspace("asha", DAY, true);
        FanOut fanOut = new FanOut(dataSource);
        // no event: the default broker, PAPER -> the twin
        decide(w, DAY, ist(DAY, "09:40:00"), SignalDecision.Action.ENTER);
        // LIVE from 10:00: a decision at 09:59:59 (fanned out late) stays PAPER, one at 10:05 goes to the broker
        event(w, w.real(), "LIVE", ist(DAY, "10:00:00"));
        decide(w, DAY, ist(DAY, "09:59:59"), SignalDecision.Action.ENTER);
        decide(w, DAY, ist(DAY, "10:05:00"), SignalDecision.Action.ENTER);
        // back to PAPER at 10:20; Off at 10:40
        event(w, w.real(), "PAPER", ist(DAY, "10:20:00"));
        decide(w, DAY, ist(DAY, "10:25:00"), SignalDecision.Action.ENTER);
        event(w, null, "PAPER", ist(DAY, "10:40:00"));
        decide(w, DAY, ist(DAY, "10:45:00"), SignalDecision.Action.ENTER);
        decide(w, DAY, ist(DAY, "10:30:00"), SignalDecision.Action.EXIT);
        fanOut.fanOut(w.group(), DAY);

        assertThat(intents(w, "ENTER")).containsExactly("TWIN:PAPER", "TWIN:PAPER", "REAL:LIVE", "TWIN:PAPER");
        assertThat(intents(w, "EXIT")).containsExactlyInAnyOrder("REAL:LIVE", "TWIN:PAPER");

        // a re-run (every tick) adds nothing, even after a later event that would point the 09:40 entry elsewhere
        event(w, w.real(), "LIVE", ist(DAY, "09:30:00"));
        assertThat(fanOut.fanOut(w.group(), DAY)).isZero();
        assertThat(intents(w, "ENTER")).hasSize(4);
    }

    @Test
    void liveFallsBackToTheTwinWithoutASessionOrEligibilityAndADayStartsPaper() {
        Workspace w = workspace("bina", DAY, true);
        TradingDay td = new TradingDay(dataSource);
        event(w, w.real(), "LIVE", ist(DAY, "10:00:00"));
        assertThat(td.entryTarget(w.tenant(), ist(DAY, "10:01:00"))).isEqualTo(w.real());
        jdbc.update("update acct.broker_account set connection_status = 'TOKEN_EXPIRED' where id = ?", w.real());
        assertThat(td.entryTarget(w.tenant(), ist(DAY, "10:01:00"))).isEqualTo(w.twin());
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", w.real());
        // the next day: the broker carries over, the mode does not
        TradingDay.State next = td.state(w.tenant(), ist(DAY.plusDays(1), "09:20:00"));
        assertThat(next.brokerAccountId()).isEqualTo(w.real());
        assertThat(next.mode()).isEqualTo(TradingDay.Mode.PAPER);
        assertThat(td.entryTarget(w.tenant(), ist(DAY.plusDays(1), "09:20:00"))).isEqualTo(w.twin());
        // a broker that is not LIVE-eligible is PAPER whatever the switch says
        Workspace v = workspace("chitra", DAY, false);
        event(v, v.real(), "LIVE", ist(DAY, "10:00:00"));
        assertThat(td.entryTarget(v.tenant(), ist(DAY, "10:01:00"))).isEqualTo(v.twin());
        // Off and nothing chosen: no target
        event(v, null, "PAPER", ist(DAY, "11:00:00"));
        assertThat(td.entryTarget(v.tenant(), ist(DAY, "11:01:00"))).isNull();
        jdbc.update("update acct.tenant set default_broker_account_id = null where id = ?", v.tenant());
        assertThat(td.entryTarget(v.tenant(), ist(DAY, "09:00:00"))).isNull();
    }

    @Test
    void anAccountModeWorkspaceIsUnchanged() {
        Workspace w = workspace("deepa", DAY, true);
        jdbc.update("update acct.tenant set trading_control = 'ACCOUNT_MODE' where id = ?", w.tenant());
        decide(w, DAY, ist(DAY, "09:45:00"), SignalDecision.Action.ENTER);
        new FanOut(dataSource).fanOut(w.group(), DAY);
        assertThat(intents(w, "ENTER")).containsExactlyInAnyOrder("REAL:LIVE", "TWIN:PAPER");
        assertThat(new TradingDay(dataSource).idle(new AccountStore(dataSource).byId(w.real()).orElseThrow())).isFalse();
    }

    @Test
    void anIdleRealAccountGetsNoTurnAndItsExitsAndAddsAreClosed() {
        LocalDate today = TradingDay.today();
        Workspace w = workspace("esha", today, true);
        AccountStore accounts = new AccountStore(dataSource);
        TradingDay td = new TradingDay(dataSource);
        BrokerAccount real = accounts.byId(w.real()).orElseThrow();
        // PAPER today (no event): the real account is idle
        assertThat(td.idle(real)).isTrue();
        assertThat(td.idle(accounts.byId(w.twin()).orElseThrow())).isFalse();         // a twin is never idle
        decide(w, today, Instant.now().minusSeconds(5), SignalDecision.Action.EXIT);
        new FanOut(dataSource).fanOut(w.group(), today);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        QuoteSource none = (d, u, t, at) -> Double.NaN;
        try (ExecutionLoop loop = new ExecutionLoop(dataSource, "ignored", "*", "worker-TD", costs, none, 1e9)) {
            loop.tick(today, 20_000);
            ExecutionLoop.AccountStatus status = loop.status().stream().filter(s -> s.accountId() == w.real()).findFirst()
                    .orElseThrow();
            assertThat(status.state()).isEqualTo("IDLE");
            assertThat(status.lastResult()).contains("1 exit/add closed NOT_HELD");
        }
        assertThat(jdbc.queryForObject("select count(*) from exec.account_lease where broker_account_id = ?", Integer.class,
                w.real())).isZero();
        // the day's LIVE broker (with a session) is not idle; nor is one holding a position
        jdbc.update("insert into acct.trading_day_event (tenant_id, broker_account_id, mode, reason) values (?, ?, 'LIVE', 'IT')",
                w.tenant(), w.real());
        assertThat(td.idle(real)).isFalse();
        td.append(w.tenant(), w.real(), TradingDay.Mode.PAPER, "IT", null);
        assertThat(td.idle(real)).isTrue();
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ? limit 1", Long.class,
                w.real());
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                + "option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, status) values "
                + "(?, ?, ?, 'ma-cross', 'NIFTY', 'CE', 77, 'NIFTY 23000 CE', 65, 100, now(), 'OPEN')", w.tenant(), w.real(),
                intent);
        assertThat(td.idle(real)).isFalse();
    }

    @Test
    void aLostSessionSendsALiveDayBackToPaperOnce() {
        LocalDate today = TradingDay.today();
        Workspace w = workspace("farah", today, true);
        TradingDay td = new TradingDay(dataSource);
        BrokerAccount real = new AccountStore(dataSource).byId(w.real()).orElseThrow();
        td.sessionLost(real, "IT");                                          // PAPER already: nothing
        assertThat(jdbc.queryForObject("select count(*) from acct.trading_day_event where tenant_id = ?", Integer.class,
                w.tenant())).isZero();
        td.append(w.tenant(), w.real(), TradingDay.Mode.LIVE, "IT", w.member());
        td.sessionLost(real, "IT");
        td.sessionLost(real, "IT");                                          // the second loss finds PAPER
        assertThat(jdbc.queryForList("select mode || ':' || reason from acct.trading_day_event where tenant_id = ? "
                + "order by id", String.class, w.tenant())).containsExactly("LIVE:IT", "PAPER:SESSION_LOST");
        assertThat(td.state(w.tenant(), Instant.now()).brokerAccountId()).isEqualTo(w.real());
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'MODE_PAPER_SESSION_LOST'", Integer.class, w.real())).isEqualTo(1);
    }

    @Test
    void theMembersKillSwitchAndLiveLossLimitCoverAllTheirAccounts() {
        LocalDate today = TradingDay.today();
        Workspace w = workspace("gita", today, true);
        AccountStore accounts = new AccountStore(dataSource);
        KillSwitches kills = new KillSwitches(dataSource);
        TradingDay td = new TradingDay(dataSource);
        kills.engage(w.tenant(), "TENANT", null, null, null, "member stop", "kc-gita");
        assertThat(kills.engaged(accounts.byId(w.twin()).orElseThrow(), "ma-cross", "NSE_FO|77")).startsWith("TENANT:");
        assertThat(kills.engaged(accounts.byId(w.real()).orElseThrow(), "ma-cross", "NSE_FO|77")).startsWith("TENANT:");
        assertThat(kills.tenantEngaged(w.tenant())).isTrue();
        // another workspace is not touched
        Workspace other = workspace("hema", today, true);
        assertThat(kills.engaged(accounts.byId(other.twin()).orElseThrow(), "ma-cross", "NSE_FO|77")).isNull();

        // loss limit: LIVE positions only, every broker together
        jdbc.update("update acct.tenant set daily_loss_limit = 10000 where id = ?", other.tenant());
        decide(other, today, Instant.now().minusSeconds(90), SignalDecision.Action.ENTER);
        jdbc.update("insert into acct.trading_day_event (tenant_id, broker_account_id, mode, reason, changed_at) values "
                + "(?, ?, 'LIVE', 'IT', now() - interval '1 minute')", other.tenant(), other.real());
        decide(other, today, Instant.now().minusSeconds(20), SignalDecision.Action.ENTER);
        new FanOut(dataSource).fanOut(other.group(), today);
        for (Object[] row : new Object[][] {{other.twin(), "PAPER", -50_000.0}, {other.real(), "LIVE", -6_000.0}}) {
            long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ? and "
                    + "execution_mode = ? limit 1", Long.class, row[0], row[1]);
            jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, "
                    + "underlying, option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, closed_at, "
                    + "realised_gross, charges, status) values (?, ?, ?, 'ma-cross', 'NIFTY', 'CE', 77, 'NIFTY 23000 CE', 0, "
                    + "100, now(), now(), ?, 100, 'CLOSED')", other.tenant(), row[0], intent, row[2]);
        }
        assertThat(td.liveNet(other.tenant(), today)).isEqualTo(-6_100.0);           // the paper loss does not count
        assertThat(td.dailyLossReached(other.tenant(), today)).isFalse();
        jdbc.update("update acct.tenant set daily_loss_limit = 6000 where id = ?", other.tenant());
        assertThat(td.dailyLossReached(other.tenant(), today)).isTrue();
        assertThat(td.liveExposure(other.tenant())).isEmpty();
    }

    @Test
    void anEntryIsRefusedWhenTheSwitchMovedAfterItsFanOut() {
        LocalDate today = TradingDay.today();
        Workspace w = workspace("jaya", today, true);
        AccountStore accounts = new AccountStore(dataSource);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        FollowerRisk risk = FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk.v7.yaml"));
        decide(w, today, Instant.now().minusSeconds(10), SignalDecision.Action.ENTER);
        decide(w, today, Instant.now().minusSeconds(5), SignalDecision.Action.ENTER);
        new FanOut(dataSource).fanOut(w.group(), today);
        assertThat(intents(w, "ENTER")).containsExactly("TWIN:PAPER", "TWIN:PAPER");
        BrokerAccount twin = accounts.byId(w.twin()).orElseThrow();
        // the member switched LIVE before the twin ran its intents: both are refused, nothing is bought anywhere
        new TradingDay(dataSource).append(w.tenant(), w.real(), TradingDay.Mode.LIVE, "IT", w.member());
        FollowerSimulator.DayResult result = new FollowerSimulator(dataSource, costs, risk, 1e9).run(twin, w.group(), today);
        assertThat(result.reasons()).containsEntry("REJECTED_TARGET_CHANGED", 2);
        assertThat(jdbc.queryForObject("select count(*) from exec.position where tenant_id = ?", Integer.class, w.tenant()))
                .isZero();
        // back to PAPER: the next entry on the twin runs
        new TradingDay(dataSource).append(w.tenant(), w.real(), TradingDay.Mode.PAPER, "IT", w.member());
        decide(w, today, Instant.now().minusSeconds(1), SignalDecision.Action.ENTER);
        new FanOut(dataSource).fanOut(w.group(), today);
        result = new FollowerSimulator(dataSource, costs, risk, 1e9).run(twin, w.group(), today);
        assertThat(result.executed()).isEqualTo(1);
    }

    /** Code review 7 Nov 2026: an add fanned out to an idle LIVE-eligible account keeps it idle and is closed. */
    @Test
    void anAddToAnIdleLiveEligibleAccountDoesNotWakeIt() {
        LocalDate today = TradingDay.today();
        Workspace w = workspace("kavya", today, true);
        TradingDay td = new TradingDay(dataSource);
        BrokerAccount real = new AccountStore(dataSource).byId(w.real()).orElseThrow();
        decide(w, today, Instant.now().minusSeconds(3), SignalDecision.Action.ADD);
        new FanOut(dataSource).fanOut(w.group(), today);
        assertThat(jdbc.queryForObject("select execution_mode from exec.trade_intent where broker_account_id = ?",
                String.class, w.real())).isEqualTo("LIVE");
        assertThat(td.idle(real)).as("a pending add does not count").isTrue();
        assertThat(td.closeIdleIntents(w.real())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state || ':' || reject_code from exec.trade_intent where broker_account_id = ?",
                String.class, w.real())).isEqualTo("SKIPPED:NOT_HELD");
        // a LIVE entry left over from another day does not keep it awake either
        jdbc.update("update exec.trade_intent set state = 'CREATED', action = 'ENTER', created_at = now() - interval "
                + "'2 days' where broker_account_id = ?", w.real());
        assertThat(td.idle(real)).isTrue();
    }

    /** Code review 7 Nov 2026: after a lost session and a new login, the day is PAPER until switched LIVE again. */
    @Test
    void aReloginAfterALostSessionIsPaperUntilSwitchedAgainAndABrokerChangeIsNeverUndone() {
        LocalDate today = TradingDay.today();
        Workspace w = workspace("lalita", today, true);
        TradingDay td = new TradingDay(dataSource);
        BrokerAccount real = new AccountStore(dataSource).byId(w.real()).orElseThrow();
        td.append(w.tenant(), w.real(), TradingDay.Mode.LIVE, "IT", w.member());
        assertThat(td.entryTargetNow(w.tenant())).isEqualTo(w.real());
        // a new session (a re-login) without a new switch: the old LIVE switch does not carry into it
        jdbc.update("update acct.broker_session set status = 'EXPIRED' where broker_account_id = ?", w.real());
        jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status) values "
                + "(?, 'session-file:kite-y.token', now(), now() + interval '1 day', 'ACTIVE')", w.real());
        assertThat(td.entryTargetNow(w.tenant())).isEqualTo(w.twin());
        td.append(w.tenant(), w.real(), TradingDay.Mode.LIVE, "IT", w.member());
        assertThat(td.entryTargetNow(w.tenant())).isEqualTo(w.real());
        // the member changes broker; a session-lost report for the old broker arriving after it changes nothing
        Long other = new AccountStore(dataSource).createFollowerAccount(w.tenant(), w.member(), "lalita Upstox",
                BrokerType.UPSTOX, TradeMode.PAPER, 100_000, EquityMode.FIXED, today, "x", 0);
        new AccountStore(dataSource).ensurePaperTwin(other);
        td.append(w.tenant(), other, TradingDay.Mode.PAPER, "BROKER", w.member());
        td.sessionLost(real, "IT");
        assertThat(td.stateNow(w.tenant()).brokerAccountId()).isEqualTo(other);
        // a disabled member's LIVE day goes back to PAPER
        td.append(w.tenant(), w.real(), TradingDay.Mode.LIVE, "IT", w.member());
        assertThat(td.personDisabled(w.member(), "IT")).containsExactly(w.tenant());
        assertThat(td.stateNow(w.tenant()).mode()).isEqualTo(TradingDay.Mode.PAPER);
        assertThat(td.stateNow(w.tenant()).brokerAccountId()).isEqualTo(w.real());
    }

    @Test
    void aTwinKeepsInStepWithItsRealAccount() {
        Workspace w = workspace("indu", DAY, false);
        AccountStore accounts = new AccountStore(dataSource);
        jdbc.update("update acct.broker_account set allocated_capital = 450000, daily_loss_limit = 9000 where id = ?", w.real());
        new FanOut(dataSource).subscribe(w.group(), w.real(), List.of("early-confirm-runner"), 1.0);
        jdbc.update("update acct.strategy_subscription set enabled = false where broker_account_id = ? and strategy_id = "
                + "'ma-cross'", w.real());
        accounts.syncPaperTwin(w.real());
        BrokerAccount twin = accounts.byId(w.twin()).orElseThrow();
        assertThat(twin.allocatedCapital()).isEqualTo(450_000);
        assertThat(twin.brokerType()).isEqualTo(BrokerType.PAPER);
        assertThat(twin.displayName()).isEqualTo("indu Zerodha paper");
        assertThat(jdbc.queryForList("select strategy_id || ':' || enabled from acct.strategy_subscription where "
                + "broker_account_id = ? order by strategy_id", String.class, w.twin()))
                .containsExactly("early-confirm-runner:true", "ma-cross:false");
        assertThat(accounts.ensurePaperTwin(w.real())).isEqualTo(w.twin());               // once
    }
}
