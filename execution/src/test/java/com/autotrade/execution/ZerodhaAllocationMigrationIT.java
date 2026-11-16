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
 * V36 (user 11 Nov 2026): the owner's ZERODHA-1 on follower-risk v10 moves to v11 at size step 100, audited; every other
 * account, a member workspace's own ZERODHA-1 on v10 included, stays as it was, and the account's own settings are kept.
 */
@Testcontainers(disabledWithoutDocker = true)
class ZerodhaAllocationMigrationIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final String V10 = "config/risk/follower-risk.v10.yaml";

    @Test
    void onlyZerodha1OnV10MovesToV11AtFullSize() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TIMESCALE)) {
            postgres.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("35").load().migrate();
                AccountStore accounts = new AccountStore(dataSource);
                long tenant = accounts.ensureTenant("owner");
                long owner = accounts.ensureUser("kc-owner", "owner");
                long z1 = accounts.ensureFollowerAccount(tenant, owner, "ZERODHA-1", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                        100_000, EquityMode.COMPOUNDING, LocalDate.now(), V10, 0);
                long other = accounts.ensureFollowerAccount(tenant, owner, "ZERODHA-2", BrokerType.ZERODHA,
                        TradeMode.DRY_RUN, 200_000, EquityMode.FIXED, LocalDate.now(), V10, 0);
                long tenant2 = accounts.ensureTenant("member");
                long member = accounts.ensureUser("kc-member", "member");
                long memberZ1 = accounts.createFollowerAccount(tenant2, member, "ZERODHA-1", BrokerType.ZERODHA,
                        TradeMode.DRY_RUN, 200_000, EquityMode.FIXED, LocalDate.now(), V10, 0);
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                jdbc.update("update acct.broker_account set daily_loss_limit = 10000, "
                        + "live_strategies = array['ma-cross'] where id = ?", z1);

                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("36").load().migrate();

                String row = "select risk_file || ' ' || size_step_pct::int from acct.broker_account where id = ?";
                assertThat(jdbc.queryForObject(row, String.class, z1)).isEqualTo("config/risk/follower-risk.v11.yaml 100");
                assertThat(jdbc.queryForObject(row, String.class, other)).isEqualTo(V10 + " 0");
                assertThat(jdbc.queryForObject(row, String.class, memberZ1)).as("a member's own ZERODHA-1").isEqualTo(V10 + " 0");
                assertThat(jdbc.queryForObject("select allocated_capital::int || ' ' || equity_mode || ' ' "
                        + "|| daily_loss_limit::int || ' ' || array_to_string(live_strategies, ',') from acct.broker_account "
                        + "where id = ?", String.class, z1)).isEqualTo("100000 COMPOUNDING 10000 ma-cross");
                assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                        + "and action = 'RISK_FILE'", Integer.class, z1)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'RISK_FILE'",
                        Integer.class)).isEqualTo(1);
            }
        }
    }
}
