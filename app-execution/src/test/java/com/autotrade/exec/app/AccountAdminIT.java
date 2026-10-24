package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Account management on the Accounts page: an OWNER creates and edits follower accounts (never LIVE), a TRADER or VIEWER
 * cannot, another tenant's account is not found, every change is audited, and the rupee loss limit is stored.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountAdminIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static AccountAdminController api;
    private static long otherTenantAccount;

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
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner"), other = accounts.ensureTenant("t-other");
        long me = accounts.ensureUser("local-owner", "me");
        accounts.ensureMembership(tenant, me, "OWNER");
        accounts.ensureMembership(tenant, accounts.ensureUser("kc-trader", "trader"), "TRADER");
        accounts.ensureMembership(tenant, accounts.ensureUser("kc-viewer", "viewer"), "VIEWER");
        long stranger = accounts.ensureUser("kc-other", "other");
        accounts.ensureMembership(other, stranger, "OWNER");
        LocalDate day = LocalDate.of(2026, 10, 26);
        long signal = accounts.ensureSignalSourceAccount(tenant, me, "SIGNAL-1", 500_000, day, "config/risk/signal-source.v2.yaml");
        accounts.ensureCopyGroup(tenant, "LIVE-SET", signal);
        jdbc.update("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, api_key_ref, api_secret_ref) "
                + "values (?, 'ZERODHA', 'TENANT_SHARED', 'KITE-1', 'env:KITE_API_KEY', 'env:KITE_API_SECRET')", tenant);
        otherTenantAccount = accounts.ensureFollowerAccount(other, stranger, "OTHER-1",
                com.autotrade.account.BrokerType.PAPER, com.autotrade.account.TradeMode.PAPER, 100_000,
                com.autotrade.account.EquityMode.FIXED, day, "../config/risk/follower-risk.v3.yaml", 100);
        api = new AccountAdminController(new Access(dataSource), dataSource,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ExecutionProperties("owner", "LIVE-SET", 0, null, 0, "it", null));
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private static AccountAdminController.NewAccount zerodha(String name, String clientId, double allocation, Double loss) {
        return new AccountAdminController.NewAccount(name, "ZERODHA", "DRY_RUN", clientId, null, allocation, "FIXED", null, loss,
                List.of("ma-cross", "early-confirm-runner"), true, null);
    }

    @Test
    void anOwnerCreatesAZerodhaAccountWithItsLimitClientIdAppAndStrategies() {
        Map<String, Object> created = api.create("local-owner", zerodha("ZERODHA-2", "ab1234", 200_000, 20_000.0));
        long id = ((Number) created.get("id")).longValue();
        Map<String, Object> d = api.details("local-owner", id);
        assertThat(d).containsEntry("broker", "ZERODHA").containsEntry("mode", "DRY_RUN").containsEntry("enabled", true)
                .containsEntry("allocatedCapital", 200_000.0).containsEntry("equityMode", "FIXED")
                .containsEntry("clientId", "AB1234").containsEntry("credentialProfile", "KITE-1")
                .containsEntry("dailyLossLimit", 20_000.0).containsEntry("connection", "PENDING_CONNECT");
        assertThat(jdbc.queryForList("select strategy_id from acct.strategy_subscription where broker_account_id = ? and enabled "
                + "order by 1", String.class, id)).containsExactly("early-confirm-runner", "ma-cross");
        assertThat(jdbc.queryForObject("select live_trading_enabled from acct.broker_account where id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'ACCOUNT_CREATED'", Integer.class, id)).isEqualTo(1);
        assertThat(api.strategyIds("local-owner")).contains("early-confirm-runner", "ma-cross");
        assertThat(api.profiles("local-owner")).extracting(p -> p.get("name")).containsExactly("KITE-1");
        assertThat(api.positions("local-owner", id, null)).isEmpty();
        assertThat(api.pnl("local-owner", id, 30)).isEmpty();
    }

    @Test
    void creationIsValidatedAndNeverLive() {
        assertThat(status(() -> api.create("local-owner", zerodha("NO-ID", null, 100_000, null)))).isEqualTo(400);
        assertThat(status(() -> api.create("local-owner", zerodha("BAD-ID", "1234AB", 100_000, null)))).isEqualTo(400);
        assertThat(status(() -> api.create("local-owner", zerodha("BIG-LOSS", "AB1235", 100_000, 150_000.0)))).isEqualTo(400);
        assertThat(status(() -> api.create("local-owner", new AccountAdminController.NewAccount("LIVE-1", "ZERODHA", "LIVE",
                "AB1236", null, 100_000.0, "FIXED", null, null, List.of(), true, null)))).isEqualTo(400);
        assertThat(status(() -> api.create("local-owner", new AccountAdminController.NewAccount("UP-1", "UPSTOX", "DRY_RUN",
                null, null, 100_000.0, "FIXED", null, null, List.of(), true, null)))).isEqualTo(400);
        api.create("local-owner", new AccountAdminController.NewAccount("PAPER-F", "PAPER", null, null, null, 100_000.0,
                "COMPOUNDING", null, null, List.of("ma-cross"), true, null));
        assertThat(status(() -> api.create("local-owner", new AccountAdminController.NewAccount("PAPER-F", "PAPER", null, null,
                null, 100_000.0, "FIXED", null, null, List.of(), true, null)))).as("duplicate name").isEqualTo(400);
        assertThat(status(() -> api.create("kc-trader", zerodha("BY-TRADER", "AB1237", 100_000, null)))).isEqualTo(403);
        assertThat(status(() -> api.create("kc-viewer", zerodha("BY-VIEWER", "AB1238", 100_000, null)))).isEqualTo(403);
    }

    @Test
    void settingsChangeTheAccountAreAuditedAndStayOffLive() {
        long id = ((Number) api.create("local-owner", zerodha("ZERODHA-3", "AB2345", 200_000, 20_000.0)).get("id")).longValue();
        Map<String, Object> after = api.settings("local-owner", id, new AccountAdminController.Settings(300_000.0, "COMPOUNDING",
                25_000.0, null, null, false, null, List.of("ma-cross")));
        assertThat(after).containsEntry("allocatedCapital", 300_000.0).containsEntry("equityMode", "COMPOUNDING")
                .containsEntry("dailyLossLimit", 25_000.0).containsEntry("enabled", false);
        assertThat(jdbc.queryForList("select strategy_id from acct.strategy_subscription where broker_account_id = ? and enabled",
                String.class, id)).containsExactly("ma-cross");
        assertThat(api.settings("local-owner", id, new AccountAdminController.Settings(null, null, null, true, null, null, null,
                null))).containsEntry("dailyLossLimit", null);
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'ACCOUNT_SETTINGS'", Integer.class, id)).isEqualTo(2);

        assertThat(status(() -> api.settings("local-owner", id, new AccountAdminController.Settings(null, null, null, null,
                "LIVE", null, null, null)))).isEqualTo(400);
        assertThat(status(() -> api.settings("local-owner", id, new AccountAdminController.Settings(null, null, 400_000.0, null,
                null, null, null, null)))).as("loss over the allocation").isEqualTo(400);
        assertThat(status(() -> api.settings("kc-trader", id, new AccountAdminController.Settings(1.0, null, null, null, null,
                null, null, null)))).isEqualTo(403);
        assertThat(status(() -> api.settings("local-owner", id, new AccountAdminController.Settings(null, null, null, null, null,
                null, null, null)))).as("nothing to change").isEqualTo(400);
    }

    @Test
    void anotherTenantsAccountIsNotFound() {
        assertThat(status(() -> api.details("local-owner", otherTenantAccount))).isEqualTo(404);
        assertThat(status(() -> api.positions("local-owner", otherTenantAccount, null))).isEqualTo(404);
        assertThat(status(() -> api.settings("local-owner", otherTenantAccount, new AccountAdminController.Settings(1.0, null,
                null, null, null, null, null, null)))).isEqualTo(404);
        assertThat(api.details("kc-other", otherTenantAccount)).containsEntry("name", "OTHER-1");
    }
}
