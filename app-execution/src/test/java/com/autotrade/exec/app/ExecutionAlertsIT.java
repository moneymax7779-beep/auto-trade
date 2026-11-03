package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
import com.autotrade.execution.KillSwitches;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Kill switches and other incidents of broker-session accounts reach Telegram (code review 3 Nov 2026). */
@Testcontainers(disabledWithoutDocker = true)
class ExecutionAlertsIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static BrokerAccount dryRun, paper;

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
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long me = accounts.ensureUser("local-owner", "me");
        accounts.ensureMembership(tenant, me, "OWNER");
        dryRun = accounts.byId(accounts.ensureFollowerAccount(tenant, me, "ZERODHA-1", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                200_000, EquityMode.FIXED, LocalDate.of(2026, 10, 26), "../config/risk/follower-risk.v7.yaml", 0)).orElseThrow();
        paper = accounts.byId(accounts.ensureFollowerAccount(tenant, me, "PAPER-F", BrokerType.PAPER, TradeMode.PAPER,
                500_000, EquityMode.FIXED, LocalDate.of(2026, 10, 26), "../config/risk/follower-risk.v3.yaml", 100)).orElseThrow();
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @Test
    void anAccountKillIsSentOnceAndOnlyForBrokerSessionAccounts() {
        KillSwitches kills = new KillSwitches(dataSource);
        kills.engageAccount(dryRun, "before the worker started", "execution");     // history: never sent
        ExecutionAlerts alerts = new ExecutionAlerts(dataSource, null);
        assertThat(alerts.collect()).isEmpty();

        kills.engageAccount(dryRun, "EXIT_NOT_FILLED: 20 of SENSEX 76400 PE still held", "execution");
        kills.engageAccount(dryRun, "EXIT_NOT_FILLED: 20 of SENSEX 76400 PE still held", "execution");
        kills.engageAccount(paper, "a paper account's switch", "execution");
        List<String> sent = alerts.collect();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).startsWith("ZERODHA-1 (DRY_RUN): KILL_ACCOUNT_ALREADY_ENGAGED")
                .contains("EXIT_NOT_FILLED").contains("exits continue");
        assertThat(alerts.collect()).isEmpty();

        new JdbcTemplate(dataSource).update("update exec.kill_switch set released_at = now() where broker_account_id = ?",
                dryRun.id());
        kills.engageAccount(dryRun, "RECONCILIATION: BSE_FO|1134459: ours 20, broker 0", "reconciler");
        assertThat(alerts.collect()).singleElement().asString().startsWith("ZERODHA-1 (DRY_RUN): KILL_ACCOUNT ")
                .contains("ours 20, broker 0");
    }
}
