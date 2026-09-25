package com.autotrade.features.time;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureConfig;

/** Session phases, trading-day counting and expiry timing for one exchange calendar. */
public final class SessionClock {

    private final FeatureConfig config;

    public SessionClock(FeatureConfig config) {
        this.config = config;
    }

    public SessionPhase phase(Instant instant) {
        LocalTime time = instant.atZone(MarketTime.IST).toLocalTime();
        if (time.isBefore(config.marketOpen())) {
            return SessionPhase.PRE_OPEN;
        }
        if (time.isBefore(config.casStart())) {
            for (Map.Entry<String, LocalTime[]> window : config.sessionWindows().entrySet()) {
                LocalTime[] range = window.getValue();
                if (!time.isBefore(range[0]) && time.isBefore(range[1])) {
                    return SessionPhase.valueOf(window.getKey());
                }
            }
            return SessionPhase.LATE;
        }
        if (time.isBefore(config.casReferenceEnd())) {
            return SessionPhase.CAS_REFERENCE;
        }
        if (time.isBefore(config.casMarketAndLimitEnd())) {
            return SessionPhase.CAS_MARKET_AND_LIMIT;
        }
        if (time.isBefore(config.casLimitOnlyEnd())) {
            return SessionPhase.CAS_LIMIT_ONLY;
        }
        if (time.isBefore(config.casEnd())) {
            return SessionPhase.CAS_MATCHING;
        }
        if (time.isBefore(config.derivativesClose())) {
            return SessionPhase.DERIVATIVES_ONLY;
        }
        return SessionPhase.CLOSED;
    }

    public boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !config.holidays().contains(date);
    }

    /** Trading days from {@code session} to {@code expiry}: 0 on expiry day. */
    public int tradingDaysToExpiry(LocalDate session, LocalDate expiry) {
        int days = 0;
        for (LocalDate date = session; date.isBefore(expiry); date = date.plusDays(1)) {
            if (isTradingDay(date.plusDays(1))) {
                days++;
            }
        }
        return days;
    }

    /** Year fraction to the expiry close (calendar time, as option models use), never below one minute. */
    public double yearsToExpiry(Instant now, LocalDate expiry) {
        Instant close = expiry.atTime(config.expiryClose()).atZone(MarketTime.IST).toInstant();
        double seconds = Math.max(60.0, close.getEpochSecond() - now.getEpochSecond());
        return seconds / (365.0 * 24 * 3600);
    }

    public long minutesToExpiryClose(Instant now, LocalDate expiry) {
        Instant close = expiry.atTime(config.expiryClose()).atZone(MarketTime.IST).toInstant();
        return Math.max(0, (close.getEpochSecond() - now.getEpochSecond()) / 60);
    }

    /** Minutes of continuous trading left today (used for the remaining expected move). */
    public long continuousMinutesLeft(Instant now) {
        LocalTime time = now.atZone(MarketTime.IST).toLocalTime();
        if (!time.isBefore(config.continuousClose())) {
            return 0;
        }
        LocalTime from = time.isBefore(config.marketOpen()) ? config.marketOpen() : time;
        return Duration.between(from, config.continuousClose()).toMinutes();
    }

    public Instant sessionOpen(LocalDate session) {
        return session.atTime(config.marketOpen()).atZone(MarketTime.IST).toInstant();
    }
}
