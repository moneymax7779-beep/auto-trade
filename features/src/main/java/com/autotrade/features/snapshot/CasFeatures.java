package com.autotrade.features.snapshot;

/**
 * Closing-auction context from what the capture has: the indicative index value and the phase the
 * feed reported. Per-stock auction data (IEP, imbalance) needs the SDK 1.29 feed and is not here yet.
 */
public record CasFeatures(
        String feedPhase,
        double indicativeIndex,
        double indicativeVsLastContinuous,
        double futuresVsIndicative) {
}
