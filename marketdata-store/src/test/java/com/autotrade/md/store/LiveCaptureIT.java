package com.autotrade.md.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Live capture round trip: record, replay (auction ticks included), and use as history. */
@Testcontainers(disabledWithoutDocker = true)
class LiveCaptureIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY1 = LocalDate.of(2026, 9, 28);
    private static final LocalDate DAY2 = LocalDate.of(2026, 9, 29);

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
    void recordsReplaysAndServesHistory() throws Exception {
        LiveCapture capture = new LiveCapture(dataSource, DAY1, "UPSTOX_V3_LIVE", "test");
        long seq = 0;
        for (int minute = 0; minute < 10; minute++) {
            Instant t = at(DAY1, LocalTime.of(9, 15).plusMinutes(minute));
            capture.accept(new IndexTick(t, t, "BANKNIFTY", ++seq, 52000 + minute, 51900.0, null));
            capture.accept(new FutureTick(t, t, "BANKNIFTY", ++seq, 7, "BANKNIFTY FUT", LocalDate.of(2026, 10, 27),
                    52100 + minute, 1000L * (minute + 1), 5e5, null, 2000.0, 1500.0));
        }
        capture.accept(new AuctionTick(at(DAY1, LocalTime.of(15, 22)), null, "BANKNIFTY", 11, "HDFCBANK", 736, 735,
                5000, 800, 100, true));
        capture.flush();
        // a restart the same day appends to the same load instead of replacing it
        LiveCapture again = new LiveCapture(dataSource, DAY1, "UPSTOX_V3_LIVE", "test");
        Instant late = at(DAY1, LocalTime.of(14, 0));
        again.accept(new IndexTick(late, late, "BANKNIFTY", ++seq, 52500, 51900.0, null));
        again.close();
        capture.close();

        LoadManifests manifests = new LoadManifests(dataSource);
        LoadManifest active = manifests.active(DAY1, "BANKNIFTY").orElseThrow();
        assertThat(active.kind()).isEqualTo("CAPTURE");

        List<MarketEvent> events = new ArrayList<>();
        new ReplayReader(dataSource).replay(List.of(active.id()), events::add);
        assertThat(events).hasSize(22);
        assertThat(events).filteredOn(e -> e instanceof AuctionTick).singleElement()
                .satisfies(e -> assertThat(((AuctionTick) e).imbalanceTotal()).isEqualTo(800));
        assertThat(events).filteredOn(e -> e instanceof FutureTick).first()
                .satisfies(e -> assertThat(((FutureTick) e).totalBuyQuantity()).isEqualTo(2000.0));

        OwnSessionHistory history = new OwnSessionHistory(dataSource);
        assertThat(history.previousSession("BANKNIFTY", DAY2, LocalTime.of(15, 15))).get()
                .satisfies(bar -> {
                    assertThat(bar.session()).isEqualTo(DAY1);
                    assertThat(bar.open()).isEqualTo(52000);
                    assertThat(bar.high()).isEqualTo(52500);
                    assertThat(bar.close()).isEqualTo(52500);
                });
        List<Map<LocalTime, Long>> volumes = history.futuresMinuteVolumes("BANKNIFTY", DAY2, 5);
        assertThat(volumes).hasSize(1);
        assertThat(volumes.getFirst().get(LocalTime.of(9, 20))).isEqualTo(1000L);
        assertThat(history.spotMinuteBars("BANKNIFTY", DAY2, 5).getFirst()).hasSize(11);
        assertThat(history.previousSession("BANKNIFTY", DAY1, LocalTime.of(15, 15))).isEmpty(); // strictly before

        List<Map<LocalTime, Double>> turnover = new ReferenceStore(dataSource).auctionTurnover("BANKNIFTY", DAY2, 5);
        assertThat(turnover).hasSize(1);
        assertThat(turnover.getFirst().get(LocalTime.of(15, 22))).isEqualTo(736.0 * 5000);
    }

    @Test
    void farExpiryOptionsAreRecordedApartFromTheNearestExpirysTable() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 27);
        LiveCapture capture = new LiveCapture(dataSource, day, "UPSTOX_V3_LIVE", "test");
        Instant t = at(day, LocalTime.of(9, 20));
        capture.accept(option(t, 1, 101L, day));                                  // the nearest expiry: md.option_tick
        capture.acceptFar(option(t, 2, 202L, LocalDate.of(2026, 11, 3)));         // next weekly: md.option_tick_far
        capture.acceptFar(option(t, 3, 303L, LocalDate.of(2026, 11, 24)));        // the monthly: md.option_tick_far
        capture.close();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForList("select instrument_token from md.option_tick where session_date = ?", Long.class, day))
                .containsExactly(101L);
        assertThat(jdbc.queryForList("select instrument_token from md.option_tick_far where session_date = ? order by 1",
                Long.class, day)).containsExactly(202L, 303L);
        List<MarketEvent> events = new ArrayList<>();
        LoadManifest active = new LoadManifests(dataSource).active(day, "NIFTY").orElseThrow();
        new ReplayReader(dataSource).replay(List.of(active.id()), events::add);
        assertThat(events).as("a replay of the day never sees a far expiry").singleElement()
                .satisfies(e -> assertThat(((OptionTick) e).instrumentToken()).isEqualTo(101L));
    }

    @Test
    void aFailedFarExpiryWriteNeverCostsTheNearestExpirysRows() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 28);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("alter table md.option_tick_far rename to option_tick_far_away");      // the far COPY fails
        try {
            LiveCapture capture = new LiveCapture(dataSource, day, "UPSTOX_V3_LIVE", "test");
            Instant t = at(day, LocalTime.of(9, 20));
            capture.accept(option(t, 1, 111L, day));
            capture.acceptFar(option(t, 2, 222L, LocalDate.of(2026, 11, 3)));
            capture.close();
        } finally {
            jdbc.execute("alter table md.option_tick_far_away rename to option_tick_far");
        }
        assertThat(jdbc.queryForList("select instrument_token from md.option_tick where session_date = ?", Long.class, day))
                .containsExactly(111L);
    }

    @Test
    void aSupersededManifestKeepsItsFarExpiryRows() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 29);
        LiveCapture capture = new LiveCapture(dataSource, day, "UPSTOX_V3_LIVE", "test");
        Instant t = at(day, LocalTime.of(9, 20));
        capture.accept(option(t, 1, 131L, day));
        capture.acceptFar(option(t, 2, 232L, LocalDate.of(2026, 11, 3)));
        capture.close();
        LoadManifest active = new LoadManifests(dataSource).active(day, "NIFTY").orElseThrow();
        try (java.sql.Connection connection = dataSource.getConnection()) {
            new LoadManifests(dataSource).deleteRows(connection, active.id());               // as a zt clone does
        }
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select count(*) from md.option_tick where session_date = ?", Long.class, day))
                .isZero();
        assertThat(jdbc.queryForList("select instrument_token from md.option_tick_far where session_date = ?", Long.class,
                day)).as("zt has no far expiries to replace them").containsExactly(232L);
    }

    private static OptionTick option(Instant t, long seq, long token, LocalDate expiry) {
        return new OptionTick(t, t, "NIFTY", seq, token, "NIFTY 22650 PE " + expiry, expiry, 22650.0,
                OptionTick.OptionType.PE, 65, 100.0, 1000L, 5e6, 2000.0, 1500.0,
                DepthLevels.of(new double[] {99.9}, new long[] {650}, new int[] {1}),
                DepthLevels.of(new double[] {100.1}, new long[] {650}, new int[] {1}),
                0.12, -0.5, 0.001, -10.0, 11.0, 1.5, t, true, true);
    }

    private static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(MarketTime.IST).toInstant();
    }
}
