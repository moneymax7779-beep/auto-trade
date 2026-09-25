package com.autotrade.md.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Migrations, bulk load, manifest activation and ordered replay against a real TimescaleDB. */
@Testcontainers(disabledWithoutDocker = true)
class MarketDataStoreIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate SESSION = LocalDate.of(2026, 9, 2);
    private static final Instant T0 = Instant.parse("2026-09-02T03:45:00Z");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stop() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void loadsActivatesAndReplaysInOrder() throws Exception {
        LoadManifests manifests = new LoadManifests(dataSource);
        long first = load(manifests, 100.0);
        long second = load(manifests, 200.0);

        assertThat(manifests.active(SESSION, "NIFTY")).get().extracting(LoadManifest::id).isEqualTo(second);
        assertThat(manifests.list()).filteredOn(m -> m.id() == first).singleElement()
                .extracting(LoadManifest::status).isEqualTo("SUPERSEDED");

        List<MarketEvent> events = new ArrayList<>();
        long delivered = new ReplayReader(dataSource).replay(List.of(second), events::add);

        assertThat(delivered).isEqualTo(6);
        assertThat(events).isSortedAccordingTo(ReplayReader.ORDER);
        assertThat(events).extracting(MarketEvent::sourceSequence).containsExactly(11L, 12L, 13L, 14L, 15L, 0L);
        assertThat(events.getFirst()).isInstanceOf(IndexTick.class);
        assertThat(((IndexTick) events.getFirst()).price()).isEqualTo(200.0);
        OptionTick option = (OptionTick) events.stream().filter(OptionTick.class::isInstance).findFirst().orElseThrow();
        assertThat(option).isEqualTo(option(200.0));
        assertThat(events.getLast()).isInstanceOf(SessionPhaseEvent.class);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            SequenceDigest expected = new SequenceDigest();
            expected.add(13, 1300);
            assertThat(TableChecks.digest(connection, MdTable.OPTION_TICK, second).sameAs(expected.result())).isTrue();
            assertThat(TableChecks.count(connection, MdTable.CONSTITUENT_TICK, second)).isEqualTo(1);
            connection.rollback();
        }
    }

    private static long load(LoadManifests manifests, double price) throws Exception {
        long id = manifests.start("CLONE", SESSION, "NIFTY", "test", "test", List.of("ticks"), "test");
        MdRowEncoder encoder = new MdRowEncoder();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            copy(connection, MdTable.INDEX_TICK, encoder.index(
                    new IndexTick(T0.plusMillis(10), T0, "NIFTY", 11, price, 99.0, 23850), meta(id, 1100)));
            copy(connection, MdTable.FUTURE_TICK, encoder.future(
                    new FutureTick(T0.plusMillis(20), null, "NIFTY", 12, 5L, "NIFTY FUT", SESSION.plusDays(27),
                            price + 5, 1000L, 1.5e7, price + 4), meta(id, 1200)));
            copy(connection, MdTable.OPTION_TICK, encoder.option(option(price), meta(id, 1300)));
            copy(connection, MdTable.CONSTITUENT_TICK, encoder.constituent(
                    new ConstituentTick(T0.plusMillis(40), null, "NIFTY", 14, 9L, "HDFCBANK", 950.0, 940.0, 12L,
                            945.0, 12.9), meta(id, 1400)));
            copy(connection, MdTable.INDEX_TICK, encoder.index(
                    new IndexTick(T0.plusMillis(50), null, "NIFTY", 15, price + 1, null, null), meta(id, 1500)));
            copy(connection, MdTable.SESSION_PHASE_EVENT, encoder.sessionPhase(new SessionPhaseRow(
                    new SessionPhaseEvent(T0.plusSeconds(3600), T0.plusSeconds(3600), "NIFTY",
                            "CAS_REFERENCE_TRANSITION", "CAS_INDICATIVE_INDEX_UNVERIFIED", price, false),
                    "A", "B", Boolean.FALSE, "FEED", "key-" + id), id, SESSION));
            manifests.activate(connection, id, "{}", "{}", "{}", "{}", "{}");
            connection.commit();
        }
        return id;
    }

    private static OptionTick option(double price) {
        return new OptionTick(T0.plusMillis(30), T0.plusMillis(29), "NIFTY", 13, 42L, "NIFTY 23850 CE 08 SEP 26",
                SESSION.plusDays(6), 23850.0, OptionTick.OptionType.CE, 65, price / 2, 2093000L, 1423890.0,
                1910025.0, 109915.0,
                DepthLevels.of(new double[] {88.7, 88.65}, new long[] {325, 1235}, new int[] {0, 0}),
                DepthLevels.of(new double[] {88.9}, new long[] {1820}, new int[] {1}),
                0.1082, 0.52, 0.0011, -10.25, 11.85, 1.55, T0.plusMillis(29), true, true);
    }

    private static RowMeta meta(long manifestId, long hash64) {
        return new RowMeta(manifestId, SESSION, hash64, "NFO", "TEST");
    }

    private static void copy(Connection connection, MdTable table, byte[] row) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(row);
        CopyLoader.copy(connection, table, new ByteArrayInputStream(buffer.toByteArray()));
    }
}
