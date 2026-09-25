package com.autotrade.tools.clone;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record CloneResult(
        long manifestId,
        LocalDate sessionDate,
        String underlying,
        Map<String, Long> loadedCounts,
        List<Long> superseded,
        long spoolBytes,
        Duration elapsed) {
}
