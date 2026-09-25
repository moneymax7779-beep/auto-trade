package com.autotrade.features.futures;

/** Futures price/OI state over a window, scored for a long (CE) view; mirror for PE. */
public enum OiState {
    FRESH_LONG(2),
    SHORT_COVERING(1),
    NEUTRAL(0),
    LONG_UNWIND(-1),
    FRESH_SHORT(-2);

    private final int longScore;

    OiState(int longScore) {
        this.longScore = longScore;
    }

    public int longScore() {
        return longScore;
    }

    public static OiState classify(double priceChange, double oiChange, double minPrice, double minOi) {
        if (Double.isNaN(priceChange) || Double.isNaN(oiChange)
                || Math.abs(priceChange) < minPrice || Math.abs(oiChange) < minOi) {
            return NEUTRAL;
        }
        if (priceChange > 0) {
            return oiChange > 0 ? FRESH_LONG : SHORT_COVERING;
        }
        return oiChange > 0 ? FRESH_SHORT : LONG_UNWIND;
    }
}
