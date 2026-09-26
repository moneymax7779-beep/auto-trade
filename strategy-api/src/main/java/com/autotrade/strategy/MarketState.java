package com.autotrade.strategy;

/**
 * The four independent states the design asks for instead of one pulse number. Direction,
 * structure and continuation are signed (−100 bearish … +100 bullish); participation is 0..100.
 * {@code label} names the state as the design's dashboard does (TREND_UP, TREND_DOWN, RANGE,
 * TRANSITION); null when the strategy version does not name it.
 */
public record MarketState(double direction, double participation, double structure, double continuation,
                          String regime, String label) {

    public MarketState(double direction, double participation, double structure, double continuation, String regime) {
        this(direction, participation, structure, continuation, regime, null);
    }
}
