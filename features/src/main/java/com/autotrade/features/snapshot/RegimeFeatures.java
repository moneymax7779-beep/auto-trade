package com.autotrade.features.snapshot;

/** Calendar and volatility context. Expected moves are one-standard-deviation, in index points. */
public record RegimeFeatures(
        String expiry,
        int dteTradingDays,
        int dteCalendarDays,
        long minutesToExpiryClose,
        double expectedMoveDaily,
        double expectedMoveRemaining,
        double dayRangeVsExpected) {
}
