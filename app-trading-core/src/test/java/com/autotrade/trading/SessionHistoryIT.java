package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.MarketState;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.SideView;
import com.autotrade.strategy.Stage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Orphaned live sessions are closed at startup; the Live page's last-session view reads the day's end state. */
@Testcontainers(disabledWithoutDocker = true)
class SessionHistoryIT {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

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

    private static long session(TradeStore store, String account, String mode) {
        return store.startSession(account, mode, DAY, "test", "early-confirm-runner", "sha256:x", Map.of(), "test");
    }

    private static Decision decision(String underlying, String time, Stage ce) {
        Instant at = Instant.parse("2026-09-25T" + time + ":00Z");
        return new Decision(at, underlying, new MarketState(10, 50, 20, 0, "NORMAL"),
                new SideView(OptionSide.CE, ce, 40, 30, 0, Map.of("near_level", true)),
                new SideView(OptionSide.PE, Stage.IDLE, 0, 0, 0, Map.of()), List.of());
    }

    @Test
    void liveSessionsLeftRunningAreStoppedAtTheirLastDecisionAndNothingElseChanges() {
        TradeStore store = new TradeStore(dataSource);
        long orphan = session(store, "PAPER-1", "PAPER_LIVE");
        long orphanNoDecisions = session(store, "PAPER-1", "PAPER_LIVE");
        long replay = session(store, "PAPER-1", "PAPER_REPLAY");
        long otherAccount = session(store, "PAPER-2", "PAPER_LIVE");
        long done = session(store, "PAPER-1", "PAPER_LIVE");
        store.endSession(done, "DONE", Map.of("net", 5), null);
        store.decision(orphan, "early-confirm-runner", decision("NIFTY", "05:00", Stage.WATCH), 23000);
        store.decision(orphan, "early-confirm-runner", decision("NIFTY", "06:30", Stage.ARMED), 23010);

        assertThat(store.closeOrphanedLiveSessions("PAPER-1")).isEqualTo(2);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> closed = jdbc.queryForMap("select status, ended_at = '2026-09-25T06:30:00Z'::timestamptz "
                + "at_last_decision, error from trade.session where id = ?", orphan);
        assertThat(closed.get("status")).isEqualTo("STOPPED");
        assertThat(closed.get("at_last_decision")).isEqualTo(true);
        assertThat((String) closed.get("error")).contains("restarted or crashed");
        assertThat(jdbc.queryForObject("select status || ':' || (ended_at = started_at) from trade.session where id = ?",
                String.class, orphanNoDecisions)).isEqualTo("STOPPED:true");
        assertThat(jdbc.queryForObject("select status from trade.session where id = ?", String.class, replay))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status from trade.session where id = ?", String.class, otherAccount))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status from trade.session where id = ?", String.class, done))
                .isEqualTo("DONE");
        assertThat(store.closeOrphanedLiveSessions("PAPER-1")).as("idempotent").isZero();
    }

    @Test
    void lastDecisionsGiveEachUnderlyingAndStrategyItsFinalState() {
        TradeStore store = new TradeStore(dataSource);
        long id = session(store, "PAPER-3", "PAPER_REPLAY");
        store.decision(id, "early-confirm-runner", decision("NIFTY", "05:00", Stage.WATCH), 23000);
        store.decision(id, "early-confirm-runner", decision("NIFTY", "09:44", Stage.ARMED), 23050);
        store.decision(id, "expiry-gamma-breakout", decision("NIFTY", "09:44", Stage.COMPRESSION), 23050);
        store.decision(id, "early-confirm-runner", decision("SENSEX", "09:40", Stage.IDLE), 74000);

        List<Map<String, Object>> rows = new HistoryController(dataSource).lastDecisions(id);

        assertThat(rows).extracting(r -> r.get("underlying") + "/" + r.get("strategy_id") + "/" + r.get("t") + "/"
                + r.get("ce_stage")).containsExactly(
                "NIFTY/early-confirm-runner/15:14/ARMED",
                "NIFTY/expiry-gamma-breakout/15:14/COMPRESSION",
                "SENSEX/early-confirm-runner/15:10/IDLE");
        assertThat(rows.getFirst().get("ce_scores").toString()).contains("\"early\"").contains("near_level");
    }
}
