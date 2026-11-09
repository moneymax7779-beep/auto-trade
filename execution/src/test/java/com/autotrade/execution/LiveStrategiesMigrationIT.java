package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * V32 on a database that already has accounts (as GCP does): every follower-risk v7 account moves to v9 with no owner
 * choice, so what trades LIVE stays v7's list; other risk files are left alone.
 */
@Testcontainers(disabledWithoutDocker = true)
class LiveStrategiesMigrationIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    @Test
    void v7AccountsMoveToV9AndStillTradeV7sList() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TIMESCALE)) {
            postgres.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("31").load().migrate();
                AccountStore accounts = new AccountStore(dataSource);
                long tenant = accounts.ensureTenant("owner");
                long owner = accounts.ensureUser("kc-owner", "owner");
                long v7 = accounts.ensureFollowerAccount(tenant, owner, "KITE-V7", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                        200_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v7.yaml", 0);
                long v3 = accounts.ensureFollowerAccount(tenant, owner, "PAPER-V3", BrokerType.PAPER, TradeMode.PAPER,
                        200_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v3.yaml", 100);
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                assertThat(jdbc.queryForObject("select risk_file from acct.broker_account where id = ?", String.class, v7))
                        .isEqualTo("config/risk/follower-risk.v9.yaml");
                assertThat(jdbc.queryForObject("select risk_file from acct.broker_account where id = ?", String.class, v3))
                        .isEqualTo("config/risk/follower-risk.v3.yaml");
                LiveStrategies live = new LiveStrategies(dataSource);
                assertThat(live.chosen(v7)).isNull();
                assertThat(live.effective(v7, FollowerRisk.load(java.nio.file.Path.of("../config/risk/follower-risk.v9.yaml"))))
                        .containsExactly("early-confirm-runner", "ma-cross", "oi-burst-cross");
            }
        }
    }
}
