package com.autotrade.features.indicators;

import java.util.ArrayDeque;
import java.util.Deque;

/** Exponential moving average seeded with the simple average of the first {@code period} values. */
public final class Ema {

    private final int period;
    private final double alpha;
    private final Deque<Double> history = new ArrayDeque<>();
    private final int historySize;
    private double seedSum;
    private int count;
    private double value = Double.NaN;

    public Ema(int period, int historySize) {
        this.period = period;
        this.alpha = 2.0 / (period + 1);
        this.historySize = Math.max(1, historySize);
    }

    public void update(double input) {
        count++;
        if (count < period) {
            seedSum += input;
            return;
        }
        if (count == period) {
            value = (seedSum + input) / period;
        } else {
            value = alpha * input + (1 - alpha) * value;
        }
        history.addLast(value);
        if (history.size() > historySize) {
            history.removeFirst();
        }
    }

    public boolean ready() {
        return !Double.isNaN(value);
    }

    public double value() {
        return value;
    }

    /** Change over the last {@code bars} values, or NaN when not enough history. */
    public double slope(int bars) {
        if (history.size() <= bars) {
            return Double.NaN;
        }
        Double[] values = history.toArray(new Double[0]);
        return values[values.length - 1] - values[values.length - 1 - bars];
    }
}
