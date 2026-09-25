package com.autotrade.md.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LoadManifest(
        long id,
        String kind,
        LocalDate sessionDate,
        String underlying,
        String sourceName,
        List<String> datasets,
        String status,
        String toolVersion,
        Instant startedAt,
        Instant finishedAt,
        String loadedCountsJson,
        String error) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
