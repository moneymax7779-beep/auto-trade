package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.execution.SignalDecision;
import com.autotrade.execution.SignalDecisionStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * A stored signal decision wakes the execution tick at once (well inside the 1 s poll), a repeated decision (same
 * idempotency key) wakes nothing, and the listener comes back after its connection is killed.
 */
@Testcontainers(disabledWithoutDocker = true)
class SignalWakeupIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static long group;

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
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIGNAL-1", 500_000, DAY,
                "config/risk/signal-source.v1.yaml");
        group = accounts.ensureCopyGroup(tenant, "LIVE-SET", signal);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static SignalDecision decision(String key) {
        return new SignalDecision(group, 9001, DAY, Instant.now(), "oi-burst-cross", 1, "NIFTY",
                SignalDecision.Action.ENTER, "CE", null, "CONFIRMED", "test", 1234, "NSE_FO|1234", "NIFTY 23950 CE", 65,
                185.0, 500_000, 500_000, 1, key, Map.of());
    }

    private static void awaitListening(SignalWakeup wakeup) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!wakeup.listening() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(wakeup.listening()).as("listening").isTrue();
    }

    @Test
    void aStoredDecisionWakesTheTickAtOnceAndARepeatDoesNot() throws Exception {
        Semaphore ticks = new Semaphore(0);
        SignalWakeup wakeup = new SignalWakeup(dataSource, ticks::release, true);
        wakeup.start();
        try {
            awaitListening(wakeup);
            SignalDecisionStore store = new SignalDecisionStore(dataSource);
            long sent = System.nanoTime();
            assertThat(store.insert(decision("wake-1"))).isTrue();
            assertThat(ticks.tryAcquire(2, TimeUnit.SECONDS)).as("woken").isTrue();
            assertThat((System.nanoTime() - sent) / 1e6).as("ms from the insert to the tick").isLessThan(500);

            assertThat(store.insert(decision("wake-1"))).as("the same key again").isFalse();
            assertThat(ticks.tryAcquire(1500, TimeUnit.MILLISECONDS)).as("a repeat wakes nothing").isFalse();

            // the listener's connection is killed: it listens again (after RETRY) and the next decision wakes the tick
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList("select pg_terminate_backend(pid) "
                    + "from pg_stat_activity where query ilike 'listen %' and pid <> pg_backend_pid()");
            long deadline = System.currentTimeMillis() + 3_000;
            while (wakeup.listening() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(wakeup.listening()).as("the lost connection is noticed").isFalse();
            Thread.sleep(SignalWakeup.RETRY.toMillis());
            awaitListening(wakeup);
            assertThat(store.insert(decision("wake-2"))).isTrue();
            assertThat(ticks.tryAcquire(2, TimeUnit.SECONDS)).as("woken after the reconnect").isTrue();
        } finally {
            wakeup.destroy();
        }
    }

    @Test
    void turnedOffItNeverListens() throws Exception {
        Semaphore ticks = new Semaphore(0);
        SignalWakeup wakeup = new SignalWakeup(dataSource, ticks::release, false);
        wakeup.start();
        try {
            new SignalDecisionStore(dataSource).insert(decision("off-1"));
            assertThat(ticks.tryAcquire(1500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(wakeup.listening()).isFalse();
        } finally {
            wakeup.destroy();
        }
    }
}
