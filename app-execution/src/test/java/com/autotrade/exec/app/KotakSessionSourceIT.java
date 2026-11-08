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
import com.autotrade.execution.KotakDryRunAdapter;
import com.autotrade.execution.LiveBrokers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The worker's Kotak Neo session (live adapters plan, step B1/C): the token file the login wrote
 * ({@code kotak-<id>.token}: token, sid, base URL, expiry) and the consumer key from the account's broker app become
 * one {@link BrokerSessions.Access}; the worker's sessions dispatch by broker (Kite's unchanged); the worker's adapters
 * are Zerodha and Kotak, and Kotak has no LIVE connection.
 */
@Testcontainers(disabledWithoutDocker = true)
class KotakSessionSourceIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    @TempDir
    static Path dir;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static BrokerSecrets secrets;
    private static BrokerAccount kotak, unlinked, zerodha;

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
        long me = accounts.ensureUser("kc-owner", "me");
        accounts.ensureMembership(tenant, me, "OWNER");
        LocalDate day = LocalDate.of(2026, 11, 9);
        long k = accounts.ensureFollowerAccount(tenant, me, "KOTAK-S1", BrokerType.KOTAK_NEO, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, "../config/risk/follower-risk.v6.yaml", 100);
        long u = accounts.ensureFollowerAccount(tenant, me, "KOTAK-S2", BrokerType.KOTAK_NEO, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, "../config/risk/follower-risk.v6.yaml", 100);
        long z = accounts.ensureFollowerAccount(tenant, me, "KITE-S1", BrokerType.ZERODHA, TradeMode.DRY_RUN, 200_000,
                EquityMode.FIXED, day, "../config/risk/follower-risk.v6.yaml", 100);
        String keyRef = BrokerSecrets.storeRef(tenant, 1, "key");
        secrets.write(keyRef, "neo-consumer-key");
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'KOTAK_NEO', 'Neo app', ?, ?) returning id", Long.class, tenant,
                keyRef, keyRef);
        jdbc.update("update acct.broker_account set credential_profile_id = ? where id = ?", profile, k);
        kotak = accounts.byId(k).orElseThrow();
        unlinked = accounts.byId(u).orElseThrow();
        zerodha = accounts.byId(z).orElseThrow();
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static void tokenFile(long accountId, Instant expires) {
        secrets.writeToken("kotak", accountId, "{\"ucc\":\"KT1234\",\"token\":\"trade-token\",\"sid\":\"trade-sid\","
                + "\"baseUrl\":\"https://e21.kotaksecurities.com\",\"accountId\":" + accountId + ",\"expiresAt\":\""
                + expires + "\"}\n");
    }

    @Test
    void theDaysKotakSessionIsTheTokenFileAndTheAppsConsumerKey() {
        KotakSessionSource source = new KotakSessionSource(dataSource, secrets);
        assertThat(source.access(kotak)).as("no login today").isEmpty();
        tokenFile(kotak.id(), Instant.now().plusSeconds(3600));
        assertThat(source.access(kotak)).hasValueSatisfying(a -> {
            assertThat(a.apiKey()).isEqualTo("neo-consumer-key");
            assertThat(a.accessToken()).isEqualTo("trade-token");
            assertThat(a.extra()).containsEntry(KotakDryRunAdapter.SID, "trade-sid")
                    .containsEntry(KotakDryRunAdapter.BASE_URL, "https://e21.kotaksecurities.com");
            assertThat(a.toString()).doesNotContain("trade-token").doesNotContain("trade-sid");
        });
        tokenFile(unlinked.id(), Instant.now().plusSeconds(3600));
        assertThat(source.access(unlinked)).as("no broker app linked: no consumer key").isEmpty();
        assertThat(source.access(zerodha)).as("another broker's account").isEmpty();
        tokenFile(kotak.id(), Instant.now().minusSeconds(1));
        assertThat(source.access(kotak)).as("expired").isEmpty();
        secrets.deleteToken(kotak.id());
        assertThat(source.access(kotak)).isEmpty();
    }

    @Test
    void theWorkersSessionsDispatchByBrokerAndKotakHasNoLiveConnection() {
        tokenFile(kotak.id(), Instant.now().plusSeconds(3600));
        BrokerSessions kite = a -> Optional.of(new BrokerSessions.Access("kite-key", "kite-token"));
        BrokerSessionRouter router = new BrokerSessionRouter(Map.of(BrokerType.ZERODHA, kite,
                BrokerType.KOTAK_NEO, new KotakSessionSource(dataSource, secrets)));
        assertThat(router.access(zerodha)).hasValueSatisfying(a -> assertThat(a.apiKey()).isEqualTo("kite-key"));
        assertThat(router.access(kotak)).hasValueSatisfying(a -> assertThat(a.apiKey()).isEqualTo("neo-consumer-key"));
        BrokerAccount upstox = new BrokerAccount(kotak.id() + 1000, kotak.tenantId(), kotak.appUserId(),
                BrokerType.UPSTOX, kotak.displayName(), kotak.legacyLabel(), kotak.role(), kotak.tradeMode(), false,
                kotak.allocatedCapital(), kotak.equityMode(), kotak.equityFrom(), kotak.riskFile(), kotak.sizeStepPct(),
                true);
        assertThat(router.access(upstox)).isEmpty();

        List<BrokerAdapter> adapters = ExecutionService.adapters(dataSource, 1e9, null, null,
                java.net.URI.create("http://127.0.0.1:1"));
        assertThat(adapters).extracting(BrokerAdapter::broker).containsExactly(BrokerType.ZERODHA, BrokerType.KOTAK_NEO);
        assertThat(adapters.get(1).live(kotak, null)).isNull();
        assertThat(LiveBrokers.LIVE).containsExactly(BrokerType.ZERODHA);
    }
}
