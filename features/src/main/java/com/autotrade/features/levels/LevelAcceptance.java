package com.autotrade.features.levels;

import java.time.Duration;
import java.time.Instant;

import com.autotrade.features.bars.Bar;

/**
 * How price has treated one level on closed bars: consecutive closes beyond it, whether a break
 * was retested and held, and how far price travelled after the break. Updated on bar close only.
 */
public final class LevelAcceptance {

    private final double level;
    private int closesAbove;
    private int closesBelow;
    private boolean brokeUp;
    private boolean brokeDown;
    private boolean retestHeldAbove;
    private boolean retestHeldBelow;
    private double maxAboveAfterBreak;
    private double maxBelowAfterBreak;
    private Double previousClose;
    private Instant brokeUpAt;
    private Instant brokeDownAt;

    public LevelAcceptance(double level) {
        this.level = level;
    }

    /** @param band distance (price units) within which a bar's extreme counts as touching the level */
    public void update(Bar bar, double band) {
        if (previousClose != null) {
            if (previousClose <= level && bar.close() > level) {
                brokeUp = true;
                brokeDown = false;
                retestHeldAbove = false;
                maxAboveAfterBreak = 0;
                brokeUpAt = bar.end();
            } else if (previousClose >= level && bar.close() < level) {
                brokeDown = true;
                brokeUp = false;
                retestHeldBelow = false;
                maxBelowAfterBreak = 0;
                brokeDownAt = bar.end();
            }
        }
        if (bar.close() > level) {
            closesAbove++;
            closesBelow = 0;
        } else if (bar.close() < level) {
            closesBelow++;
            closesAbove = 0;
        }
        if (brokeUp) {
            maxAboveAfterBreak = Math.max(maxAboveAfterBreak, bar.high() - level);
            if (closesAbove > 1 && bar.low() <= level + band && bar.close() > level) {
                retestHeldAbove = true;
            }
        }
        if (brokeDown) {
            maxBelowAfterBreak = Math.max(maxBelowAfterBreak, level - bar.low());
            if (closesBelow > 1 && bar.high() >= level - band && bar.close() < level) {
                retestHeldBelow = true;
            }
        }
        previousClose = bar.close();
    }

    public double level() {
        return level;
    }

    public int closesAbove() {
        return closesAbove;
    }

    public int closesBelow() {
        return closesBelow;
    }

    public boolean retestHeldAbove() {
        return brokeUp && retestHeldAbove;
    }

    public boolean retestHeldBelow() {
        return brokeDown && retestHeldBelow;
    }

    public double maxAboveAfterBreak() {
        return brokeUp ? maxAboveAfterBreak : 0;
    }

    public double maxBelowAfterBreak() {
        return brokeDown ? maxBelowAfterBreak : 0;
    }

    /** End of the bar that closed up through the level (the latest break), or null. */
    public Instant brokeUpAt() {
        return brokeUp ? brokeUpAt : null;
    }

    public Instant brokeDownAt() {
        return brokeDown ? brokeDownAt : null;
    }

    /** Minutes since the upward break while closes stay above the level; 0 otherwise. */
    public double minutesAbove(Instant now) {
        return brokeUp && closesAbove > 0 ? Duration.between(brokeUpAt, now).toSeconds() / 60.0 : 0;
    }

    public double minutesBelow(Instant now) {
        return brokeDown && closesBelow > 0 ? Duration.between(brokeDownAt, now).toSeconds() / 60.0 : 0;
    }
}
