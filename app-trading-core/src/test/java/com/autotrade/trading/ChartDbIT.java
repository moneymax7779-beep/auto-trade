package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** The charts' own pool (no parallel workers) and the kept chart days (V26), tied to the day's ACTIVE load manifest. */
@Testcontainers(disabledWithoutDocker = true)
class ChartDbIT {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource engine;
    private static ChartDb charts;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:2.30.1-pg17")
                .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setPoolName("autotrade");
        engine = new HikariDataSource(config);
        Flyway.configure().dataSource(engine).locations("classpath:db/migration").load().migrate();
        charts = new ChartDb(engine);
    }

    @AfterAll
    static void stop() {
        charts.destroy();
        engine.close();
        postgres.stop();
    }

    private static long manifest(String underlying, String status) {
        return new JdbcTemplate(engine).queryForObject("insert into md.load_manifest (kind, session_date, underlying, "
                + "source_name, source_detail, datasets, status, tool_version) values ('CAPTURE', ?, ?, 'test', 'test', "
                + "'{options}', ?, 'test') returning id", Long.class, DAY, underlying, status);
    }

    @Test
    void chartsUseTheirOwnPoolWithoutParallelWorkers() {
        assertThat(charts.dataSource()).isNotSameAs(engine);
        assertThat(((HikariDataSource) charts.dataSource()).getPoolName()).isEqualTo("charts");
        assertThat(((HikariDataSource) charts.dataSource()).getMaximumPoolSize()).isEqualTo(ChartDb.POOL);
        assertThat(charts.jdbc().queryForObject("show max_parallel_workers_per_gather", String.class)).isEqualTo("0");
        assertThat(new JdbcTemplate(engine).queryForObject("show max_parallel_workers_per_gather", String.class))
                .as("the engine's connections are unchanged").isNotEqualTo("0");
    }

    @Test
    void aKeptDayIsReturnedUntilItsDayIsLoadedAgain() {
        assertThat(charts.load("oi-tape", DAY, "NIFTY")).as("nothing kept").isNull();
        charts.save("oi-tape", DAY, "SENSEX", new byte[] {9});
        assertThat(charts.load("oi-tape", DAY, "SENSEX")).as("no active manifest: not kept").isNull();

        long first = manifest("NIFTY", "ACTIVE");
        charts.save("oi-tape", DAY, "NIFTY", new byte[] {1, 2, 3});
        assertThat(charts.load("oi-tape", DAY, "NIFTY")).containsExactly(1, 2, 3);
        assertThat(charts.load("pcr", DAY, "NIFTY")).as("another kind").isNull();
        charts.save("oi-tape", DAY, "NIFTY", new byte[] {4});
        assertThat(charts.load("oi-tape", DAY, "NIFTY")).as("replaced").containsExactly(4);

        new JdbcTemplate(engine).update("update md.load_manifest set status = 'SUPERSEDED' where id = ?", first);
        manifest("NIFTY", "ACTIVE");
        assertThat(charts.load("oi-tape", DAY, "NIFTY")).as("the day was loaded again: computed again").isNull();
    }

    /** 29 Oct 2026: a finished day's JSON answer (surges, premium, levels) comes back as it was kept. */
    @Test
    void aFinishedDaysJsonAnswerIsKeptAndReadBack() {
        LocalDate day = LocalDate.of(2026, 10, 20);
        new JdbcTemplate(engine).update("insert into md.load_manifest (kind, session_date, underlying, source_name, "
                + "source_detail, datasets, status, tool_version) values ('CAPTURE', ?, 'SENSEX', 'test', 'test', '{options}', "
                + "'ACTIVE', 'test')", day);
        java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("underlying", "SENSEX");
        answer.put("index", java.util.List.of(java.util.List.of("09:15", 74100.5), java.util.List.of("09:16", 74102.0)));
        answer.put("optionsExpiry", null);
        charts.saveJson("surges", day, "SENSEX", answer);
        assertThat(charts.loadJson("surges", day, "SENSEX")).isEqualTo(answer);
        assertThat(charts.loadJson("surges", day, "NIFTY")).isNull();
        assertThat(ChartDb.finished(day)).isTrue();
        assertThat(ChartDb.finished(LocalDate.now(com.autotrade.core.time.MarketTime.IST).plusDays(1))).isFalse();
    }
}
