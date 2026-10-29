package com.autotrade.exec.app;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/execution/status}: this worker, its heartbeat ({@code lastTick}, {@code stale}) and each follower
 * account's last turn (route, lease, state, result). Through the UI (trading-core always sets the user header) the
 * accounts are only the caller's tenants' (29 Oct 2026: a family member never sees another's accounts); a call on the
 * host itself without the header (the watchdog, the runbook's curl; the service binds 127.0.0.1) sees every account.
 */
@RestController
class StatusController {

    private final ExecutionService service;
    private final ExecutionProperties properties;
    private final Access access;

    StatusController(ExecutionService service, ExecutionProperties properties, Access access) {
        this.service = service;
        this.properties = properties;
        this.access = access;
    }

    @GetMapping("/api/execution/status")
    Map<String, Object> status(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worker", properties.workerId());
        out.put("copyGroup", properties.copyGroup());
        out.put("day", service.day().toString());
        java.time.Instant last = service.lastTick();
        long age = last == null ? -1 : java.time.Duration.between(last, java.time.Instant.now()).toSeconds();
        out.put("lastTick", last == null ? null : last.toString());
        // the watchdog's signal: no finished tick for 10 poll intervals (or none yet)
        out.put("stale", last == null || age * 1000 > 10 * properties.pollMs());
        if (subject == null) {
            out.put("accounts", service.status());
        } else {
            Access.User user = access.user(subject);
            out.put("accounts", service.status().stream().filter(s -> user.sees(s.tenantId())).toList());
        }
        return out;
    }
}
