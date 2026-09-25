package com.autotrade.features.time;

/**
 * Where in the trading day an instant falls. The continuous-trading windows are strategy priors
 * from the feature file; the CAS phases follow the exchange session file.
 */
public enum SessionPhase {
    PRE_OPEN,
    OPENING_DISCOVERY,
    TREND_WINDOW,
    MIDDAY,
    AFTERNOON,
    LATE,
    CAS_REFERENCE,
    CAS_MARKET_AND_LIMIT,
    CAS_LIMIT_ONLY,
    CAS_MATCHING,
    DERIVATIVES_ONLY,
    CLOSED;

    public boolean isCas() {
        return this == CAS_REFERENCE || this == CAS_MARKET_AND_LIMIT || this == CAS_LIMIT_ONLY || this == CAS_MATCHING;
    }

    /** Continuous trading in the underlying's cash constituents. Normal-session statistics use only these. */
    public boolean isContinuous() {
        return this == OPENING_DISCOVERY || this == TREND_WINDOW || this == MIDDAY || this == AFTERNOON || this == LATE;
    }
}
