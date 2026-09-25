package com.autotrade.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.md.store.Runs;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers(disabledWithoutDocker = true)
class HeldOutGuardIT {

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

    @Test
    void aStrategyFamilyUsesEachHeldOutSessionOnceWhateverTheVersion() throws SQLException {
        LocalDate session = LocalDate.of(2026, 9, 22);
        long first = new Runs(dataSource).start("LIFECYCLE", "test", "test", List.of(session), List.of("NIFTY"), "{}");
        new LifecycleStore(dataSource, first).claimHeldOut("early-confirm-runner", "sha256:v2", List.of(session));

        assertThat(LifecycleStore.heldOutAlreadyUsed(dataSource, "early-confirm-runner", List.of(session)))
                .containsExactly(session);
        assertThat(LifecycleStore.heldOutAlreadyUsed(dataSource, "another-strategy", List.of(session))).isEmpty();

        long second = new Runs(dataSource).start("LIFECYCLE", "test", "test", List.of(session), List.of("NIFTY"), "{}");
        assertThatThrownBy(() -> new LifecycleStore(dataSource, second)
                .claimHeldOut("early-confirm-runner", "sha256:v3", List.of(session)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void declaredSplitIsReadable() throws SQLException {
        assertThat(LifecycleStore.splits(dataSource)).containsEntry(LocalDate.of(2026, 9, 18), "TUNING")
                .containsEntry(LocalDate.of(2026, 9, 22), "HELD_OUT").hasSize(17);
    }
}
