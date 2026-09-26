package com.autotrade.features.snapshot;

/**
 * Order-book imbalance (v3): (bid quantity − ask quantity) / (bid + ask), −1..+1, for the nearest
 * future and the ATM call and put. Options use the feed's total pending quantities, else the five
 * depth levels; futures only have book totals on feeds that carry them (Upstox), else NaN.
 *
 * <p>Static depth is easily cancelled, so persistence matters more than a single value: the min and
 * max over the short and long windows (10 s and 20 s in features v3) show whether the imbalance
 * stayed on one side. {@code *ChangeLong} is the change over the long window.
 *
 * <p>NSE option books carry a structural bid bias (on 25 Sep 2026 the ATM call and put both sat at
 * +0.5 to +0.65 all day), so an option's absolute imbalance says little; compare the call with the put.
 */
public record BookFeatures(
        double futures,
        double futuresMinShort,
        double futuresMaxShort,
        double futuresMinLong,
        double futuresMaxLong,
        double futuresChangeLong,
        double atmCe,
        double atmCeMinShort,
        double atmCeMaxShort,
        double atmCeMinLong,
        double atmCeMaxLong,
        double atmCeChangeLong,
        double atmPe,
        double atmPeMinShort,
        double atmPeMaxShort,
        double atmPeMinLong,
        double atmPeMaxLong,
        double atmPeChangeLong,
        String optionSource) {

    public static final BookFeatures EMPTY = new BookFeatures(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "NONE");
}
