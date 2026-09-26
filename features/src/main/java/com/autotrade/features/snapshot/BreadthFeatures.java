package com.autotrade.features.snapshot;

/**
 * Index-weighted constituent participation. {@code momentumBreadth} and {@code dayBreadth} are on
 * −100..+100; {@code topConcentration} is the share of the dominant side's contribution coming from
 * the top N stocks (0..1). {@code moverBreadth} (features v5) is the design's weighted bullish minus
 * bearish contribution: index weight of stocks up over the window minus weight of stocks down, as a
 * share of the weight with data (−100..+100); NaN before v5.
 */
public record BreadthFeatures(
        double momentumBreadth,
        double dayBreadth,
        double weightCoveragePct,
        double topConcentration,
        int constituents,
        double moverBreadth) {
}
