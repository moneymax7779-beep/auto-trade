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

    LevelsController(LevelsService levels) {
        this.levels = levels;
    }

    @GetMapping("/api/levels")
    Map<String, Object> levels(@RequestParam(required = false) String date, @RequestParam String underlying) {
        LocalDate day = date == null ? LocalDate.now(MarketTime.IST) : LocalDate.parse(date);
        return levels.levels(day, underlying.toUpperCase(java.util.Locale.ROOT));
    }
}
