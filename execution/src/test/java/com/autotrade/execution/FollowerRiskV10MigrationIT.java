package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
 * A-372 / V34 on a database that already has accounts (as GCP does): every follower-risk v9 account moves to v10 (three
 * open positions), an owner's LIVE strategy choice on it is kept, and accounts on other risk files are left alone.
 */
@Testcontainers(disabledWithoutDocker = true)
class FollowerRiskV10MigrationIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    @Test
    void v9AccountsMoveToV10KeepingTheirLiveChoice() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TIMESCALE)) {
            postgres.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("33").load().migrate();
                AccountStore accounts = new AccountStore(dataSource);
                long tenant = accounts.ensureTenant("owner");
                long owner = accounts.ensureUser("kc-owner", "owner");
                long chosen = accounts.ensureFollowerAccount(tenant, owner, "KITE-V9-CHOSEN", BrokerType.ZERODHA,
                        TradeMode.DRY_RUN, 200_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v9.yaml", 0);
                long plain = accounts.ensureFollowerAccount(tenant, owner, "KITE-V9", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                        200_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v9.yaml", 0);
                long v3 = accounts.ensureFollowerAccount(tenant, owner, "PAPER-V3", BrokerType.PAPER, TradeMode.PAPER,
                        200_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v3.yaml", 100);
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                jdbc.update("update acct.broker_account set live_strategies = array['ma-cross', 'wall-break-rider'] where id = ?",
                        chosen);

                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("34").load().migrate();

                String file = "select risk_file from acct.broker_account where id = ?";
                assertThat(jdbc.queryForObject(file, String.class, chosen)).isEqualTo("config/risk/follower-risk.v10.yaml");
                assertThat(jdbc.queryForObject(file, String.class, plain)).isEqualTo("config/risk/follower-risk.v10.yaml");
                assertThat(jdbc.queryForObject(file, String.class, v3)).isEqualTo("config/risk/follower-risk.v3.yaml");

                FollowerRisk v10 = FollowerRisk.load(Path.of("../config/risk/follower-risk.v10.yaml"));
                // the reads need the newest tables (V35's OFF list); the risk files were checked above at this test's own target
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
                LiveStrategies live = new LiveStrategies(dataSource);
                assertThat(live.effective(chosen, v10)).containsExactly("ma-cross", "wall-break-rider");
                assertThat(live.effective(plain, v10)).containsExactly("early-confirm-runner", "ma-cross", "oi-burst-cross");
                assertThat(v10.maxOpenPositions()).isEqualTo(3);
                assertThat(v10.live().maxOpenPositions()).isEqualTo(3);
            }
        }
    }
}
