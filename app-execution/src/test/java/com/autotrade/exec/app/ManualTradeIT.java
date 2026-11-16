package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * The manual trade API (user, 9 Nov 2026): follower accounts only, each scoped to its tenant; a viewer reads but never
 * buys; a buy is confirmed with "BUY", a LIVE account's with "BUY LIVE"; EXIT with "EXIT"; a LIVE account whose risk file
 * does not list manual says so before anything is tried; the worker's refusal is a 409 with its reason.
 */
@Testcontainers(disabledWithoutDocker = true)
class ManualTradeIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static ManualTradeController api;
    private static long paper, liveV7, liveV8, bobPaper;

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
        long viewer = accounts.ensureUser("kc-viewer", "viewer");
        accounts.ensureMembership(ta, alice, "OWNER");
        accounts.ensureMembership(tb, bob, "OWNER");
        accounts.ensureMembership(ta, viewer, "VIEWER");
        accounts.ensureSignalSourceAccount(ta, alice, "SIG-A", 500_000, DAY, "config/risk/signal-source.v2.yaml");
        paper = accounts.ensureFollowerAccount(ta, alice, "ALICE-PAPER", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v3.yaml", 100);
        liveV7 = accounts.ensureFollowerAccount(ta, alice, "ALICE-KITE7", BrokerType.ZERODHA, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v7.yaml", 0);
        liveV8 = accounts.ensureFollowerAccount(ta, alice, "ALICE-KITE8", BrokerType.ZERODHA, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v8.yaml", 0);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'kite-app', 'file:kite-key', 'file:kite-secret') "
                + "returning id", Long.class, ta);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = 'LIVE', live_trading_enabled = "
                + "true where id in (?, ?)", profile, liveV7, liveV8);
        bobPaper = accounts.ensureFollowerAccount(tb, bob, "BOB-PAPER", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, "../config/risk/follower-risk.v3.yaml", 100);
        // the worker is not started here: a confirmed buy reaches it and gets its refusal
        ExecutionService service = new ExecutionService(dataSource, new ExecutionProperties(null, "*", 0, null, 0, "it", null));
        api = new ManualTradeController(new Access(dataSource), dataSource, service);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private interface Call {
        void run() throws Exception;
    }

    private static String refusal(Call call) {
        try {
            call.run();
            return "200";
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value() + " " + e.getReason();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void followerAccountsOnlyAndALiveRiskFileWithoutManualSaysSo() {
        List<Map<String, Object>> mine = api.manualAccounts("kc-alice");
        assertThat(mine).extracting(m -> m.get("name")).containsExactly("ALICE-PAPER", "ALICE-KITE7", "ALICE-KITE8");
        assertThat(mine).filteredOn(m -> m.get("name").equals("ALICE-KITE7")).singleElement()
                .satisfies(m -> assertThat((String) m.get("liveBlocked")).contains("does not list manual"));
        assertThat(mine).filteredOn(m -> m.get("name").equals("ALICE-KITE8")).singleElement()
                .satisfies(m -> assertThat(m.get("liveBlocked")).isNull());
        assertThat(mine).allSatisfy(m -> {
            assertThat(m.get("canBuy")).isEqualTo(true);
            assertThat(m.get("lots")).isEqualTo(1);
        });
        assertThat(api.manualAccounts("kc-viewer")).allSatisfy(m -> assertThat(m.get("canBuy")).isEqualTo(false));
        assertThat(api.manualAccounts("kc-bob")).extracting(m -> m.get("name")).containsExactly("BOB-PAPER");
        assertThat(api.today("kc-alice", paper)).satisfies(m -> {
            assertThat(m.get("mode")).isEqualTo("PAPER");
            assertThat((List<?>) m.get("orders")).isEmpty();
            assertThat(m.get("killSwitch")).isNull();
        });
    }

    @Test
    void aBuyNeedsItsConfirmationTheRightRoleAndTheCallersOwnAccount() {
        assertThat(refusal(() -> api.buy("kc-alice", liveV8, new ManualTradeController.Buy("NIFTY", "CE", null, "BUY"))))
                .isEqualTo("403 confirm with \"BUY LIVE\"");
        assertThat(refusal(() -> api.buy("kc-alice", paper, new ManualTradeController.Buy("NIFTY", "CE", null, "yes"))))
                .isEqualTo("403 confirm with \"BUY\"");
        assertThat(refusal(() -> api.buy("kc-viewer", paper, new ManualTradeController.Buy("NIFTY", "CE", null, "BUY"))))
                .startsWith("403 read-only role");
        assertThat(refusal(() -> api.buy("kc-bob", paper, new ManualTradeController.Buy("NIFTY", "CE", null, "BUY"))))
                .startsWith("404");
        assertThat(refusal(() -> api.buy("kc-alice", bobPaper, new ManualTradeController.Buy("NIFTY", "CE", null, "BUY"))))
                .startsWith("404");
        assertThat(refusal(() -> api.buy("kc-alice", liveV8, new ManualTradeController.Buy("NIFTY", "CE", null, "BUY LIVE"))))
                .isEqualTo("409 the worker has not started");
        assertThat(refusal(() -> api.quote("kc-alice", paper, "NIFTY", "CE"))).isEqualTo("409 the worker has not started");
        assertThat(refusal(() -> api.quote("kc-viewer", paper, "NIFTY", "CE"))).startsWith("403 read-only role");
        assertThat(refusal(() -> api.quote("kc-bob", paper, "NIFTY", "CE"))).startsWith("404");
        assertThat(refusal(() -> api.exit("kc-alice", paper, new ManualTradeController.Exit("NIFTY", "SELL"))))
                .isEqualTo("403 confirm with \"EXIT\"");
        assertThat(refusal(() -> api.exit("kc-viewer", paper, new ManualTradeController.Exit("NIFTY", "EXIT"))))
                .startsWith("403");
        assertThat(refusal(() -> api.exit("kc-alice", paper, new ManualTradeController.Exit("NIFTY", "EXIT"))))
                .isEqualTo("409 the worker has not started");
    }
}
