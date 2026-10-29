package com.autotrade.trading;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autotrade.core.time.MarketTime;

/** Price levels with the minute each became knowable, for the charts (display only). */
@RestController
class LevelsController {

    private final LevelsService levels;
    private final ChartDb db;
    /** Finished days already read (from md.chart_day_cache or computed), by day and index. */
    private final Map<String, Map<String, Object>> finished = new java.util.concurrent.ConcurrentHashMap<>();

    LevelsController(LevelsService levels, ChartDb db) {
        this.levels = levels;
        this.db = db;
    }

    /** A finished day's levels are computed once and kept in md.chart_day_cache (29 Oct 2026: 1.4 s after each restart). */
    @GetMapping("/api/levels")
    Map<String, Object> levels(@RequestParam(required = false) String date, @RequestParam String underlying) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        String u = underlying.toUpperCase(java.util.Locale.ROOT);
        if (!ChartDb.finished(day)) {
            return levels.levels(day, u);
        }
        return finished.computeIfAbsent(day + "|" + u, k -> {
            Map<String, Object> kept = db.loadJson(KIND, day, u);
            if (kept != null) {
                return kept;
            }
            Map<String, Object> value = levels.levels(day, u);
            db.saveJson(KIND, day, u, value);
            return value;
        });
    }

    private static final String KIND = "levels";
}
