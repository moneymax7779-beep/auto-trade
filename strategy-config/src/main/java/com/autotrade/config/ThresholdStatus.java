package com.autotrade.config;

/** Validation state of a threshold file, in gate order (see docs/IMPLEMENTATION-PLAN.md). */
public enum ThresholdStatus {
    /** Starting values, not tested. */
    UNCALIBRATED,
    /** Passed replay on the tuning sessions (G1). */
    TUNED_G1,
    /** Passed one run on held-out sessions (G2). */
    VALIDATED_G2,
    /** Passed forward PAPER (G3). */
    PAPER_APPROVED,
    /** Passed LIVE on one lot (G4). */
    LIVE_APPROVED
}
