package com.autotrade.trading.activity;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User activity (plan docs/plans/2026-11-23-user-activity-logging-plan.md):
 * <ul>
 *   <li>{@code POST /api/presence}: every signed-in page's beat (each user; a member API);</li>
 *   <li>{@code GET /api/me/devices}: the user's own sign-ins (each user; a member API);</li>
 *   <li>{@code GET /api/activity}, {@code GET /api/activity/sign-ins}: everyone's (the operator only, by the
 *       sign-in chain's {@code /api/**} rule).</li>
 * </ul>
 */
@RestController
class ActivityController {

    /** A tab's beat: seconds on screen and in use since its last beat, as the page counted them. */
    record Presence(String tabId, Integer visible, Integer active, String path, Boolean standalone, Boolean closing) {
    }

    private final ActivityWeb web;
    private final ActivityReport report;

    ActivityController(ActivityWeb web, ActivityReport report) {
        this.web = web;
        this.report = report;
    }

    @PostMapping("/api/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void presence(@RequestBody(required = false) Presence p, HttpServletRequest request, Principal principal) {
        ActivityRecorder recorder = web.recorder();
        if (p == null || principal == null || !recorder.enabled()) {
            return;
        }
        UUID tab;
        try {
            tab = UUID.fromString(p.tabId());
        } catch (RuntimeException e) {
            return;                                                // not a tab id: ignored, never an error page
        }
        recorder.beat(web.key(request), principal.getName(), tab, p.visible() == null ? 0 : p.visible(),
                p.active() == null ? 0 : p.active(), p.path(), Boolean.TRUE.equals(p.standalone()),
                Boolean.TRUE.equals(p.closing()));
    }

    @GetMapping("/api/activity")
    Map<String, Object> overview(@RequestParam(defaultValue = "30") int days) {
        if (!web.recorder().enabled()) {
            return Map.of("enabled", false, "users", List.of());
        }
        Map<String, Object> out = new LinkedHashMap<>(report.overview(days));
        out.put("enabled", true);
        out.put("recorder", web.recorder().counters());
        return out;
    }

    @GetMapping("/api/activity/sign-ins")
    List<Map<String, Object>> signIns(@RequestParam String subject, @RequestParam(defaultValue = "30") int days) {
        return web.recorder().enabled() ? report.signIns(subject, days, true) : List.of();
    }

    @GetMapping("/api/me/devices")
    Map<String, Object> devices(HttpServletRequest request, Principal principal) {
        if (!web.recorder().enabled() || principal == null) {
            return Map.of("enabled", false, "signIns", List.of());
        }
        return Map.of("enabled", true, "signIns", report.devices(principal.getName(), web.key(request)));
    }
}
