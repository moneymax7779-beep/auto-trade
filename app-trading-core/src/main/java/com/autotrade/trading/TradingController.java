package com.autotrade.trading;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator API, bound to loopback only (server.address) until authentication arrives in Phase 5.
 * Kill switches block new entries and adds; exit-all closes every position.
 */
@RestController
@RequestMapping("/api")
class TradingController {

    private final SessionRunner runner;

    TradingController(SessionRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/status")
    ResponseEntity<Map<String, Object>> status() {
        TradingSession session = runner.current();
        Map<String, Object> status = new LinkedHashMap<>();
        if (session == null) {
            status.put("status", "NO_SESSION");
        } else {
            status.putAll(session.status());
        }
        status.put("schedule", runner.schedule());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/orders")
    ResponseEntity<List<Map<String, Object>>> orders() {
        TradingSession session = runner.current();
        return session == null ? ResponseEntity.ok(List.of()) : ResponseEntity.ok(session.orders());
    }

    @PostMapping("/kill")
    ResponseEntity<Map<String, Object>> kill(@RequestParam(defaultValue = "GLOBAL") String scope,
                                             @RequestParam(defaultValue = "operator") String reason) {
        TradingSession session = runner.current();
        if (session == null) {
            return ResponseEntity.status(409).body(Map.of("error", "no session"));
        }
        session.engageKillSwitch(scope, reason);
        return ResponseEntity.ok(session.status());
    }

    /**
     * Releases a kill switch: {@code scope} ALL (the UI) releases every engaged switch, e.g. ACCOUNT:PAPER-1 set by
     * the daily loss limit; a scope that is not engaged is a 409, never a silent no-op. Note: a daily-loss switch
     * engages again at the next entry while the day's loss is still beyond the limit.
     */
    @PostMapping("/kill/release")
    ResponseEntity<Map<String, Object>> release(@RequestParam(defaultValue = "ALL") String scope) {
        TradingSession session = runner.current();
        if (session == null) {
            return ResponseEntity.status(409).body(Map.of("error", "no session"));
        }
        TradingSession.Release release = session.releaseKillSwitch(scope);
        if (release.released().isEmpty()) {
            String error = release.refused() != null ? release.refused() : "no engaged kill switch for scope " + scope;
            return ResponseEntity.status(409).body(Map.of("error", error,
                    "engaged", session.status().getOrDefault("killSwitches", java.util.List.of())));
        }
        return ResponseEntity.ok(session.status());
    }

    /** Closes every position, then stops the session (the service keeps serving status). */
    @PostMapping("/stop")
    ResponseEntity<Map<String, Object>> stop() {
        TradingSession session = runner.current();
        if (session == null) {
            return ResponseEntity.status(409).body(Map.of("error", "no session"));
        }
        session.exitAll("OPERATOR_STOP");
        session.stop();
        return ResponseEntity.ok(session.status());
    }

    @PostMapping("/exit-all")
    ResponseEntity<Map<String, Object>> exitAll(@RequestParam(defaultValue = "OPERATOR") String reason) {
        TradingSession session = runner.current();
        if (session == null) {
            return ResponseEntity.status(409).body(Map.of("error", "no session"));
        }
        session.exitAll(reason);
        return ResponseEntity.ok(session.status());
    }
}
