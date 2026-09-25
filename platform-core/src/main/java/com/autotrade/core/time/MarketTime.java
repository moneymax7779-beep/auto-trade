package com.autotrade.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Indian market time helpers. Every stored timestamp is an {@link Instant}; IST is for display and session boundaries. */
public final class MarketTime {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private MarketTime() {
    }

    /** Interprets a zone-less timestamp that was recorded in IST wall-clock time. */
    public static Instant fromIstLocal(LocalDateTime istLocal) {
        return istLocal.atZone(IST).toInstant();
    }

    public static LocalDate sessionDate(Instant instant) {
        return instant.atZone(IST).toLocalDate();
    }

    public static Instant sessionStart(LocalDate sessionDate) {
        return sessionDate.atStartOfDay(IST).toInstant();
    }

    public static Instant sessionEnd(LocalDate sessionDate) {
        return sessionDate.plusDays(1).atStartOfDay(IST).toInstant();
    }
}
