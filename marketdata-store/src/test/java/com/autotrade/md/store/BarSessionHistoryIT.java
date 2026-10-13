package com.autotrade.md.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.OiBaseline;
import com.autotrade.core.time.MarketTime;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Features v12 (A-090): the bar history's one-query spot bars and the OI baseline read, on the real schema. */
@Testcontainers(disabledWithoutDocker = true)
class BarSessionHistoryIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

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
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (LocalDate day : List.of(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30))) {
            for (LocalTime t : List.of(LocalTime.of(9, 14), LocalTime.of(9, 15), LocalTime.of(10, 0), LocalTime.of(15, 14),
                    LocalTime.of(15, 15))) {
                double price = 22000 + day.getDayOfMonth() * 10 + t.getHour();
                jdbc.update("insert into hist.candle (instrument_key, underlying, kind, bar_start, open, high, low, close, source) "
                        + "values ('NSE_INDEX|Nifty 50', 'NIFTY', 'INDEX', ?, ?, ?, ?, ?, 'test')",
                        Timestamp.from(day.atTime(t).atZone(MarketTime.IST).toInstant()), price, price + 5, price - 5, price);
            }
        }
        String row = "insert into ref.oi_baseline (underlying, side, dte_bucket, minute_bucket, n, median, mad, first_day, "
                + "last_day, params, near_atm_strikes, heavy_weight_strikes, outer_strike_weight, window_min) "
                + "values (?, ?, ?, ?, 100, 0.001, 0.01, '2026-01-01', '2026-09-30', 'test', ?, 2, 0.5, 5)";
        jdbc.update(row, "NIFTY", "CE", 1, 3, 3);
        jdbc.update(row, "NIFTY", "PE", 1, 3, 3);
        jdbc.update(row, "SENSEX", "CE", 1, 3, 3);
        jdbc.update(row, "SENSEX", "PE", 1, 3, 4);                      // a second definition mixed in
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
    void spotBarsComeNewestFirstPerSessionInsideContinuousTrading() {
        BarSessionHistory bars = new BarSessionHistory(dataSource);
        List<List<MinuteBar>> sessions = bars.spotMinuteBars("NIFTY", LocalDate.of(2026, 10, 1), 2);
        assertThat(sessions).hasSize(2);
        assertThat(sessions.getFirst()).extracting(b -> b.start().atZone(MarketTime.IST).toLocalDate())
                .containsOnly(LocalDate.of(2026, 9, 30));
        assertThat(sessions.getFirst()).extracting(b -> b.start().atZone(MarketTime.IST).toLocalTime())
                .containsExactly(LocalTime.of(9, 15), LocalTime.of(10, 0), LocalTime.of(15, 14));   // 09:14 and 15:15 out
        assertThat(sessions.get(1).getFirst().start().atZone(MarketTime.IST).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(bars.spotMinuteBars("NIFTY", LocalDate.of(2026, 9, 28), 5)).isEmpty();          // nothing before the 28th
    }

    @Test
    void readsTheBaselineWithItsDefinitionAndRefusesMixedOnes() {
        BarSessionHistory bars = new BarSessionHistory(dataSource);
        OiBaseline nifty = bars.oiBaseline("NIFTY");
        assertThat(nifty.params()).isEqualTo(new OiBaseline.Params(3, 2, 0.5, 5));
        assertThat(nifty.cells()).containsKey(new OiBaseline.Cell.Key("PE", 1, 3));
        assertThat(bars.oiBaseline("SENSEX").params()).isNull();                                    // mixed: refused later
        assertThat(bars.oiBaseline("BANKNIFTY").isEmpty()).isTrue();
    }
}
