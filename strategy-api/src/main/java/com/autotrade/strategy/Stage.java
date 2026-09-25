package com.autotrade.strategy;

/** Where one directional setup (CE or PE) is in its lifecycle during a session. */
public enum Stage {
    IDLE,
    WATCH,
    ARMED,
    EARLY_ENTRY,
    CONFIRMED,
    RUNNER,
    /** The setup traded and was closed; no further entries on this side today. */
    EXITED;

    public boolean holdsPosition() {
        return this == EARLY_ENTRY || this == CONFIRMED || this == RUNNER;
    }
}
