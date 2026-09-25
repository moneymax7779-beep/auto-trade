package com.autotrade.features.snapshot;

/**
 * Nearest-expiry index future. Momentum in points; {@code *Norm} values are divided by the futures
 * 1-minute ATR; {@code oiChangeDay} is against the first OI seen in the session.
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
        double atr1m) {
}
