package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** L1 workers on the real schema: incremental ticks, one worker per account (lease), takeover with a new epoch. */
@Testcontainers(disabledWithoutDocker = true)
class ExecutionLoopIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

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

    private static Instant ist(String time) {
        return LocalDateTime.parse("2026-10-16T" + time).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private static SignalDecision decision(long group, int seq, String time, SignalDecision.Action action, double price) {
        return new SignalDecision(group, 9100, DAY, ist(time), "opening-drive", 2, "NIFTY", action, "CE", null,
                "CONFIRMED", action.name(), 77, null, "NIFTY 23000 CE", 65, price, 100_000, 500_000, 10, "W:" + seq,
                Map.of("strategy_lots", 1.0, "intended_lots", 1.0));
    }

    @Test
    void oneWorkerPerAccountIncrementalTicksAndATakeoverWithTheNextEpoch() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-1", 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "LIVE-SET", signal);
        long follower = accounts.ensureFollowerAccount(tenant, owner, "DRY-W", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, DAY, "../config/risk/follower-risk.v2.yaml", 100);
        new FanOut(dataSource).subscribe(group, follower, List.of("opening-drive"), 1.0);
        SignalDecisionStore store = new SignalDecisionStore(dataSource);
        CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                "india-index-options-costs.v2.yaml")));
        QuoteSource none = (d, u, t, at) -> Double.NaN;

        try (ExecutionLoop a = new ExecutionLoop(dataSource, "owner", "LIVE-SET", "worker-A", costs, none, 1e9);
             ExecutionLoop b = new ExecutionLoop(dataSource, "owner", "LIVE-SET", "worker-B", costs, none, 1e9)) {
            store.insert(decision(group, 1, "09:16:00", SignalDecision.Action.ENTER, 150));
            assertThat(a.tick(DAY, 10_000)).isEqualTo(1);
            assertThat(a.status()).singleElement().satisfies(s -> {
                assertThat(s.state()).isEqualTo("RUNNING");
                assertThat(s.leaseEpoch()).isEqualTo(1L);
                assertThat(s.lastResult()).contains("executed 1");
            });
            b.tick(DAY, 10_000);                                                   // A holds the account
            assertThat(b.status()).singleElement().satisfies(s -> assertThat(s.state()).isEqualTo("LEASE_HELD_ELSEWHERE"));
            a.tick(DAY, 10_000);                                                   // nothing new: nothing run again
            assertThat(a.status().getFirst().lastResult()).startsWith("nothing new");

            a.close();                                                             // A stops; B takes over
            store.insert(decision(group, 2, "10:00:00", SignalDecision.Action.EXIT, 180));
            b.tick(DAY, 10_000);
            assertThat(b.status()).singleElement().satisfies(s -> {
                assertThat(s.state()).isEqualTo("RUNNING");
                assertThat(s.leaseEpoch()).isEqualTo(2L);
                assertThat(s.lastResult()).contains("executed 1");
            });
        }
        assertThat(jdbc.queryForList("select role || ':' || lease_epoch from exec.order_execution where broker_account_id = ? "
                + "order by id", String.class, follower)).containsExactly("ENTRY:1", "EXIT:2");
        assertThat(jdbc.queryForObject("select status from exec.position where broker_account_id = ?", String.class,
                follower)).isEqualTo("CLOSED");
    }
}
