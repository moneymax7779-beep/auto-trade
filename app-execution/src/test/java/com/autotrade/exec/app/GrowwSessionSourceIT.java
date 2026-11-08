package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.BrokerAdapter;
import com.autotrade.execution.BrokerSessions;
import com.autotrade.execution.LiveBrokers;
import com.autotrade.groww.GrowwConfig;
import com.autotrade.upstox.orders.UpstoxConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The worker's Groww session (live adapters plan, step B3/C): the token file the member's Groww approval login wrote
 * ({@code groww-<id>.token}: ucc, accessToken, accountId, expiresAt) and the API key from the account's GROWW broker app
 * become one {@link BrokerSessions.Access}; only this account's file, of the Groww client (UCC) pinned on it; the
 * worker's sessions dispatch by broker (Kite's, Kotak's and Upstox's unchanged); the worker's adapters are Zerodha,
 * Kotak, Upstox and Groww, and Groww has no LIVE connection.
 */
@Testcontainers(disabledWithoutDocker = true)
class GrowwSessionSourceIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    @TempDir
    static Path dir;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static BrokerSecrets secrets;
    private static BrokerAccount groww, unlinked, otherClient, kotak;

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
        secrets = new BrokerSecrets(dir.resolve("secrets"), dir.resolve("sessions"), dir.resolve("store"));
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long me = accounts.ensureUser("uc-owner", "me");
        accounts.ensureMembership(tenant, me, "OWNER");
        LocalDate day = LocalDate.of(2026, 11, 9);
        String risk = "../config/risk/follower-risk.v6.yaml";
        long u = accounts.ensureFollowerAccount(tenant, me, "GROWW-S1", BrokerType.GROWW, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        long n = accounts.ensureFollowerAccount(tenant, me, "GROWW-S2", BrokerType.GROWW, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        long o = accounts.ensureFollowerAccount(tenant, me, "GROWW-S3", BrokerType.GROWW, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        long k = accounts.ensureFollowerAccount(tenant, me, "KOTAK-S9", BrokerType.KOTAK_NEO, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        String keyRef = BrokerSecrets.storeRef(tenant, 1, "key");
        String secretRef = BrokerSecrets.storeRef(tenant, 1, "secret");
        secrets.write(keyRef, "member-groww-api-key");
        secrets.write(secretRef, "member-groww-secret");
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'GROWW', 'Member app', ?, ?) returning id", Long.class, tenant,
                keyRef, secretRef);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, external_broker_user_id = 'GRW1234' "
                + "where id = ?", profile, u);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, external_broker_user_id = 'ZZ0001' "
                + "where id = ?", profile, o);
        groww = accounts.byId(u).orElseThrow();
        unlinked = accounts.byId(n).orElseThrow();
        otherClient = accounts.byId(o).orElseThrow();
        kotak = accounts.byId(k).orElseThrow();
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    /** What {@link GrowwLogin} writes through {@link SessionActivation#activate}. */
    private static void tokenFile(long fileOf, long accountId, String user, Instant expires) {
        secrets.writeToken("groww", fileOf, "{\"ucc\":\"" + user + "\",\"accessToken\":\"member-access-token\","
                + "\"accountId\":" + accountId + ",\"expiresAt\":\"" + expires + "\"}\n");
    }

    @Test
    void theDaysGrowwSessionIsTheMembersOwnTokenFileAndTheAppsKey() {
        GrowwSessionSource source = new GrowwSessionSource(dataSource, secrets);
        secrets.deleteToken(groww.id());                     // the other test may have run first (JUnit's order)
        assertThat(source.access(groww)).as("no login today").isEmpty();
        tokenFile(groww.id(), groww.id(), "grw1234", Instant.now().plusSeconds(3600));
        assertThat(source.access(groww)).hasValueSatisfying(a -> {
            assertThat(a.apiKey()).as("Groww's calls need no API key: none is carried").isNull();
            assertThat(a.toString()).doesNotContain("member-groww-api-key");
            assertThat(a.accessToken()).isEqualTo("member-access-token");
            assertThat(a.extra()).isEmpty();
            assertThat(a.toString()).doesNotContain("member-access-token");
        });
        tokenFile(unlinked.id(), unlinked.id(), "grw1234", Instant.now().plusSeconds(3600));
        assertThat(source.access(unlinked)).as("no broker app linked: no session").isEmpty();
        tokenFile(otherClient.id(), otherClient.id(), "grw1234", Instant.now().plusSeconds(3600));
        assertThat(source.access(otherClient)).as("the file's Groww client is not the one pinned on the account")
                .isEmpty();
        tokenFile(groww.id(), otherClient.id(), "grw1234", Instant.now().plusSeconds(3600));
        assertThat(source.access(groww)).as("a file naming another account is never used").isEmpty();
        assertThat(source.access(kotak)).as("another broker's account").isEmpty();
        secrets.writeToken("kite", groww.id(), "{\"accessToken\":\"kite-token\"}");
        tokenFile(groww.id(), groww.id(), "grw1234", Instant.now().minusSeconds(1));
        assertThat(source.access(groww)).as("expired, and another broker's file is not read").isEmpty();
        secrets.deleteToken(groww.id());
        assertThat(source.access(groww)).isEmpty();
    }

    @Test
    void theWorkersSessionsDispatchByBrokerAndGrowwHasNoLiveConnection() {
        tokenFile(groww.id(), groww.id(), "grw1234", Instant.now().plusSeconds(3600));
        BrokerSessions kite = a -> Optional.of(new BrokerSessions.Access("kite-key", "kite-token"));
        BrokerSessions neo = a -> Optional.of(new BrokerSessions.Access("neo-key", "neo-token"));
        BrokerSessionRouter router = new BrokerSessionRouter(Map.of(BrokerType.ZERODHA, kite, BrokerType.KOTAK_NEO, neo,
                BrokerType.GROWW, new GrowwSessionSource(dataSource, secrets)));
        assertThat(router.access(groww)).hasValueSatisfying(a -> assertThat(a.accessToken())
                .isEqualTo("member-access-token"));
        assertThat(router.access(kotak)).hasValueSatisfying(a -> assertThat(a.apiKey()).isEqualTo("neo-key"));

        java.net.URI local = java.net.URI.create("http://127.0.0.1:1");
        List<BrokerAdapter> adapters = ExecutionService.adapters(dataSource, 1e9, null, null, null,
                new GrowwSessionSource(dataSource, secrets), local, UpstoxConfig.at(local), GrowwConfig.at(local),
                com.autotrade.execution.ReadLiveProbe.Settings.OFF);
        assertThat(adapters).extracting(BrokerAdapter::broker).containsExactly(BrokerType.ZERODHA, BrokerType.KOTAK_NEO,
                BrokerType.UPSTOX, BrokerType.GROWW);
        assertThat(adapters.get(3).live(groww, null)).isNull();
        assertThat(LiveBrokers.LIVE).containsExactly(BrokerType.ZERODHA);
    }
}
