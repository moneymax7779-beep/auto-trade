package com.autotrade.strategy;

/** Where one directional setup (CE or PE) is in its lifecycle during a session. */
public enum Stage {
    IDLE,
    WATCH,
    /** The underlying is coiling (tight range, converged EMAs, fading volume) before a possible expansion. */
    COMPRESSION,
    ARMED,
    EARLY_ENTRY,
    CONFIRMED,
    /** Confirmed, waiting for the broken level to be retested and held before the runner tranche. */
    RETEST,
    RUNNER,
    /** The setup traded and was closed; no further entries on this side today. */
    EXITED;

    public boolean holdsPosition() {
        return this == EARLY_ENTRY || this == CONFIRMED || this == RETEST || this == RUNNER;
    }
}
