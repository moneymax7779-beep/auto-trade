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

    /** A paced replay's pause / speed state (409 when no paced replay runs). */
    @GetMapping("/replay-control")
    ResponseEntity<Map<String, Object>> replayControl() {
        TradingSession session = runner.current();
        if (session == null || !"PAPER_REPLAY".equals(String.valueOf(session.status().get("mode")))) {
            return ResponseEntity.status(409).body(Map.of("error", "no replay running"));
        }
        if (!runner.replayControl().attached()) {       // an unpaced replay has no clock to pause or re-pace
            return ResponseEntity.status(409).body(Map.of("error", "this replay runs unpaced (speed 0): start it with a speed to control it"));
        }
        return ResponseEntity.ok(runner.replayControl().state());
    }

    /**
     * Controls a paced replay (user, 5 Nov 2026): {@code pause}, {@code resume}, {@code speed} ({@code value} market
     * seconds per second), {@code until} (run on, pause at {@code time} HH:mm[:ss] IST), {@code next-entry},
     * {@code next-exit} (optionally {@code symbol}: that contract's exit) and {@code next-trade}: run on and pause after
     * that fill. A pause stops before the next market event, so what is shown is the state at that moment.
     */
    @PostMapping("/replay-control")
    ResponseEntity<Map<String, Object>> replayControl(@RequestParam String action, @RequestParam(required = false) Double value,
                                                      @RequestParam(required = false) String time,
                                                      @RequestParam(required = false) String symbol) {
        TradingSession session = runner.current();
        if (session == null || !"PAPER_REPLAY".equals(String.valueOf(session.status().get("mode")))) {
            return ResponseEntity.status(409).body(Map.of("error", "no replay running"));
        }
        if (!runner.replayControl().attached()) {       // an unpaced replay has no clock to pause or re-pace
            return ResponseEntity.status(409).body(Map.of("error", "this replay runs unpaced (speed 0): start it with a speed to control it"));
        }
        com.autotrade.md.live.ReplayControl c = runner.replayControl();
        switch (action) {
            case "pause" -> c.pause();
            case "resume" -> c.resume();
            case "speed" -> {
                if (value == null || !(value > 0) || value > 1000) {
                    return ResponseEntity.badRequest().body(Map.of("error", "value: market seconds per second, 0-1000"));
                }
                c.speed(value);
            }
            case "until" -> {
                java.time.LocalDate day = java.time.LocalDate.parse(String.valueOf(session.status().get("date")));
                java.time.LocalTime at;
                try {
                    at = java.time.LocalTime.parse(time == null ? "" : time);
                } catch (java.time.format.DateTimeParseException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "time: HH:mm or HH:mm:ss"));
                }
                c.pauseAt(day.atTime(at).atZone(com.autotrade.core.time.MarketTime.IST).toInstant());
                c.resume();
            }
            case "next-entry", "next-exit", "next-trade" -> {
                c.pauseOn(switch (action) {
                    case "next-entry" -> com.autotrade.md.live.ReplayControl.PauseOn.ENTRY;
                    case "next-exit" -> com.autotrade.md.live.ReplayControl.PauseOn.EXIT;
                    default -> com.autotrade.md.live.ReplayControl.PauseOn.TRADE;
                }, symbol);
                c.resume();
            }
            default -> {
                return ResponseEntity.badRequest().body(Map.of("error", "action: pause, resume, speed, until, next-entry, "
                        + "next-exit, next-trade"));
            }
        }
        return ResponseEntity.ok(c.state());
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
