package com.autotrade.features.indicators;

import java.util.ArrayList;
import java.util.List;

import com.autotrade.features.bars.Bar;

/**
 * Pivot swing highs and lows: a bar whose high (low) is strictly above (below) the {@code strength}
 * bars on each side. A pivot is known only {@code strength} bars after it formed, and is reported
 * then, never earlier.
 */
public final class SwingTracker {

    public record Swing(Bar bar, double price, boolean high) {
    }

    private final int strength;
    private final List<Bar> window = new ArrayList<>();
    private final List<Swing> highs = new ArrayList<>();
    private final List<Swing> lows = new ArrayList<>();

    public SwingTracker(int strength) {
        this.strength = strength;
    }

    public void update(Bar bar) {
        window.add(bar);
        if (window.size() > 2 * strength + 1) {
            window.removeFirst();
        }
        if (window.size() < 2 * strength + 1) {
            return;
        }
        Bar pivot = window.get(strength);
        boolean isHigh = true;
        boolean isLow = true;
        for (int i = 0; i < window.size(); i++) {
            if (i == strength) {
                continue;
            }
            isHigh &= pivot.high() > window.get(i).high();
            isLow &= pivot.low() < window.get(i).low();
        }
        if (isHigh) {
            highs.add(new Swing(pivot, pivot.high(), true));
        }
        if (isLow) {
            lows.add(new Swing(pivot, pivot.low(), false));
        }
    }

    public Swing lastHigh() {
        return highs.isEmpty() ? null : highs.getLast();
    }

    public Swing lastLow() {
        return lows.isEmpty() ? null : lows.getLast();
    }

    /** True when the last {@code count} confirmed swing lows are each higher than the one before. */
    public boolean higherLows(int count) {
        return rising(lows, count);
    }

    /** True when the last {@code count} confirmed swing highs are each lower than the one before. */
    public boolean lowerHighs(int count) {
        if (highs.size() < count) {
            return false;
        }
        for (int i = highs.size() - count + 1; i < highs.size(); i++) {
            if (highs.get(i).price() >= highs.get(i - 1).price()) {
                return false;
            }
        }
        return true;
    }

    private static boolean rising(List<Swing> swings, int count) {
        if (swings.size() < count) {
            return false;
        }
        for (int i = swings.size() - count + 1; i < swings.size(); i++) {
            if (swings.get(i).price() <= swings.get(i - 1).price()) {
                return false;
            }
        }
        return true;
    }
}
