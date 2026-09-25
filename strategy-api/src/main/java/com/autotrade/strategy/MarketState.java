package com.autotrade.strategy;

/**
 * The four independent states the design asks for instead of one pulse number. Direction,
 * structure and continuation are signed (−100 bearish … +100 bullish); participation is 0..100.
 */
public record MarketState(double direction, double participation, double structure, double continuation,
                          String regime) {
}
