package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.core.time.MarketTime;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The session panels on a bar day: the ma-cross stop is the last 3-minute swing low confirmed by the entry minute, the
 * premium excursion covers only the minutes held, unknown ids are 404s, and a day missing VIX or option prices is not
 * kept in the chart cache (its bars may still be fetched).
 */
@Testcontainers(disabledWithoutDocker = true)
class SessionPanelsIT {

    private static final LocalDate DAY = LocalDate.of(2025, 12, 29);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:2.30.1-pg17")
                .asCompatibleSubstituteFor("postgres"));
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
        dataSource.close();
        postgres.stop();
    }

    private static Timestamp at(LocalDate day, String time) {
        return Timestamp.from(day.atTime(LocalTime.parse(time)).atZone(MarketTime.IST).toInstant());
    }

    private static void bar(JdbcTemplate jdbc, String key, String kind, LocalDate expiry, Double strike, String type,
                            Timestamp start, double open, double high, double low, double close) {
        jdbc.update("insert into hist.candle (instrument_key, underlying, kind, expiry, strike, option_type, bar_start, open, "
                + "high, low, close, source) values (?, 'NIFTY', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test')",
                key, kind, expiry, strike, type, start, open, high, low, close);
    }

    @Test
    void stopTargetPremiumAndContextOnABarDay() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // the index: 3-minute slots of flat minutes falling 10 a slot to 24,900 (slot 10, 09:45) and rising after it
        for (int minute = 0; minute < 90; minute++) {
            int slot = minute / 3;
            double p = slot <= 10 ? 25000 - 10 * slot : 24900 + 10 * (slot - 10);
            bar(jdbc, "NSE_INDEX|Nifty 50", "INDEX", null, null, null, at(DAY, LocalTime.of(9, 15).plusMinutes(minute).toString()),
                    p, p + 1, p - 1, p);
        }
        bar(jdbc, "NSE_INDEX|Nifty 50", "INDEX", null, null, null, at(DAY.minusDays(3), "15:29"), 24950, 24950, 24950, 24950);
        LocalDate expiry = LocalDate.of(2025, 12, 30);
        double[][] option = {{10 * 60 + 29, 55, 999, 1, 55}, {10 * 60 + 30, 55, 57, 54, 56}, {10 * 60 + 35, 56, 60, 50, 58},
                {10 * 60 + 40, 58, 58, 52, 57}, {10 * 60 + 41, 57, 99, 1, 57}};
        for (double[] o : option) {
            bar(jdbc, "NSE_FO|1", "OPTION", expiry, 25950.0, "CE", at(DAY, LocalTime.ofSecondOfDay((long) o[0] * 60).toString()),
                    o[1], o[2], o[3], o[4]);
        }
        TradeStore store = new TradeStore(dataSource);
        long session = store.startSession("PAPER-1", "PAPER_REPLAY", DAY, "replay-as-live:bars", "ma-cross", "sha256:x", Map.of(), "test");
        long other = store.startSession("PAPER-1", "PAPER_REPLAY", DAY, "replay-as-live:bars", "ma-cross", "sha256:x", Map.of(), "test");
        jdbc.update("insert into trade.candidate (session_id, strategy_id, underlying, at, action, side, reason, approved, spot, context) "
                + "values (?, 'ma-cross', 'NIFTY', ?, 'ENTER', 'CE', 'EMA_CROSS_UP', true, 25080, '{}'::jsonb)", session, at(DAY, "10:30"));
        long position = jdbc.queryForObject("insert into trade.position (session_id, underlying, option_side, symbol, opened_at, "
                + "closed_at, stages, exit_reason, realised, costs, net, strategy_id, quantity, lot_size, average_cost, average_exit) "
                + "values (?, 'NIFTY', 'CE', 'NIFTY 25950 CE 30 DEC 25', ?, ?, '{CONFIRMED}', 'TIME', 0, 0, 0, 'ma-cross', 75, 75, "
                + "55.5, 57) returning id", Long.class, session, at(DAY, "10:30:01"), at(DAY, "10:40:00"));
        SessionPanelsController panels = new SessionPanelsController(new ChartDb(dataSource));

        Map<String, Object> structure = panels.structure(session, "nifty");
        assertThat(structure).containsEntry("source", "bars");
        assertThat((List<?>) structure.get("candles")).hasSize(30);
        @SuppressWarnings("unchecked")
        Map<String, Object> trade = ((List<Map<String, Object>>) structure.get("trades")).getFirst();
        // the 09:45 slot's low (24,899) is a swing once the 09:51 slot closes (09:54); target 2 x the 181-point risk
        assertThat(trade).containsEntry("position", position).containsEntry("at", "10:30").containsEntry("exit", "10:40")
                .containsEntry("stop", 24899.0).containsEntry("target", 25442.0).containsEntry("rewardToRiskAssumed", true);

        Map<String, Object> premium = panels.premium(session, position);
        assertThat(premium).containsEntry("source", "bars").containsEntry("strike", 25950.0);
        assertThat((List<?>) premium.get("bars")).hasSize(5);
        assertThat(premium.get("excursion")).isEqualTo(Map.of("bestPrice", 60.0, "bestAt", "10:35", "mfe", 4.5,
                "mfeRupees", 338L, "worstPrice", 50.0, "worstAt", "10:35", "mae", 5.5, "maeRupees", 413L));

        assertThatThrownBy(() -> panels.premium(other, position)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> panels.structure(999_999, "NIFTY")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        Map<String, Object> context = panels.dayContext(DAY.toString(), "NIFTY");
        @SuppressWarnings("unchecked")
        Map<String, Object> index = (Map<String, Object>) context.get("index");
        assertThat(index).containsEntry("prevClose", 24950.0).containsEntry("gapPct", 0.2);
        assertThat((List<?>) context.get("vix")).isEmpty();
        assertThat(SessionPanelsController.complete(context)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from md.chart_day_cache where kind = 'day-context'", Integer.class))
                .as("an incomplete day is computed again, not kept").isZero();
    }
}
