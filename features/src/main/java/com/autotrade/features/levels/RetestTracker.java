package com.autotrade.features.levels;

import java.time.Instant;

import com.autotrade.features.bars.Bar;

/**
 * Break → retest → hold for one level, in one direction, on closed 3-minute bars (features v6).
 *
 * <ul>
 *   <li>BROKEN: a bar closed through the level (from the other side).</li>
 *   <li>RETESTING: a later bar came back within {@code band} of the level (a close up to {@code band}
 *       back through it is tolerated).</li>
 *   <li>HELD: after the touch, a bar made a higher low than the touch bar (lower high for a downward
 *       break), closed beyond the touch bar's close and in the upper (lower) half of its own range: the
 *       retest held and price resumed.</li>
 *   <li>FAILED: a bar closed more than {@code band} back through the level after the break.</li>
 * </ul>
 * A new break restarts the sequence.
 */
public final class RetestTracker {

    public enum State { NONE, BROKEN, RETESTING, HELD, FAILED }

    private final double level;
    private final boolean up;
    private State state = State.NONE;
    private Instant stateAt;
    private Bar breakBar;
    private Bar touchBar;
    private Double previousClose;

    /** @param up true for a break above the level (ORH, PDH), false for a break below (ORL, PDL) */
    public RetestTracker(double level, boolean up) {
        this.level = level;
        this.up = up;
    }

    public void update(Bar bar, double band) {
        int s = up ? 1 : -1;
        double close = s * bar.close();
        double lvl = s * level;
        boolean crossed = previousClose != null && s * previousClose <= lvl && close > lvl;
        if (crossed) {
            move(State.BROKEN, bar);
            breakBar = bar;
            touchBar = null;
        } else if (state == State.BROKEN || state == State.RETESTING || state == State.HELD) {
            double near = up ? bar.low() : -bar.high();       // the bar's extreme towards the level
            if (close < lvl - band) {
                move(State.FAILED, bar);
            } else if (state == State.BROKEN && near <= lvl + band) {
                move(State.RETESTING, bar);
                touchBar = bar;
            } else if (state == State.RETESTING) {
                double touchNear = up ? touchBar.low() : -touchBar.high();
                double touchClose = s * touchBar.close();
                double location = up ? bar.closeLocation() : 1 - bar.closeLocation();
                if (near >= touchNear && close > touchClose && location >= 0.5) {
                    move(State.HELD, bar);
                } else if (near < touchNear) {
                    touchBar = bar;                           // a deeper touch becomes the reference
                }
            }
        }
        previousClose = bar.close();
    }

    private void move(State next, Bar bar) {
        state = next;
        stateAt = bar.end();
    }

    public State state() {
        return state;
    }

    public Instant stateAt() {
        return stateAt;
    }

    public Bar breakBar() {
        return breakBar;
    }

    public Bar touchBar() {
        return touchBar;
    }

    public double level() {
        return level;
    }
}
