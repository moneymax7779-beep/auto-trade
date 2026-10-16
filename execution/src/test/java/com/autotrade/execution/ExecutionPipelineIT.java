package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
        return new SignalDecision(group, 9001, DAY, ist(time), strategy, priority, "SENSEX", action, "CE", null, "CONFIRMED",
                reason, 1234, "BSE_FO|1234", "SENSEX 73200 CE", 20, price, budget, 500_000, lots, group + ":9001:" + seq);
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
        assertThat(store.insert(decision(group, 1, "09:25:00", "opening-drive", 2, SignalDecision.Action.ENTER, 183.81,
                500_000, 124, "BREAK_PRIOR_ORH"))).isTrue();
        assertThat(store.insert(decision(group, 1, "09:25:00", "opening-drive", 2, SignalDecision.Action.ENTER, 183.81,
                500_000, 124, "BREAK_PRIOR_ORH"))).isFalse();                              // same key: ignored
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
}
