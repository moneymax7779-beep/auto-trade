package com.autotrade.features.snapshot;

/**
 * How the ATM options themselves behave (v3): premium momentum (mid-price change in percent), a new
 * high of the premium within the lookback, one-minute traded volume and its expansion over the
 * preceding baseline rate, the straddle's compression ending, and IV one strike in and out of the
 * money (fractions).
 *
 * <p>{@code straddleCompressionEnded}: the straddle fell over the lookback up to the recent window,
 * then stopped falling within it (the design's "straddle stops falling").
 */
public record PremiumFeatures(
        double ceChange1mPct,
        double ceChange3mPct,
        boolean ceNewHigh,
        double ceVolume1m,
        double ceVolumeExpansion,
        double peChange1mPct,
        double peChange3mPct,
        boolean peNewHigh,
        double peVolume1m,
        double peVolumeExpansion,
        double straddleChangeLookback,
        double straddleChangeRecent,
        boolean straddleCompressionEnded,
        double itmCeIv,
        double otmCeIv,
        double itmPeIv,
        double otmPeIv) {

    public static final PremiumFeatures EMPTY = new PremiumFeatures(Double.NaN, Double.NaN, false, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN);
}
