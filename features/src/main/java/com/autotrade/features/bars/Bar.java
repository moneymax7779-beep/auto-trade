package com.autotrade.features.bars;

import java.time.Instant;

/** A closed (or, for {@link BarSeries#current()}, forming) OHLC bar over [start, end). */
public record Bar(Instant start, Instant end, double open, double high, double low, double close, long volume,
                  int ticks) {

    public double range() {
        return high - low;
    }

    public double body() {
        return Math.abs(close - open);
    }

    /** Close position within the range: 1 = at the high, 0 = at the low, 0.5 for a zero-range bar. */
    public double closeLocation() {
        return range() == 0 ? 0.5 : (close - low) / range();
    }

    public double upperWick() {
        return high - Math.max(open, close);
    }

    public double lowerWick() {
        return Math.min(open, close) - low;
    }
}
