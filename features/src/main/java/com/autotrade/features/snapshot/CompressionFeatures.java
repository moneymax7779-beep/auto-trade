package com.autotrade.features.snapshot;

/**
 * Compression before expansion (features v6).
 *
 * <ul>
 *   <li>{@code rangePts}: high − low of spot over the compression window (30 min).</li>
 *   <li>{@code rangeVsSession}: that range over the median of the same trailing range at every earlier
 *       minute today (below 1 = tighter than usual today; NaN until enough history).</li>
 *   <li>{@code emaGapAtr}: |EMA9 − EMA20| in ATR3m (small = EMAs converged).</li>
 *   <li>{@code volumeRateRatio}: futures volume per minute over the recent window (15 min) over the
 *       rate in the prior window (30 min before it); below 1 = volume drying up.</li>
 *   <li>{@code straddleChangePct}: ATM straddle change over 10 minutes, percent (negative = decaying).</li>
 *   <li>{@code vwapCrosses}: 1-minute closes crossing the VWAP proxy within the window (chop).</li>
 * </ul>
 */
public record CompressionFeatures(
        double rangePts,
        double rangeVsSession,
        double emaGapAtr,
        double volumeRateRatio,
        double straddleChangePct,
        int vwapCrosses) {

    public static final CompressionFeatures EMPTY = new CompressionFeatures(Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, 0);
}
