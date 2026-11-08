package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.autotrade.upstox.orders.UpstoxConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The worker's Upstox session (live adapters plan, step B2/C): the token file the member's own Upstox login wrote
 * ({@code upstox-<id>.token}: userId, accessToken, accountId, expiresAt) and the API key from the account's UPSTOX
 * broker app become one {@link BrokerSessions.Access}; only this account's file, of the Upstox client pinned on it, and
 * never zt-tiger-v2's token (its reader is not even on the worker's class path); the worker's sessions dispatch by
 * broker (Kite's and Kotak's unchanged); the worker's adapters are Zerodha, Kotak and Upstox, and Upstox has no LIVE
 * connection.
 */
@Testcontainers(disabledWithoutDocker = true)
class UpstoxSessionSourceIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    @TempDir
    static Path dir;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static BrokerSecrets secrets;
    private static BrokerAccount upstox, unlinked, otherClient, kotak;

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
        long u = accounts.ensureFollowerAccount(tenant, me, "UPSTOX-S1", BrokerType.UPSTOX, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        long n = accounts.ensureFollowerAccount(tenant, me, "UPSTOX-S2", BrokerType.UPSTOX, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        long o = accounts.ensureFollowerAccount(tenant, me, "UPSTOX-S3", BrokerType.UPSTOX, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        long k = accounts.ensureFollowerAccount(tenant, me, "KOTAK-S9", BrokerType.KOTAK_NEO, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, risk, 100);
        String keyRef = BrokerSecrets.storeRef(tenant, 1, "key");
        String secretRef = BrokerSecrets.storeRef(tenant, 1, "secret");
        secrets.write(keyRef, "member-upstox-api-key");
        secrets.write(secretRef, "member-upstox-secret");
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'UPSTOX', 'Member app', ?, ?) returning id", Long.class, tenant,
                keyRef, secretRef);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, external_broker_user_id = 'BM6462' "
                + "where id = ?", profile, u);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, external_broker_user_id = 'ZZ0001' "
                + "where id = ?", profile, o);
        upstox = accounts.byId(u).orElseThrow();
        unlinked = accounts.byId(n).orElseThrow();
        otherClient = accounts.byId(o).orElseThrow();
        kotak = accounts.byId(k).orElseThrow();
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    /** What {@link UpstoxLogin} writes through {@link SessionActivation#activate}. */
    private static void tokenFile(long fileOf, long accountId, String user, Instant expires) {
        secrets.writeToken("upstox", fileOf, "{\"userId\":\"" + user + "\",\"accessToken\":\"member-access-token\","
                + "\"accountId\":" + accountId + ",\"expiresAt\":\"" + expires + "\"}\n");
    }

    @Test
    void theDaysUpstoxSessionIsTheMembersOwnTokenFileAndTheAppsKey() {
        UpstoxSessionSource source = new UpstoxSessionSource(dataSource, secrets);
        assertThat(source.access(upstox)).as("no login today").isEmpty();
        tokenFile(upstox.id(), upstox.id(), "BM6462", Instant.now().plusSeconds(3600));
        assertThat(source.access(upstox)).hasValueSatisfying(a -> {
            assertThat(a.apiKey()).isEqualTo("member-upstox-api-key");
            assertThat(a.accessToken()).isEqualTo("member-access-token");
            assertThat(a.extra()).isEmpty();
            assertThat(a.toString()).doesNotContain("member-access-token");
        });
        tokenFile(unlinked.id(), unlinked.id(), "BM6462", Instant.now().plusSeconds(3600));
        assertThat(source.access(unlinked)).as("no broker app linked: no session").isEmpty();
        tokenFile(otherClient.id(), otherClient.id(), "BM6462", Instant.now().plusSeconds(3600));
        assertThat(source.access(otherClient)).as("the file's Upstox client is not the one pinned on the account")
                .isEmpty();
        tokenFile(upstox.id(), otherClient.id(), "BM6462", Instant.now().plusSeconds(3600));
        assertThat(source.access(upstox)).as("a file naming another account is never used").isEmpty();
        assertThat(source.access(kotak)).as("another broker's account").isEmpty();
        secrets.writeToken("kite", upstox.id(), "{\"accessToken\":\"kite-token\"}");
        tokenFile(upstox.id(), upstox.id(), "BM6462", Instant.now().minusSeconds(1));
        assertThat(source.access(upstox)).as("expired (03:30 IST), and another broker's file is not read").isEmpty();
        secrets.deleteToken(upstox.id());
        assertThat(source.access(upstox)).isEmpty();
        assertThatThrownBy(() -> Class.forName("com.autotrade.upstox.ZtTigerTokenSource"))
                .as("zt-tiger-v2's token reader is not on the execution worker's class path")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void theWorkersSessionsDispatchByBrokerAndUpstoxHasNoLiveConnection() {
        tokenFile(upstox.id(), upstox.id(), "BM6462", Instant.now().plusSeconds(3600));
        BrokerSessions kite = a -> Optional.of(new BrokerSessions.Access("kite-key", "kite-token"));
        BrokerSessions neo = a -> Optional.of(new BrokerSessions.Access("neo-key", "neo-token"));
        BrokerSessionRouter router = new BrokerSessionRouter(Map.of(BrokerType.ZERODHA, kite, BrokerType.KOTAK_NEO, neo,
                BrokerType.UPSTOX, new UpstoxSessionSource(dataSource, secrets)));
        assertThat(router.access(upstox)).hasValueSatisfying(a -> assertThat(a.apiKey()).isEqualTo("member-upstox-api-key"));
        assertThat(router.access(kotak)).hasValueSatisfying(a -> assertThat(a.apiKey()).isEqualTo("neo-key"));

        List<BrokerAdapter> adapters = ExecutionService.adapters(dataSource, 1e9, null, null,
                new UpstoxSessionSource(dataSource, secrets), java.net.URI.create("http://127.0.0.1:1"),
                UpstoxConfig.at(java.net.URI.create("http://127.0.0.1:1")));
        assertThat(adapters).extracting(BrokerAdapter::broker).containsExactly(BrokerType.ZERODHA, BrokerType.KOTAK_NEO,
                BrokerType.UPSTOX);
        assertThat(adapters.get(2).live(upstox, null)).isNull();
        assertThat(ExecutionService.adapters(dataSource, 1e9, null, null, java.net.URI.create("http://127.0.0.1:1")))
                .as("the Kite and Kotak wiring as before").extracting(BrokerAdapter::broker)
                .containsExactly(BrokerType.ZERODHA, BrokerType.KOTAK_NEO);
        assertThat(LiveBrokers.LIVE).containsExactly(BrokerType.ZERODHA);
    }
}
