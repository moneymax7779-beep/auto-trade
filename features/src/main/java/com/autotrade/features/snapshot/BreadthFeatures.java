package com.autotrade.features.snapshot;

/**
 * Index-weighted constituent participation. {@code momentumBreadth} and {@code dayBreadth} are on
 * −100..+100; {@code topConcentration} is the share of the dominant side's contribution coming from
 * the top N stocks (0..1).
 */
public record BreadthFeatures(
        double momentumBreadth,
        double dayBreadth,
        double weightCoveragePct,
        double topConcentration,
        int constituents) {
}
