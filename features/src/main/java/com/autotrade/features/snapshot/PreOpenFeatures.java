package com.autotrade.features.snapshot;

/**
 * The pre-open call auction (09:00-09:08 IST) of the index's constituents, from the broker's auction feed (features
 * v14, {@code preopen.enabled}): each stock's indicative equilibrium price (IEP) against its previous close and its
 * unmatched quantity, weighted by index weight. Point-in-time (only auction ticks received so far); fixed once the
 * auction ends.
 * <ul>
 * <li>{@code preOpenReturnPct}: weighted mean of IEP / previous close - 1, in %;</li>
 * <li>{@code preOpenBreadth}: weighted share of stocks indicated above minus below their previous close (-100..100);</li>
 * <li>{@code preOpenImbalance}: weighted share with buyers in excess minus sellers in excess (-100..100);</li>
 * <li>{@code preOpenCoveragePct}: index weight with an auction quote and a previous close, in %.</li>
 * </ul>
 */
public record PreOpenFeatures(double preOpenReturnPct, double preOpenBreadth, double preOpenImbalance,
                              double preOpenCoveragePct) {

    public static final PreOpenFeatures EMPTY = new PreOpenFeatures(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
}
