package com.autotrade.features.snapshot;

/**
 * Nearest-expiry index future. Momentum in points; {@code *Norm} values are divided by the futures
 * 1-minute ATR; {@code oiChangeDay} is against the first OI seen in the session.
 *
 * <p>{@code rvolTod} compares the current slot with the same slot in previous sessions; it runs high
 * all through a futures expiry week (rollover volume), so {@code daysToExpiry} is given alongside.
 * {@code rvolSession} compares the current slot with today's earlier slots, which removes day-level
 * shifts such as rollover and shows participation accelerating within the session.
 */
public record FuturesFeatures(
        String symbol,
        double price,
        double vwap,
        double momentum30s,
        double momentum1m,
        double momentum3m,
        double momentum1mNorm,
        double acceleration1m,
        double basis,
        double basisChange1m,
        double basisChange3m,
        double oi,
        double oiChange3m,
        double oiChangeDay,
        String oiState,
        long volumeLastMinute,
        double rvolTod,
        double rvolSlope,
        int rvolHistorySessions,
        double rvolSession,
        int daysToExpiry,
        double atr1m) {
}
