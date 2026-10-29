package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
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
 * The multi-user API's isolation (live plan section 9, L6 gate: "one user's login never reaches another"): a user sees
 * only their tenants' accounts, cannot act on another tenant's account (404, its existence not revealed), a VIEWER
 * cannot act, only an ADMIN engages GLOBAL_LIVE, flatten needs its confirmation word.
 */
@Testcontainers(disabledWithoutDocker = true)
class ExecutionApiIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static AccountsController api;
    private static long aliceAccount, bobAccount;

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
        long ta = accounts.ensureTenant("t-alice"), tb = accounts.ensureTenant("t-bob");
        long alice = accounts.ensureUser("kc-alice", "alice"), bob = accounts.ensureUser("kc-bob", "bob");
        long viewer = accounts.ensureUser("kc-viewer", "viewer"), admin = accounts.ensureUser("kc-admin", "admin");
        accounts.ensureMembership(ta, alice, "OWNER");
        accounts.ensureMembership(tb, bob, "OWNER");
        accounts.ensureMembership(ta, viewer, "VIEWER");
        accounts.ensureMembership(ta, admin, "ADMIN");
        aliceAccount = accounts.ensureFollowerAccount(ta, alice, "ALICE-1", BrokerType.ZERODHA, TradeMode.DRY_RUN, 500_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v3.yaml", 100);
        bobAccount = accounts.ensureFollowerAccount(tb, bob, "BOB-1", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v3.yaml", 100);
        ExecutionService service = new ExecutionService(dataSource, new ExecutionProperties(null, "*", 0, null, 0, "it", null));
        api = new AccountsController(new Access(dataSource), dataSource, service);
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

    @Test
    void eachUserSeesAndActsOnlyOnTheirOwnTenant() {
        assertThat(api.accounts("kc-alice")).extracting(AccountsController.AccountView::name).containsExactly("ALICE-1");
        assertThat(api.accounts("kc-bob")).extracting(AccountsController.AccountView::name).containsExactly("BOB-1");
        assertThat(status(() -> api.accounts(null))).isEqualTo(401);
        assertThat(status(() -> api.accounts("kc-stranger"))).isEqualTo(403);

        // another tenant's account: not found, whatever the call
        assertThat(status(() -> api.engage("kc-bob", new AccountsController.Engage("ACCOUNT", aliceAccount, null, null,
                "x", null, null)))).isEqualTo(404);
        assertThat(status(() -> api.audit("kc-bob", aliceAccount))).isEqualTo(404);
        assertThat(status(() -> {
            try {
                api.flatten("kc-bob", aliceAccount, new AccountsController.Flatten("FLATTEN", "x"));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).isEqualTo(404);
        // a viewer reads, never acts; only an admin engages GLOBAL_LIVE
        assertThat(api.accounts("kc-viewer")).hasSize(1);
        assertThat(status(() -> api.engage("kc-viewer", new AccountsController.Engage("ACCOUNT", aliceAccount, null, null,
                "x", null, null)))).isEqualTo(403);
        assertThat(status(() -> api.engage("kc-alice", new AccountsController.Engage("GLOBAL_LIVE", null, null, null,
                "x", null, null)))).isEqualTo(403);
        Map<String, Object> global = api.engage("kc-admin", new AccountsController.Engage("GLOBAL_LIVE", null, null, null,
                "drill", null, null));
        assertThat(api.switches("kc-bob")).extracting(s -> s.scope()).contains("GLOBAL_LIVE");
        assertThat(status(() -> api.release("kc-bob", (Long) global.get("id")))).isEqualTo(403);
        assertThat(api.release("kc-admin", (Long) global.get("id"))).containsEntry("released", true);
    }

    @Test
    void anOwnersKillSwitchIsTheirsAloneAndFlattenNeedsItsConfirmation() throws Exception {
        long id = (Long) api.engage("kc-alice", new AccountsController.Engage("ACCOUNT", aliceAccount, null, null, "drill", null, null))
                .get("id");
        assertThat(api.switches("kc-alice")).extracting(s -> s.id()).contains(id);
        assertThat(api.switches("kc-bob")).extracting(s -> s.id()).doesNotContain(id);
        assertThat(status(() -> api.release("kc-bob", id))).isEqualTo(403);
        assertThat(api.release("kc-alice", id)).containsEntry("released", true);
        assertThat(status(() -> {
            try {
                api.flatten("kc-alice", aliceAccount, new AccountsController.Flatten("yes", "x"));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).isEqualTo(403);
        assertThat(api.flatten("kc-alice", aliceAccount, new AccountsController.Flatten("FLATTEN", "drill")))
                .containsEntry("result", List.of("the worker has not started"));
        assertThat(api.audit("kc-alice", null)).extracting(r -> r.get("action")).contains("KILL_ACCOUNT", "KILL_RELEASED");
        assertThat(api.audit("kc-bob", null)).extracting(r -> r.get("action")).doesNotContain("KILL_ACCOUNT");
    }
}
