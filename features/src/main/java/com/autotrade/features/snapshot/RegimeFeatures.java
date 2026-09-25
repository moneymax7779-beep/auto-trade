package com.autotrade.features.snapshot;

/**
 * Calendar and volatility context. Expected moves are one standard deviation, in index points:
 * to expiry, per trading day, and for the rest of today. {@code expectedMoveMethod} says how they
 * were derived (STRADDLE, IV, or v1's IV_TRADING_MINUTES).
 */
public record RegimeFeatures(
        String expiry,
        int dteTradingDays,
        int dteCalendarDays,
        long minutesToExpiryClose,
        double expectedMoveToExpiry,
        double expectedMoveDaily,
        double expectedMoveRemaining,
        String expectedMoveMethod,
        double dayRangeVsExpected) {
}
