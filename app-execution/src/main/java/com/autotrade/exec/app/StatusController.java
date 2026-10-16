package com.autotrade.exec.app;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/execution/status}: this worker and each follower account's last turn (lease, state, result). */
@RestController
class StatusController {

    private final ExecutionService service;
    private final ExecutionProperties properties;

    StatusController(ExecutionService service, ExecutionProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/api/execution/status")
    Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worker", properties.workerId());
        out.put("copyGroup", properties.copyGroup());
        out.put("accounts", service.status());
        return out;
    }
}
