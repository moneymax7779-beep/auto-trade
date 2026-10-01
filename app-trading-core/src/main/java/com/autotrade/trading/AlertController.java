package com.autotrade.trading;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Active alerts and the latest messages, for the Live page banner. */
@RestController
class AlertController {

    private final AlertService alerts;

    AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/api/alerts")
    Map<String, Object> alerts() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("telegram", alerts.telegramConfigured());
        body.put("active", alerts.active());
        body.put("recent", alerts.recent());
        return body;
    }
}
