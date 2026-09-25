package com.autotrade.features.indicators;

import com.autotrade.features.bars.Bar;

/** Average true range with Wilder smoothing, seeded by the simple average of the first period. */
public final class WilderAtr {

    private final int period;
    private double previousClose = Double.NaN;
    private double seedSum;
    private int count;
    private double value = Double.NaN;

    public WilderAtr(int period) {
        this.period = period;
    }

    public void update(Bar bar) {
        double trueRange = Double.isNaN(previousClose) ? bar.range()
                : Math.max(bar.high(), previousClose) - Math.min(bar.low(), previousClose);
        previousClose = bar.close();
        count++;
        if (count < period) {
            seedSum += trueRange;
        } else if (count == period) {
            value = (seedSum + trueRange) / period;
        } else {
            value = (value * (period - 1) + trueRange) / period;
        }
    }

    public boolean ready() {
        return !Double.isNaN(value);
    }

    public double value() {
        return value;
    }
}
