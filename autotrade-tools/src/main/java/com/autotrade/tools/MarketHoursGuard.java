package com.autotrade.tools;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import com.autotrade.core.time.MarketTime;

/**
 * Keeps heavy reads off the source database while it is capturing a live session.
 * Holidays are not known here; {@code --force} overrides.
 */
public final class MarketHoursGuard {

    static final LocalTime BLOCK_FROM = LocalTime.of(9, 0);
    static final LocalTime BLOCK_UNTIL = LocalTime.of(15, 50);

    private MarketHoursGuard() {
    }

    public static boolean isBlocked(ZonedDateTime now) {
        ZonedDateTime ist = now.withZoneSameInstant(MarketTime.IST);
        DayOfWeek day = ist.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = ist.toLocalTime();
        return !time.isBefore(BLOCK_FROM) && time.isBefore(BLOCK_UNTIL);
    }
}
