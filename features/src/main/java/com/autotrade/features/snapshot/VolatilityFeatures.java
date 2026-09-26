package com.autotrade.features.snapshot;

/**
 * Volatility context (v3). Percentiles are 0..100 against earlier sessions:
 *
 * <ul>
 *   <li>{@code atmIvPercentile}: ATM IV against earlier sessions' ATM IV at the same minute, using only
 *       sessions of the same kind (expiry day or not), because expiry-day IV is not comparable.</li>
 *   <li>{@code realizedVol}: annualised standard deviation of 1-minute log returns of spot over the
 *       realised window; its percentile and {@code atrPercentile} (ATR1m) compare with earlier
 *       sessions at the same minute. {@code rvIvRatio} = realised / ATM IV.</li>
 *   <li>India VIX (market-wide, NIFTY-derived; never a direction signal): level, change over 5 and
 *       15 minutes and since the previous close, and percentiles of the level among the last 60 and
 *       252 daily closes. {@code vixSource} is TICK (live feed), MINUTE (one-minute bars) or NONE.</li>
 * </ul>
 */
public record VolatilityFeatures(
        double atmIvPercentile,
        int ivHistorySessions,
        String ivTrend,
        double realizedVol,
        double realizedVolPercentile,
        double rvIvRatio,
        double atrPercentile,
        int spotHistorySessions,
        double vix,
        double vixChange5m,
        double vixChange15m,
        double vixChangeDay,
        double vixPercentile60d,
        double vixPercentile252d,
        int vixHistoryDays,
        double secondsSinceVix,
        String vixSource) {

    public static final VolatilityFeatures EMPTY = new VolatilityFeatures(Double.NaN, 0, "UNKNOWN", Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, 0, Double.NaN, "NONE");
}
