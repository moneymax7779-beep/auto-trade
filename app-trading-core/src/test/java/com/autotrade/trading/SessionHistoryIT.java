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
    private static final AuthProperties NO_LOGIN = new AuthProperties("none", null, null, null, null, null, null, null);
    private static final AuthProperties OIDC = new AuthProperties("oidc", null, null, null, null, null, null, null);
    private static final org.springframework.security.core.Authentication MEMBER =
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("demo", "x", List.of());
    private static final org.springframework.security.core.Authentication OPERATOR =
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("satya", "x",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR")));

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
    void shadowSessionsLeftRunningAreStoppedAndTheirStrategyFileIsPendingAgain() {
        TradeStore store = new TradeStore(dataSource);
        LocalDate day = LocalDate.parse("2026-10-21");
        long finished = store.startSession("SHADOW-1", "PAPER_REPLAY", day, "own", "orc", "sha256:x",
                Map.of("orc-v1.yaml", "sha256:a", "features.v12.yaml", "sha256:f"), "test");
        store.endSession(finished, "DONE", Map.of("net", 0), null);
        long cut = store.startSession("SHADOW-1", "PAPER_REPLAY", day, "own", "zbf", "sha256:x",
                Map.of("zbf-v10.yaml", "sha256:b", "features.v12.yaml", "sha256:f"), "test");
        long failed = store.startSession("SHADOW-1", "PAPER_REPLAY", day, "own", "qpb", "sha256:x",
                Map.of("qpb-v4.yaml", "sha256:c"), "test");
        store.endSession(failed, "FAILED", Map.of(), "boom");
        long replay = session(store, "PAPER-1", "PAPER_REPLAY");
        long live = session(store, "SHADOW-1", "PAPER_LIVE");
        assertThat(store.ranWith("SHADOW-1", day, "zbf-v10.yaml")).as("running counts as ran").isTrue();

        assertThat(store.closeOrphanedShadowSessions("SHADOW-1")).isEqualTo(1);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> closed = jdbc.queryForMap("select status, error from trade.session where id = ?", cut);
        assertThat(closed.get("status")).isEqualTo("STOPPED");
        assertThat((String) closed.get("error")).contains("restarted or crashed");
        assertThat(jdbc.queryForObject("select status from trade.session where id = ?", String.class, replay))
                .as("other accounts' replays run in their own processes").isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status from trade.session where id = ?", String.class, live))
                .as("live sessions are closeOrphanedLiveSessions' business").isEqualTo("RUNNING");
        assertThat(store.ranWith("SHADOW-1", day, "orc-v1.yaml")).as("done").isTrue();
        assertThat(store.ranWith("SHADOW-1", day, "qpb-v4.yaml")).as("failed: not retried").isTrue();
        assertThat(store.ranWith("SHADOW-1", day, "zbf-v10.yaml")).as("cut short: pending again").isFalse();
        assertThat(store.ranWith("SHADOW-1", day, "odb-v9.yaml")).as("never ran").isFalse();
        assertThat(store.ranWith("SHADOW-1", day.minusDays(1), "orc-v1.yaml")).as("other day").isFalse();
        assertThat(store.ranWith("PAPER-1", day, "orc-v1.yaml")).as("other account").isFalse();
        assertThat(store.closeOrphanedShadowSessions("SHADOW-1")).as("idempotent").isZero();
    }

    @Test
    void lastDecisionsGiveEachUnderlyingAndStrategyItsFinalState() {
        TradeStore store = new TradeStore(dataSource);
        long id = session(store, "PAPER-3", "PAPER_REPLAY");
        store.decision(id, "early-confirm-runner", decision("NIFTY", "05:00", Stage.WATCH), 23000);
        store.decision(id, "early-confirm-runner", decision("NIFTY", "09:44", Stage.ARMED), 23050);
        store.decision(id, "expiry-gamma-breakout", decision("NIFTY", "09:44", Stage.COMPRESSION), 23050);
        store.decision(id, "early-confirm-runner", decision("SENSEX", "09:40", Stage.IDLE), 74000);

        List<Map<String, Object>> rows = new HistoryController(dataSource, null, NO_LOGIN).lastDecisions(id);

        assertThat(rows).extracting(r -> r.get("underlying") + "/" + r.get("strategy_id") + "/" + r.get("t") + "/"
                + r.get("ce_stage")).containsExactly(
                "NIFTY/early-confirm-runner/15:14/ARMED",
                "NIFTY/expiry-gamma-breakout/15:14/COMPRESSION",
                "SENSEX/early-confirm-runner/15:10/IDLE");
        assertThat(rows.getFirst().get("ce_scores").toString()).contains("\"early\"").contains("near_level");
    }

    @Test
    void aStraddleEntryIsLoggedWithItsBudget() {
        TradeStore store = new TradeStore(dataSource);
        long id = session(store, "PAPER-4", "PAPER_REPLAY");
        Decision base = decision("NIFTY", "08:24", Stage.CONFIRMED);
        store.decision(id, "expiry-breakout-straddle", new Decision(base.time(), base.underlying(), base.state(),
                base.ce(), base.pe(), List.of(com.autotrade.strategy.OrderIntent.straddle(0, 500_000, 30, 20,
                        Stage.CONFIRMED, "BREAKOUT_DOWN"))), 23307.8);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select orders from trade.decision where session_id = ?",
                String.class, id)).isEqualTo("ENTER_STRADDLE:CE+PE:500000:BREAKOUT_DOWN");
    }
    /**
     * The Sessions page's runs: a batch's zt days and own-capture days are one run, its bar days another; a re-run day counts
     * once with its latest net; a different config file set is a different run; the run filter returns exactly its sessions.
     */
    @Test
    void sessionRunsGroupABatchAcrossTickFeedsAndTheFilterReturnsItsSessions() {
        TradeStore store = new TradeStore(dataSource);
        HistoryController history = new HistoryController(dataSource, null, NO_LOGIN);
        Map<String, String> v1 = Map.of("runs-test.v1.yaml", "sha256:a", "paper-risk.v17.yaml", "sha256:r");
        Map<String, String> v2 = Map.of("runs-test.v2.yaml", "sha256:b", "paper-risk.v17.yaml", "sha256:r");
        long zt = store.startSession("PAPER-1", "PAPER_REPLAY", LocalDate.of(2026, 9, 2), "replay-as-live:zt-tiger-v2",
                "runs-test", "sha256:x", v1, "runs1");
        long ztAgain = store.startSession("PAPER-1", "PAPER_REPLAY", LocalDate.of(2026, 9, 2), "replay-as-live:zt-tiger-v2",
                "runs-test", "sha256:x", v1, "runs1");
        long own = store.startSession("PAPER-1", "PAPER_REPLAY", LocalDate.of(2026, 10, 14), "replay-as-live:own-capture",
                "runs-test", "sha256:x", v1, "runs1");
        long bars = store.startSession("PAPER-1", "PAPER_REPLAY", LocalDate.of(2026, 3, 2), "replay-as-live:bars",
                "runs-test", "sha256:x", v1, "runs1");
        long other = store.startSession("PAPER-1", "PAPER_REPLAY", LocalDate.of(2026, 9, 2), "replay-as-live:zt-tiger-v2",
                "runs-test", "sha256:x", v2, "runs1");
        store.endSession(zt, "DONE", Map.of("net", 100), null);
        store.endSession(ztAgain, "DONE", Map.of("net", 250), null);
        store.endSession(own, "DONE", Map.of("net", -50), null);
        store.endSession(bars, "DONE", Map.of("net", 7), null);
        store.endSession(other, "DONE", Map.of("net", 9), null);

        List<Map<String, Object>> runs = history.sessionRuns().stream().filter(r -> "runs1".equals(r.get("code_version"))).toList();
        assertThat(runs).hasSize(3);
        Map<String, Object> ticks = runs.stream().filter(r -> "ticks".equals(r.get("data"))
                && r.get("configs").toString().contains("runs-test.v1")).findFirst().orElseThrow();
        assertThat(((Number) ticks.get("days")).intValue()).isEqualTo(2);
        assertThat(((Number) ticks.get("sessions")).intValue()).isEqualTo(3);
        assertThat(((Number) ticks.get("net")).intValue()).as("the re-run day counts once, latest net").isEqualTo(200);
        assertThat(ticks.get("date_from")).isEqualTo("2026-09-02");
        assertThat(ticks.get("date_to")).isEqualTo("2026-10-14");

        List<Map<String, Object>> sessions = history.sessions(new HistoryController.SessionFilter("runs1", "runs-test",
                (String) ticks.get("config_key"), "PAPER_REPLAY", "ticks"));
        assertThat(sessions).extracting(r -> ((Number) r.get("id")).longValue()).containsExactly(own, ztAgain, zt);
        assertThat(history.sessions(new HistoryController.SessionFilter("runs1", null, null, null, "bars")))
                .extracting(r -> ((Number) r.get("id")).longValue()).containsExactly(bars);
        // blank values are absent: "?code=runs1&data=" is the code filter alone
        assertThat(history.sessions(new HistoryController.SessionFilter("runs1", " ", "", null, "")))
                .extracting(r -> ((Number) r.get("id")).longValue()).containsExactlyInAnyOrder(zt, ztAgain, own, bars, other);
        // an unknown value matches nothing (no error)
        assertThat(history.sessions(new HistoryController.SessionFilter("runs1", null, null, null, "minutes"))).isEmpty();
        // all blank = the unfiltered list (live sessions + the newest 200), as before
        assertThat(history.sessions(new HistoryController.SessionFilter("", null, " ", null, null)))
                .extracting(r -> ((Number) r.get("id")).longValue()).contains(zt, ztAgain, own, bars, other);
    }

    /**
     * User, 10 Nov 2026 (members see "the chart and trades, nothing more"): a member's session list is the live PAPER
     * account's sessions only, without configs, code or P&L, whatever the filter; and only their trades can be read.
     */
    @Test
    void aMemberSeesTheLiveSessionsAndTheirTradesOnly() {
        TradeStore store = new TradeStore(dataSource);
        HistoryController history = new HistoryController(dataSource, null, OIDC);
        long live = store.startSession("PAPER-1", "PAPER_LIVE", LocalDate.of(2026, 11, 6), "live", "member-test", "sha256:x",
                Map.of("member-test.v1.yaml", "sha256:a"), "member1");
        long signal = store.startSession("SIGNAL-1", "PAPER_LIVE", LocalDate.of(2026, 11, 6), "live", "member-test",
                "sha256:x", Map.of(), "member1");
        long replay = store.startSession("PAPER-1", "PAPER_REPLAY", LocalDate.of(2026, 11, 6), "replay-as-live:own-capture",
                "member-test", "sha256:x", Map.of(), "member1");
        long shadow = store.startSession("SHADOW-1", "PAPER_REPLAY", LocalDate.of(2026, 11, 6), "replay-as-live:own-capture",
                "member-test", "sha256:x", Map.of(), "member1");
        for (long id : List.of(live, signal, replay, shadow)) {                // none left RUNNING for the other tests
            store.endSession(id, "DONE", Map.of("net", 1234), null);
        }

        for (HistoryController.SessionFilter filter : java.util.Arrays.asList(null,
                new HistoryController.SessionFilter("member1", null, null, "PAPER_REPLAY", null))) {
            List<Map<String, Object>> rows = history.sessions(filter, MEMBER);
            assertThat(rows).extracting(r -> ((Number) r.get("id")).longValue()).contains(live)
                    .doesNotContain(signal, replay, shadow);
            assertThat(rows).allSatisfy(r -> assertThat(r).containsOnlyKeys("id", "account", "mode", "session_date"));
        }
        assertThat(history.positions(live, MEMBER).getStatusCode().value()).isEqualTo(200);
        for (long other : List.of(signal, replay, shadow, 987_654_321L)) {
            assertThat(history.positions(other, MEMBER).getStatusCode().value()).as("session %d", other).isEqualTo(404);
        }
        // the owner: everything, as before
        assertThat(history.sessions(null, OPERATOR)).extracting(r -> ((Number) r.get("id")).longValue())
                .contains(live, signal, replay, shadow);
        assertThat(history.sessions(null, OPERATOR).getFirst()).containsKeys("configs", "summary", "code_version");
        assertThat(history.positions(replay, OPERATOR).getStatusCode().value()).isEqualTo(200);
    }

    /** A session's page loads it by id, so a replay older than the newest 200 still has its strategy (and chart). */
    @Test
    void oneSessionIsFoundByIdEvenWhenNotAmongTheNewest200() {
        TradeStore store = new TradeStore(dataSource);
        HistoryController history = new HistoryController(dataSource, null, NO_LOGIN);
        long old = store.startSession("PAPER-1", "PAPER_REPLAY", LocalDate.of(2026, 10, 1), "replay-as-live:own-capture",
                "wall-break-rider", "sha256:x", Map.of(), "old1");
        for (int i = 0; i < 201; i++) {
            store.startSession("PAPER-1", "PAPER_REPLAY", DAY, "replay-as-live:bars", "filler", "sha256:x", Map.of(), "old1");
        }
        assertThat(history.sessions(null)).extracting(r -> ((Number) r.get("id")).longValue()).doesNotContain(old);

        Map<String, Object> row = history.session(old).getBody();
        assertThat(row).isNotNull();
        assertThat(row.get("strategy_id")).isEqualTo("wall-break-rider");
        assertThat(row.get("session_date")).isEqualTo("2026-10-01");
        assertThat(history.session(old + 100_000).getStatusCode().value()).isEqualTo(404);
    }

    /**
     * A trade's context comes from the session's own snapshot run, not another replay of the same date started right
     * after it (code review 5 Nov 2026); an approved entry with no stored position is a trade still open only in the
     * running session, never in a finished one.
     */
    @Test
    void tradeContextUsesTheSessionsOwnSnapshotRunAndNoPhantomOpenTrades() throws Exception {
        TradeStore store = new TradeStore(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        com.autotrade.md.store.Runs runs = new com.autotrade.md.store.Runs(dataSource);
        long id = session(store, "PAPER-7", "PAPER_REPLAY");
        long mine = runs.start("REPLAY_FEATURES", "test", "test", List.of(DAY), List.of("NIFTY"), "{}");
        long other = runs.start("REPLAY_FEATURES", "test", "test", List.of(DAY), List.of("NIFTY"), "{}");
        store.snapshotRun(id, mine);
        java.sql.Timestamp snap = java.sql.Timestamp.from(Instant.parse("2026-09-25T04:00:00Z"));     // 09:30 IST
        for (long run : List.of(mine, other)) {
            jdbc.update("insert into feat.snapshot (run_id, session_date, underlying, snap_time, phase, spot, features) "
                    + "values (?,?,?,?,?,?,?::jsonb)", run, DAY, "NIFTY", snap, "MORNING", 23000.0, "{\"run\": " + run + "}");
        }
        jdbc.update("insert into trade.position (session_id, underlying, option_side, symbol, opened_at, closed_at, stages, "
                        + "exit_reason, realised, costs, net, strategy_id, quantity, lot_size, average_cost, average_exit) "
                        + "values (?,?,?,?,?,?,'{}'::text[],?,?,?,?,?,?,?,?,?)", id, "NIFTY", "CE", "NIFTY 23000 CE",
                java.sql.Timestamp.from(Instant.parse("2026-09-25T04:00:30Z")), java.sql.Timestamp.from(Instant.parse("2026-09-25T04:30:30Z")),
                "TARGET", 3250.0, 120.0, 3130.0, "early-confirm-runner", 65, 65, 100.0, 150.0);
        store.candidate(id, "early-confirm-runner", "NIFTY", Instant.parse("2026-09-25T04:00:25Z"), "ENTER", "CE", "BREAKOUT",
                true, null, 23000, Map.of("near_level", true));
        // approved but never stored as a position: in a finished session, not a trade
        store.candidate(id, "early-confirm-runner", "NIFTY", Instant.parse("2026-09-25T06:00:00Z"), "ENTER", "PE", "BREAKDOWN",
                true, null, 23100, Map.of());

        HistoryController history = new HistoryController(dataSource, null, NO_LOGIN);
        List<Map<String, Object>> trades = history.tradeContext(id);
        assertThat(trades).singleElement().satisfies(t -> {
            assertThat(t.get("entry_reason")).isEqualTo("BREAKOUT");
            assertThat(t.get("entry_features").toString().replace(" ", "")).isEqualTo("{\"run\":" + mine + "}");
        });
        Map<String, Object> at = history.at(id, "NIFTY", "09:31:00");
        assertThat(((Map<?, ?>) at.get("snapshot")).get("features").toString().replace(" ", "")).isEqualTo("{\"run\":" + mine + "}");
        assertThat(at.get("open")).asList().singleElement().satisfies(o -> assertThat(((Map<?, ?>) o).get("quantity")).isEqualTo(65L));
        assertThat(history.badTime(new java.time.format.DateTimeParseException("x", "9:61", 0)).getStatusCode().value()).isEqualTo(400);
    }
}
