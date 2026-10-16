package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * L1 end to end on the real schema: a signal source's decisions fan out once to subscribed followers, which size from
 * their own equity, refuse by their own capital, skip what they do not hold, and close at the signal's price.
 */
@Testcontainers(disabledWithoutDocker = true)
class ExecutionPipelineIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static Instant ist(String time) {
        return LocalDateTime.parse("2026-10-15T" + time).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private static SignalDecision decision(long group, int seq, String time, String strategy, int priority,
                                           SignalDecision.Action action, double price, double budget, int lots,
                                           String reason) {
        return decision(group, seq, time, strategy, priority, action, "CE", null, price, budget, lots, reason, Map.of());
    }

    private static SignalDecision decision(long group, int seq, String time, String strategy, int priority,
                                           SignalDecision.Action action, String side, String legGroup, double price,
                                           double budget, int lots, String reason, Map<String, Double> context) {
        return new SignalDecision(group, 9001, DAY, ist(time), strategy, priority, "SENSEX", action, side, legGroup,
                "CONFIRMED", reason, "CE".equals(side) ? 1234 : 1235, "BSE_FO|1234", "SENSEX 73200 " + side, 20, price,
                budget, 500_000, lots, group + ":9001:" + seq, context);
    }

    @Test
    void decisionsFanOutOnceAndEachFollowerTradesByItsOwnCapital() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-1", 500_000, DAY,
                "config/risk/signal-source.v1.yaml");
        long group = accounts.ensureCopyGroup(tenant, "LIVE-SET", signal);
        String risk = "../config/risk/follower-risk.v1.yaml";
        long big = accounts.ensureFollowerAccount(tenant, owner, "PAPER-F", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, risk, 100);
        long small = accounts.ensureFollowerAccount(tenant, owner, "PILOT-F", BrokerType.ZERODHA, TradeMode.DRY_RUN, 50_000,
                EquityMode.FIXED, DAY, risk, 0);                                           // one-lot pilot
        long other = accounts.ensureFollowerAccount(tenant, owner, "MAC-ONLY", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, risk, 100);
        FanOut fanOut = new FanOut(dataSource);
        fanOut.subscribe(group, big, List.of("opening-drive", "expiry-trend-rider"), 1.0);
        fanOut.subscribe(group, small, List.of("opening-drive"), 1.0);
        fanOut.subscribe(group, other, List.of("ma-cross"), 1.0);

        SignalDecisionStore store = new SignalDecisionStore(dataSource);
        // odb enters SENSEX 73200 CE at 183.81 with a Rs 5L budget (124 lots on the virtual account), exits at 488.19;
        // etr enters the same contract at 300 with Rs 6L, then a second ENTER while held (skipped), then exits at 281
        Map<String, Double> book = Map.of("liquidity_lots", 124.0, "strategy_lots", 1.0, "intended_lots", 1.0);
        assertThat(store.insert(decision(group, 1, "09:25:00", "opening-drive", 2, SignalDecision.Action.ENTER, "CE", null,
                183.81, 500_000, 124, "BREAK_PRIOR_ORH", book))).isTrue();
        assertThat(store.insert(decision(group, 1, "09:25:00", "opening-drive", 2, SignalDecision.Action.ENTER, "CE", null,
                183.81, 500_000, 124, "BREAK_PRIOR_ORH", book))).isFalse();               // same key: ignored
        assertThat(store.forSession(9001).getFirst().context()).containsEntry("liquidity_lots", 124.0);
        store.insert(decision(group, 2, "10:00:00", "opening-drive", 2, SignalDecision.Action.EXIT, 488.19, Double.NaN, 124,
                "TIME_10:00"));
        store.insert(decision(group, 3, "11:40:00", "expiry-trend-rider", 3, SignalDecision.Action.ENTER, 300.0, 600_000,
                100, "BREAK"));
        store.insert(decision(group, 4, "11:41:00", "expiry-trend-rider", 3, SignalDecision.Action.ENTER, 301.0, 600_000,
                100, "AGAIN"));
        store.insert(decision(group, 5, "14:06:00", "expiry-trend-rider", 3, SignalDecision.Action.EXIT, 281.0, Double.NaN,
                100, "SWING_LOW_LOST"));

        // big gets 5 intents (odb x2, etr x3), small 2 (odb), ma-cross-only none; a second fan-out adds nothing
        assertThat(fanOut.fanOut(group, DAY)).isEqualTo(7);
        assertThat(fanOut.fanOut(group, DAY)).isZero();

        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        FollowerRisk followerRisk = FollowerRisk.load(Path.of(risk));
        BrokerAccount bigAccount = accounts.followers(group).stream().filter(a -> a.id() == big).findFirst().orElseThrow();
        FollowerSimulator.DayResult paper = new FollowerSimulator(dataSource, costs, followerRisk, 1e9)
                .run(bigAccount, group, DAY);
        // odb: Rs 5L at 183.81 x 20 would be 136 lots, but the signal's book allowed 124 -> 124 lots (the liquidity ceiling);
        // etr: Rs 6L budget scaled to Rs 5L equity = Rs 6L, over the per-trade cap (100 % of 5L) -> refused; the second
        // etr ENTER: also refused; the etr EXIT: nothing held -> skipped
        assertThat(paper.executed()).isEqualTo(2);
        assertThat(paper.rejected()).isEqualTo(2);
        assertThat(paper.skipped()).isEqualTo(1);
        assertThat(paper.reasons()).containsEntry("REJECTED_PER_TRADE", 2).containsEntry("SKIPPED_NOT_HELD", 1);
        Long quantity = jdbc.queryForObject("select quantity_requested from exec.order_execution e join exec.trade_intent i "
                + "on i.id = e.trade_intent_id where i.broker_account_id = ? and e.role = 'ENTRY'", Long.class, big);
        assertThat(quantity).isEqualTo(124 * 20);
        double charges = jdbc.queryForObject("select charges from exec.position where broker_account_id = ?", Double.class,
                big);
        assertThat(charges).isBetween(1_000.0, 3_000.0);                                     // brokerage, STT, exchange, GST
        assertThat(paper.net()).isCloseTo((488.19 - 183.81) * 2480 - charges, within(0.01));

        BrokerAccount smallAccount = accounts.followers(group).stream().filter(a -> a.id() == small).findFirst().orElseThrow();
        FollowerSimulator.DayResult pilot = new FollowerSimulator(dataSource, costs, followerRisk, Double.NaN)
                .run(smallAccount, group, DAY);
        // DRY_RUN without known broker funds refuses the entry; its exit then has nothing to close
        assertThat(pilot.reasons()).containsEntry("REJECTED_FUNDS_UNKNOWN", 1).containsEntry("SKIPPED_NOT_HELD", 1);
        jdbc.update("update exec.trade_intent set state = 'CREATED', reject_code = null where broker_account_id = ?", small);
        FollowerSimulator.DayResult pilotFunded = new FollowerSimulator(dataSource, costs, followerRisk, 200_000)
                .run(smallAccount, group, DAY);
        assertThat(pilotFunded.executed()).isEqualTo(2);                                     // one lot in, one lot out
        assertThat(jdbc.queryForObject("select raw_status from exec.order_execution e join exec.trade_intent i "
                + "on i.id = e.trade_intent_id where i.broker_account_id = ? and e.role = 'ENTRY'", String.class, small))
                .isEqualTo("DRY_RUN_SIMULATED");
        assertThat(jdbc.queryForObject("select quantity_requested from exec.order_execution e join exec.trade_intent i "
                + "on i.id = e.trade_intent_id where i.broker_account_id = ? and e.role = 'ENTRY'", Long.class, small))
                .isEqualTo(20L);
    }

    @Test
    void aStraddleIsSizedAsAPairAndAPlanAddUsesTheEntryScale() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner-2");
        long owner = accounts.ensureUser("kc-owner-2", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-2", 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "SET-2", signal);
        long id = accounts.ensureFollowerAccount(tenant, owner, "F-2", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v2.yaml", 100);
        FanOut fanOut = new FanOut(dataSource);
        fanOut.subscribe(group, id, List.of("expiry-breakout-straddle", "expiry-trend-rider"), 1.0);
        SignalDecisionStore store = new SignalDecisionStore(dataSource);
        java.util.function.IntFunction<String> key = n -> "S2:" + n;
        // ebs straddle Rs 3L: call 150 + put 100 -> Rs 5,000 per lot pair -> 60 lots each leg; exits per leg
        store.insert(new SignalDecision(group, 9002, DAY, ist("12:57:00"), "expiry-breakout-straddle", 0, "SENSEX",
                SignalDecision.Action.ENTER, "CE", "ebs@12:57", "CONFIRMED", "BREAK", 1, null, "SENSEX C", 20, 150, 300_000,
                500_000, 0, key.apply(1), Map.of("call_ask", 150.0, "put_ask", 100.0)));
        store.insert(new SignalDecision(group, 9002, DAY, ist("12:57:00"), "expiry-breakout-straddle", 0, "SENSEX",
                SignalDecision.Action.ENTER, "PE", "ebs@12:57", "CONFIRMED", "BREAK", 2, null, "SENSEX P", 20, 100, 300_000,
                500_000, 0, key.apply(2), Map.of("call_ask", 150.0, "put_ask", 100.0)));
        store.insert(new SignalDecision(group, 9002, DAY, ist("13:30:00"), "expiry-breakout-straddle", 0, "SENSEX",
                SignalDecision.Action.EXIT, "CE", "STRADDLE:CE", null, "TARGET", 1, null, "SENSEX C", 20, 210, Double.NaN,
                500_000, 0, key.apply(3), Map.of()));
        store.insert(new SignalDecision(group, 9002, DAY, ist("13:30:00"), "expiry-breakout-straddle", 0, "SENSEX",
                SignalDecision.Action.EXIT, "PE", "STRADDLE:PE", null, "TARGET", 2, null, "SENSEX P", 20, 90, Double.NaN,
                500_000, 0, key.apply(4), Map.of()));
        // etr: plan of 4 units, first tranche 1 unit at 50 with Rs 2L -> scale 2L / (4 x 20 x 50) = 50 -> 50 lots;
        // an add of 2 units -> 100 lots at the same scale
        store.insert(new SignalDecision(group, 9002, DAY, ist("14:00:00"), "expiry-trend-rider", 3, "SENSEX",
                SignalDecision.Action.ENTER, "CE", null, "CONFIRMED", "BREAK", 3, null, "SENSEX E", 20, 50, 200_000, 500_000,
                50, key.apply(5), Map.of("strategy_lots", 1.0, "intended_lots", 4.0)));
        store.insert(new SignalDecision(group, 9002, DAY, ist("14:05:00"), "expiry-trend-rider", 3, "SENSEX",
                SignalDecision.Action.ADD, "CE", null, "RUNNER", "ADD", 3, null, "SENSEX E", 20, 55, Double.NaN, 500_000,
                100, key.apply(6), Map.of("strategy_lots", 2.0)));
        assertThat(fanOut.fanOut(group, DAY)).isEqualTo(6);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        BrokerAccount account = accounts.followers(group).getFirst();
        FollowerSimulator.DayResult day = new FollowerSimulator(dataSource, costs,
                FollowerRisk.load(Path.of("../config/risk/follower-risk.v2.yaml")), 1e9).run(account, group, DAY);
        assertThat(day.executed()).isEqualTo(6);
        List<Map<String, Object>> legs = jdbc.queryForList("select option_side, quantity_open, average_entry, status "
                + "from exec.position where broker_account_id = ? and strategy_id = 'expiry-breakout-straddle' order by 1", id);
        assertThat(legs).extracting(r -> r.get("status")).containsOnly("CLOSED");
        Long ceQty = jdbc.queryForObject("select quantity_requested from exec.order_execution e join exec.trade_intent i "
                + "on i.id = e.trade_intent_id where i.broker_account_id = ? and i.option_side = 'CE' and e.role = 'ENTRY' "
                + "and i.strategy_id = 'expiry-breakout-straddle'", Long.class, id);
        assertThat(ceQty).isEqualTo(60 * 20);
        assertThat(jdbc.queryForList("select quantity_requested from exec.order_execution e join exec.trade_intent i "
                + "on i.id = e.trade_intent_id where i.broker_account_id = ? and i.strategy_id = 'expiry-trend-rider' "
                + "order by e.id", Long.class, id)).containsExactly(50L * 20, 100L * 20);
    }

    @Test
    void aCapitalRefusedAddIsRetriedWhenAnExitFreesCapital() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner-3");
        long owner = accounts.ensureUser("kc-owner-3", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-3", 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "SET-3", signal);
        long id = accounts.ensureFollowerAccount(tenant, owner, "F-3", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v2.yaml", 100);
        FanOut fanOut = new FanOut(dataSource);
        fanOut.subscribe(group, id, List.of("ma-cross", "expiry-trend-rider"), 1.0);
        SignalDecisionStore store = new SignalDecisionStore(dataSource);
        java.util.function.IntFunction<String> key = n -> "S3:" + n;
        // ma-cross holds Rs 2.4L; etr enters 1 of 4 units with Rs 4L at 100 (scale 4L / (4 x 20 x 100) = 50 -> 50 lots =
        // Rs 1L); its add of 2 units (100 lots at 110 = Rs 2.2L) does not fit (2.4L + 1L + 2.2L > 5L) until ma-cross exits
        store.insert(new SignalDecision(group, 9003, DAY, ist("12:00:00"), "ma-cross", 4, "SENSEX", SignalDecision.Action.ENTER,
                "PE", null, "CONFIRMED", "CROSS", 7, null, "SENSEX M", 20, 120, 240_000, 500_000, 100, key.apply(1),
                Map.of("strategy_lots", 1.0, "intended_lots", 1.0)));
        store.insert(new SignalDecision(group, 9003, DAY, ist("12:20:00"), "expiry-trend-rider", 3, "SENSEX",
                SignalDecision.Action.ENTER, "CE", null, "CONFIRMED", "BREAK", 8, null, "SENSEX E", 20, 100, 400_000, 500_000,
                50, key.apply(2), Map.of("strategy_lots", 1.0, "intended_lots", 4.0)));
        store.insert(new SignalDecision(group, 9003, DAY, ist("12:22:00"), "expiry-trend-rider", 3, "SENSEX",
                SignalDecision.Action.ADD, "CE", null, "RUNNER", "ADD", 8, null, "SENSEX E", 20, 110, Double.NaN, 500_000,
                100, key.apply(3), Map.of("strategy_lots", 2.0)));
        store.insert(new SignalDecision(group, 9003, DAY, ist("12:30:00"), "ma-cross", 4, "SENSEX", SignalDecision.Action.EXIT,
                "PE", null, null, "TARGET", 7, null, "SENSEX M", 20, 150, Double.NaN, 500_000, 0, key.apply(4), Map.of()));
        store.insert(new SignalDecision(group, 9003, DAY, ist("14:00:00"), "expiry-trend-rider", 3, "SENSEX",
                SignalDecision.Action.EXIT, "CE", null, null, "TRAIL", 8, null, "SENSEX E", 20, 200, Double.NaN, 500_000, 0,
                key.apply(5), Map.of()));
        fanOut.fanOut(group, DAY);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        FollowerRisk risk = FollowerRisk.load(Path.of("../config/risk/follower-risk.v2.yaml"));
        BrokerAccount account = accounts.followers(group).getFirst();
        // the etr contract quotes 125 at 12:30 when ma-cross exits
        FollowerSimulator.DayResult day = new FollowerSimulator(dataSource, costs, risk, 1e9,
                (d, u, token, at) -> token == 8 && at.equals(ist("12:30:00")) ? 125.0 : Double.NaN).run(account, group, DAY);
        assertThat(day.reasons()).containsEntry("REJECTED_CAPITAL", 1).containsEntry("EXECUTED_RETRY", 1);
        assertThat(jdbc.queryForList("select quantity_requested || '@' || average_fill_price from exec.order_execution e "
                + "join exec.trade_intent i on i.id = e.trade_intent_id where i.broker_account_id = ? "
                + "and i.strategy_id = 'expiry-trend-rider' and e.role = 'ENTRY' order by e.id", String.class, id))
                .containsExactly("1000@100", "2000@125");                              // the add filled at 12:30's ask
        assertThat(jdbc.queryForObject("select reject_code from exec.trade_intent where broker_account_id = ? "
                + "and action = 'ADD'", String.class, id)).isEqualTo("CAPITAL_RETRIED");
    }
}
