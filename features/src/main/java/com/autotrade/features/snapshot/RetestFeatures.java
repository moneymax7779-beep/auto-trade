package com.autotrade.features.snapshot;

/**
 * Break → retest → hold state of the four trigger levels (features v6): ORH and PDH for upward breaks,
 * ORL and PDL for downward breaks. State is NONE, BROKEN, RETESTING, HELD or FAILED
 * ({@link com.autotrade.features.levels.RetestTracker}); minutes since the state began;
 * {@code *PullbackVolumeRatio} = futures volume in the retest (touch) bar over the break bar's
 * (below 1 = "selling volume decreases" on the pullback).
 */
public record RetestFeatures(
        String orhState,
        double orhMinutesInState,
        double orhPullbackVolumeRatio,
        String orlState,
        double orlMinutesInState,
        double orlPullbackVolumeRatio,
        String pdhState,
        double pdhMinutesInState,
        double pdhPullbackVolumeRatio,
        String pdlState,
        double pdlMinutesInState,
        double pdlPullbackVolumeRatio) {

    public static final RetestFeatures EMPTY = new RetestFeatures("NONE", Double.NaN, Double.NaN, "NONE", Double.NaN,
            Double.NaN, "NONE", Double.NaN, Double.NaN, "NONE", Double.NaN, Double.NaN);
}
