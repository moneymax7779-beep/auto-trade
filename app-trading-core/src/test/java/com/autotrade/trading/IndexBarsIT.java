package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.core.time.MarketTime;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The multi-day index chart: sessions found backwards from {@code until} over a weekend, a bar day's index minutes with
 * its near-month futures volume, a tick day's per-minute futures volume from the cumulative day volume, and the live
 * PAPER account's trade on the index (another account's is left out).
 */
@Testcontainers(disabledWithoutDocker = true)
class IndexBarsIT {

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres"));
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static Timestamp at(LocalDate day, String time) {
        return Timestamp.from(day.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant());
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoSessionsAcrossAWeekendWithVolumeAndTheLiveAccountsTrade() {
        LocalDate friday = LocalDate.of(2025, 12, 26), monday = LocalDate.of(2025, 12, 29);
        // Friday: stored one-minute bars, index and the near-month future (a later expiry is ignored)
        for (int m = 0; m < 3; m++) {
            Timestamp t = at(friday, LocalTime.of(9, 15 + m).toString());
            jdbc.update("insert into hist.candle (instrument_key, underlying, kind, bar_start, open, high, low, close, source) "
                    + "values ('NSE_INDEX|Nifty 50', 'NIFTY', 'INDEX', ?, 100, 102, 99, 101, 'test')", t);
            jdbc.update("insert into hist.candle (instrument_key, underlying, kind, expiry, bar_start, open, high, low, close, "
                    + "volume, source) values ('NSE_FO|F1', 'NIFTY', 'FUTURE', '2025-12-30', ?, 1, 1, 1, 1, ?, 'test')", t, 1000 + m);
            jdbc.update("insert into hist.candle (instrument_key, underlying, kind, expiry, bar_start, open, high, low, close, "
                    + "volume, source) values ('NSE_FO|F2', 'NIFTY', 'FUTURE', '2026-01-27', ?, 1, 1, 1, 1, 7, 'test')", t);
        }
        // Monday: own capture, two index minutes; the future's cumulative volume 500 -> 800 (09:15) -> 1,100 (09:16)
        String[][] index = {{"09:15:05", "200"}, {"09:15:40", "203"}, {"09:16:10", "201"}};
        for (String[] r : index) {
            jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, src_hash64, price) "
                    + "values (1, ?, 'NIFTY', ?, 1, 1, ?)", monday, at(monday, r[0]), Double.parseDouble(r[1]));
        }
        String[][] fut = {{"09:15:01", "500"}, {"09:15:50", "800"}, {"09:16:30", "1100"}};
        for (String[] r : fut) {
            jdbc.update("insert into md.future_tick (manifest_id, session_date, underlying, recv_ts, src_seq, src_hash64, "
                    + "instrument_token, symbol, segment, expiry, price, volume) values (1, ?, 'NIFTY', ?, 1, 1, 9, "
                    + "'NIFTY FUT', 'NSE_FO', '2025-12-30', 200, ?)", monday, at(monday, r[0]), Long.parseLong(r[1]));
        }
        TradeStore store = new TradeStore(dataSource);
        for (String account : List.of("PAPER-1", "SHADOW-1")) {
            long s = store.startSession(account, "PAPER_LIVE", monday, "upstox", "live-set", "sha256:x", Map.of(), "test");
            jdbc.update("insert into trade.position (session_id, underlying, option_side, symbol, opened_at, closed_at, stages, "
                    + "exit_reason, realised, costs, net, strategy_id, quantity, lot_size, average_cost, average_exit) values "
                    + "(?, 'NIFTY', 'CE', 'NIFTY 200 CE 30 DEC 25', ?, ?, '{CONFIRMED}', 'STOP', 0, 0, -50, 'ma-cross', 65, 65, 10, 9)",
                    s, at(monday, "09:15:30"), at(monday, "09:16:00"));
        }

        IndexBarsController api = new IndexBarsController(new ChartDb(dataSource), "PAPER-1");
        Map<String, Object> r = api.indexBars("nifty", 2, monday.toString());

        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        assertThat(days).extracting(d -> d.get("date")).containsExactly(friday.toString(), monday.toString());
        assertThat(days.get(0)).containsEntry("source", "bars");
        assertThat((List<Object[]>) days.get(0).get("bars")).hasSize(3).first()
                .satisfies(b -> assertThat(b).containsExactly("09:15", 100.0, 102.0, 99.0, 101.0, 1000L));
        assertThat(days.get(1)).containsEntry("source", "ticks");
        List<Object[]> ticks = (List<Object[]>) days.get(1).get("bars");
        assertThat(ticks).hasSize(2);
        assertThat(ticks.get(0)).containsExactly("09:15", 200.0, 203.0, 200.0, 203.0, 300L);
        assertThat(ticks.get(1)).containsExactly("09:16", 201.0, 201.0, 201.0, 201.0, 300L);
        assertThat((List<Map<String, Object>>) r.get("trades")).singleElement().satisfies(t -> {
            assertThat(t).containsEntry("strategy", "ma-cross").containsEntry("opened", "09:15").containsEntry("closed", "09:16");
        });
    }
}
