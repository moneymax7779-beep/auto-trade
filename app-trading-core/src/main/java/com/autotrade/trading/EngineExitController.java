package com.autotrade.trading;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The engine's own exits from the admin exit desk (plan 2026-11-20 phase C; plan 2026-11-23 A1, user 23 Nov 2026: "As a
 * owner I should be able exit all the open positions, live/paper"). Operator only (SecurityConfig: /api/**).
 * <ul>
 *   <li>{@code POST /api/sessions/{session}/positions/exit}: one strategy's position on one index in one running live
 *       session (PAPER-1, or the signal source SIGNAL-1), through its order manager as the strategy's own exit would.</li>
 *   <li>{@code POST /api/sessions/exit-everything}: every position of every running live session; with
 *       {@code stopEntries} each session's GLOBAL kill switch first (review 23 Nov 2026: without it a strategy could enter
 *       again on its next signal, and SIGNAL-1 would send that entry to every follower: "everything" would not stay flat).</li>
 *   <li>{@code POST /api/sessions/{session}/entries/release}: lets that session enter again (its GLOBAL switch).</li>
 * </ul>
 * A SIGNAL-1 close is published as an EXIT decision, so every follower holding the trade exits too (decision 2 of the
 * plan: "exit all" means everyone out); a PAPER-1 close touches PAPER-1 (and DEMO-1, which mirrors it) only. Each call
 * needs a reason, is logged and audited (ENGINE_EXIT, ENGINE_EXIT_EVERYTHING); the exit reason on the positions is
 * ADMIN_EXIT. Replays are never touched.
 */
@RestController
@RequestMapping("/api/sessions")
class EngineExitController {

    private static final Logger log = LoggerFactory.getLogger(EngineExitController.class);
    static final String REASON = "ADMIN_EXIT";
    static final String EVERYTHING = "EXIT EVERYTHING";
    private static final JsonMapper JSON = new JsonMapper();

    private final SessionRunner runner;
    private final JdbcTemplate jdbc;

    EngineExitController(SessionRunner runner, DataSource dataSource) {
        this.runner = runner;
        this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
    }

    record PositionExit(String strategy, String underlying, String reason) {
    }

    /**
     * @param stopEntries   engage each live session's GLOBAL kill switch before its exits (null: false)
     * @param exitPositions exit every position (null: true); false with stopEntries only stops the sessions' new entries
     */
    record Everything(String reason, String confirm, Boolean stopEntries, Boolean exitPositions) {
    }

    record Release(String reason) {
    }

    @PostMapping("/{session}/positions/exit")
    Map<String, Object> exitPosition(@PathVariable long session, @RequestBody PositionExit body, Principal principal) {
        String reason = reason(body == null ? null : body.reason());
        if (body.strategy() == null || body.strategy().isBlank() || body.underlying() == null || body.underlying().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "strategy and underlying are required");
        }
        TradingSession s = live().stream().filter(x -> session == sessionId(x)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no running live session " + session));
        String account = String.valueOf(s.status().get("account"));
        boolean started = s.exitPosition(body.strategy(), body.underlying(), REASON);
        String actor = actor(principal);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session);
        out.put("account", account);
        out.put("strategy", body.strategy());
        out.put("underlying", body.underlying());
        out.put("outcome", started ? "EXITING" : "NOT_HELD");
        if (started) {
            log.warn("ENGINE EXIT by {}: {} {} {} on {} ({})", actor, account, body.strategy(), body.underlying(), session,
                    reason);
            audit("ENGINE_EXIT", actor, out, reason);
        }
        return out;
    }

    @PostMapping("/exit-everything")
    Map<String, Object> exitEverything(@RequestBody Everything body, Principal principal) {
        String reason = reason(body == null ? null : body.reason());
        if (!EVERYTHING.equals(body.confirm() == null ? null : body.confirm().trim().toUpperCase(java.util.Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirm with \"" + EVERYTHING + "\"");
        }
        String actor = actor(principal);
        List<Map<String, Object>> sessions = new ArrayList<>();
        boolean stop = Boolean.TRUE.equals(body.stopEntries());
        for (TradingSession s : live()) {
            Map<String, Object> status = s.status();
            Object open = status.getOrDefault("openPositions", List.of());
            int count = open instanceof List<?> l ? l.size() : 0;
            if (stop) {
                s.engageKillSwitch("GLOBAL", "EXIT EVERYTHING by " + actor + ": " + reason, actor);   // before the exits
            }
            boolean exit = !Boolean.FALSE.equals(body.exitPositions());
            if (exit) {
                s.exitAll(REASON);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("session", sessionId(s));
            row.put("account", status.get("account"));
            row.put("positions", exit ? count : 0);
            row.put("entriesStopped", stop);
            sessions.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", sessions);
        log.warn("ENGINE EXIT EVERYTHING by {}: {} ({})", actor, sessions, reason);
        audit("ENGINE_EXIT_EVERYTHING", actor, out, reason);
        return out;
    }

    /** Lets one running live session enter again: its GLOBAL kill switch released (409 when it is not engaged). */
    @PostMapping("/{session}/entries/release")
    Map<String, Object> releaseEntries(@PathVariable long session, @RequestBody(required = false) Release body,
                                       Principal principal) {
        TradingSession s = live().stream().filter(x -> session == sessionId(x)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no running live session " + session));
        String actor = actor(principal);
        TradingSession.Release released = s.releaseKillSwitch("GLOBAL", actor);
        if (released.released().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, released.refused() != null ? released.refused()
                    : "new entries are not stopped on this session");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session);
        out.put("account", s.status().get("account"));
        out.put("released", released.released());
        log.warn("ENGINE ENTRIES RESUMED by {} on {}", actor, out.get("account"));
        audit("ENGINE_ENTRIES_RESUMED", actor, out, body == null || body.reason() == null ? "" : body.reason());
        return out;
    }

    /** The running live sessions (PAPER-1 and its signal source); a replay is never touched. */
    private List<TradingSession> live() {
        return runner.sessions().stream().filter(s -> "PAPER_LIVE".equals(String.valueOf(s.status().get("mode")))).toList();
    }

    private static long sessionId(TradingSession s) {
        Object id = s.status().get("session");
        return id instanceof Number n ? n.longValue() : -1;
    }

    private static String reason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 3 || r.length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason: 3 to 300 characters (it is audited)");
        }
        return r;
    }

    private static String actor(Principal principal) {
        return principal == null ? "operator" : principal.getName();
    }

    /** The audit row (exec.audit_log, no account: the engine's); a failed write never stops the exit. */
    private void audit(String action, String actor, Map<String, Object> detail, String reason) {
        if (jdbc == null) {
            return;
        }
        try {
            Map<String, Object> d = new LinkedHashMap<>(detail);
            d.put("reason", reason);
            jdbc.update("insert into exec.audit_log (actor, action, detail) values (?, ?, ?::jsonb)", actor, action,
                    JSON.writeValueAsString(d));
        } catch (RuntimeException e) {
            log.error("engine exit audit not written: {}", e.getMessage());
        }
    }
}
