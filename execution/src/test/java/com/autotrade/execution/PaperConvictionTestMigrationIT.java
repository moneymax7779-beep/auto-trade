package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

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
 * V37 (A-378, user 12 Nov 2026): two PAPER followers of the owner's LIVE-SET group, PAPER-40 on v11 and PAPER-40-RVOL on
 * v12, each Rs 1 L FIXED at 40 % with a Rs 20 K loss limit, subscribed to etr / mac / obc / wbr, audited. A member tenant
 * with its own ZERODHA-1 and LIVE-SET gets nothing, and ZERODHA-1 itself is untouched.
 */
@Testcontainers(disabledWithoutDocker = true)
class PaperConvictionTestMigrationIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final String V11 = "config/risk/follower-risk.v11.yaml";

    @Test
    void createsTheTwoPaperArmsForTheOwnerOnly() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TIMESCALE)) {
            postgres.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("36").load().migrate();
                AccountStore accounts = new AccountStore(dataSource);
                long tenant = accounts.ensureTenant("owner");
                long owner = accounts.ensureUser("kc-owner", "owner");
                long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-1", 500_000, LocalDate.now(),
                        "config/risk/signal-source.v2.yaml");
                long group = accounts.ensureCopyGroup(tenant, "LIVE-SET", signal);
                long z1 = accounts.ensureFollowerAccount(tenant, owner, "ZERODHA-1", BrokerType.ZERODHA,
                        TradeMode.DRY_RUN, 100_000, EquityMode.COMPOUNDING, LocalDate.now(), V11, 40);
                long tenant2 = accounts.ensureTenant("member");
                long member = accounts.ensureUser("kc-member", "member");
                long signal2 = accounts.createFollowerAccount(tenant2, member, "SIG-M", BrokerType.PAPER, TradeMode.PAPER,
                        500_000, EquityMode.FIXED, LocalDate.now(), V11, 100);
                accounts.ensureCopyGroup(tenant2, "LIVE-SET", signal2);
                accounts.createFollowerAccount(tenant2, member, "ZERODHA-1", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                        200_000, EquityMode.FIXED, LocalDate.now(), V11, 100);
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                String z1Before = jdbc.queryForObject("select row_to_json(a)::text from acct.broker_account a where id = ?",
                        String.class, z1);

                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("37").load().migrate();

                List<String> arms = jdbc.queryForList("select display_name || ' ' || tenant_id || ' ' || broker_type || ' ' "
                        + "|| trade_mode || ' ' || account_role || ' ' || allocated_capital::int || ' ' || equity_mode || ' ' "
                        + "|| equity_from || ' ' || risk_file || ' ' || size_step_pct::int || ' ' || daily_loss_limit::int "
                        + "|| ' ' || enabled from acct.broker_account where display_name like 'PAPER-40%' order by 1",
                        String.class);
                assertThat(arms).containsExactly(
                        "PAPER-40 " + tenant + " PAPER PAPER FOLLOWER_TRADE 100000 FIXED 2026-11-13 " + V11 + " 40 20000 true",
                        "PAPER-40-RVOL " + tenant + " PAPER PAPER FOLLOWER_TRADE 100000 FIXED 2026-11-13 "
                                + "config/risk/follower-risk.v12.yaml 40 20000 true");
                for (String name : List.of("PAPER-40", "PAPER-40-RVOL")) {
                    assertThat(jdbc.queryForList("select s.strategy_id from acct.strategy_subscription s join "
                            + "acct.broker_account a on a.id = s.broker_account_id where a.display_name = ? and s.enabled "
                            + "and s.copy_group_id = ? order by 1", String.class, name, group))
                            .as(name).containsExactly("expiry-trend-rider", "ma-cross", "oi-burst-cross", "wall-break-rider");
                }
                assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'ACCOUNT_CREATED' "
                        + "and actor = 'migration V37' and tenant_id = ?", Integer.class, tenant)).isEqualTo(2);
                assertThat(jdbc.queryForObject("select count(*) from acct.broker_account where tenant_id = ?",
                        Integer.class, tenant2)).as("the member tenant gets nothing").isEqualTo(2);
                assertThat(jdbc.queryForObject("select row_to_json(a)::text from acct.broker_account a where id = ?",
                        String.class, z1)).as("ZERODHA-1 untouched").isEqualTo(z1Before);
            }
        }
    }
}
