package com.autotrade.trading;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.time.MarketTime;

/** Preview of the daily P&amp;L summary that is sent to Telegram after each session (read-only; sends nothing). */
@RestController
class DailySummaryController {

    private final TradingProperties properties;
    private final DataSource target;
    private final AlertService alerts;

    DailySummaryController(TradingProperties properties, DataSource target, AlertService alerts) {
        this.properties = properties;
        this.target = target;
        this.alerts = alerts;
    }

    @GetMapping("/api/daily-summary")
    Map<String, Object> preview(@RequestParam(required = false) String date) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("telegramConfigured", alerts.telegramConfigured());
        out.put("groupConfigured", alerts.telegramGroupConfigured());
        out.put("text", new DailySummary(target).build(properties.trading().account(), day));
        return out;
    }
}
