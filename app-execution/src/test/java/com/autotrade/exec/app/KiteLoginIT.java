package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.zerodha.KiteAuth;
import com.autotrade.zerodha.KiteSimulator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The daily Kite login against the local Kite simulator and a fresh schema: the state binds the redirect to one account
 * and one use, the client id must match, the token lands in an owner-only file and never in the database, the session
 * row and the connection follow, and expiry deletes the token.
 */
@Testcontainers(disabledWithoutDocker = true)
class KiteLoginIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    @TempDir
    static Path dir;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static KiteSimulator kite;
    private static JdbcTemplate jdbc;
    private static long account, wrongClientAccount;
    private static Access.User owner, viewer;
    private static KiteLogin login;

    @BeforeAll
    static void start() throws Exception {
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
        long tenant = accounts.ensureTenant("owner");
        long me = accounts.ensureUser("local-owner", "me"), watcher = accounts.ensureUser("kc-viewer", "viewer");
        accounts.ensureMembership(tenant, me, "OWNER");
        accounts.ensureMembership(tenant, watcher, "VIEWER");
        account = accounts.ensureFollowerAccount(tenant, me, "ZERODHA-1", BrokerType.ZERODHA, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, LocalDate.of(2026, 10, 26), "../config/risk/follower-risk.v3.yaml", 100);
        wrongClientAccount = accounts.ensureFollowerAccount(tenant, me, "ZERODHA-2", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                200_000, EquityMode.FIXED, LocalDate.of(2026, 10, 26), "../config/risk/follower-risk.v3.yaml", 100);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'TENANT_SHARED', 'KITE-1', 'file:kite-api-key', "
                + "'file:kite-api-secret') returning id", Long.class, tenant);
        jdbc.update("update acct.broker_account set credential_profile_id = ? where id in (?, ?)", profile, account,
                wrongClientAccount);
        jdbc.update("update acct.broker_account set external_broker_user_id = 'AB1234' where id = ?", wrongClientAccount);
        Path secrets = Files.createDirectories(dir.resolve("secrets"));
        Files.writeString(secrets.resolve("kite-api-key"), "sim-api-key\n");
        Files.writeString(secrets.resolve("kite-api-secret"), "sim-secret\n");
        kite = new KiteSimulator(0, "sim-api-key", "sim-secret", 3_000);
        kite.strictTokens();
        kite.funds(250_000);
        KiteLoginProperties props = new KiteLoginProperties(secrets.toString(), dir.resolve("sessions").toString(),
                "https://example.test", kite.baseUri().toString(), kite.baseUri().toString(), dir.resolve("none.env").toString());
        login = new KiteLogin(dataSource, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), props);
        Access access = new Access(dataSource);
        owner = access.user("local-owner");
        viewer = access.user("kc-viewer");
    }

    @AfterAll
    static void stop() {
        kite.close();
        dataSource.close();
        postgres.stop();
    }

    private static String state(URI loginUrl) {
        String params = URLDecoder.decode(loginUrl.getQuery(), StandardCharsets.UTF_8);
        assertThat(params).contains("api_key=sim-api-key").contains("v=3");
        return params.substring(params.indexOf("state=") + 6);
    }

    @Test
    void aLoginStoresTheTokenInAnOwnerOnlyFileAndActivatesTheAccount() throws Exception {
        String state = state(login.start(owner, account));

        KiteLogin.Result r = login.callback("req-1", "success", state);

        assertThat(r.ok()).as(r.message()).isTrue();
        assertThat(r.message()).contains("SIM001").contains("250,000");
        Path token = dir.resolve("sessions").resolve("kite-" + account + ".token");
        assertThat(Files.readString(token)).contains("\"accessToken\":\"sim-").contains("\"userId\":\"SIM001\"");
        assertThat(Files.getPosixFilePermissions(token)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        Map<String, Object> s = jdbc.queryForMap("select token_ref, status, expires_at > now() live from acct.broker_session "
                + "where broker_account_id = ?", account);
        assertThat(s).containsEntry("token_ref", "session-file:kite-" + account + ".token").containsEntry("status", "ACTIVE")
                .containsEntry("live", true);
        assertThat(jdbc.queryForObject("select count(*) from acct.broker_session where token_ref like '%sim-%'", Long.class))
                .as("the token is never in the database").isZero();
        assertThat(jdbc.queryForMap("select connection_status, external_broker_user_id from acct.broker_account where id = ?",
                account)).containsEntry("connection_status", "ACTIVE").containsEntry("external_broker_user_id", "SIM001");

        assertThat(login.callback("req-2", "success", state).ok()).as("a state is single-use").isFalse();

        // a second login replaces the session: one ACTIVE row
        assertThat(login.callback("req-3", "success", state(login.start(owner, account))).ok()).isTrue();
        assertThat(jdbc.queryForList("select status from acct.broker_session where broker_account_id = ? order by id",
                String.class, account)).containsExactly("EXPIRED", "ACTIVE");

        // expiry: the sweep ends the session, the account waits for a login, the token file is gone
        jdbc.update("update acct.broker_session set expires_at = now() - interval '1 minute' where broker_account_id = ? "
                + "and status = 'ACTIVE'", account);
        login.sweepExpired();
        assertThat(Files.exists(token)).isFalse();
        assertThat(jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class,
                account)).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void theWrongZerodhaClientOrAFailedLoginStoresNothing() {
        KiteLogin.Result wrong = login.callback("req-9", "success", state(login.start(owner, wrongClientAccount)));
        assertThat(wrong.ok()).isFalse();
        assertThat(wrong.message()).contains("SIM001").contains("AB1234");
        assertThat(Files.exists(dir.resolve("sessions").resolve("kite-" + wrongClientAccount + ".token"))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from acct.broker_session where broker_account_id = ?", Long.class,
                wrongClientAccount)).isZero();

        assertThat(login.callback("req-10", "cancelled", state(login.start(owner, account))).ok()).isFalse();
        assertThat(login.callback("req-11", "success", "forged-state").ok()).isFalse();
    }

    @Test
    void onlyAUserWhoMayActStartsALogin() {
        try {
            login.start(viewer, account);
            org.junit.jupiter.api.Assertions.fail("a VIEWER started a login");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(403);
        }
    }

    @Test
    void theSessionEndsAtTheNextSixAmIst() {
        assertThat(KiteLogin.nextFlush(Instant.parse("2026-10-26T03:00:00Z")))        // 08:30 IST
                .isEqualTo(Instant.parse("2026-10-27T00:30:00Z"));                       // 06:00 IST next day
        assertThat(KiteLogin.nextFlush(Instant.parse("2026-10-26T00:00:00Z")))        // 05:30 IST
                .isEqualTo(Instant.parse("2026-10-26T00:30:00Z"));
        assertThat(KiteAuth.checksum("k", "r", "s")).hasSize(64);
    }
}
