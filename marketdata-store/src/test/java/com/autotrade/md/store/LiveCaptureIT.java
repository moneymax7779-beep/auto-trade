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
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
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

    private static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(MarketTime.IST).toInstant();
    }
}
