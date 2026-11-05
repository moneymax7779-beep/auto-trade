package com.autotrade.md.live;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Operator controls for a paced replay (user, 5 Nov 2026: "stop at some point for analyzing what data it holds at that
 * point ... or carry on ... till the exit"): pause, resume, the speed, pause at a market time, and pause once the
 * session's next entry is complete or its next position (optionally one contract's) has closed. The replay feed paces
 * every event through {@link #pace}, which waits on this object's monitor, so a pause, a resume, a speed change or a
 * stop takes effect at once, even in the middle of a long wait; nothing after a pause point is processed.
 */
public final class ReplayControl {

    /** What pauses the replay: none, the next complete entry, the next closed position, either. */
    public enum PauseOn { NONE, ENTRY, EXIT, TRADE }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private boolean paused;
    private double speed;
    private Instant pauseAt;
    private PauseOn pauseOn = PauseOn.NONE;
    private String pauseOnSymbol;
    private Instant marketNow;
    private String pausedBecause;
    private Runnable onPause = () -> { };
    /** Whether a feed paces through this control now (an unpaced replay has none: its controls would do nothing). */
    private boolean attached;
    /** The pacing clock: an event's market time and the wall time it was delivered, at {@link #anchorSpeed}. */
    private Instant anchorMarket;
    private long anchorWallNanos;
    private double anchorSpeed;
    /** Whether the current pause's hook has run (once per pause). */
    private boolean hookRan;

    /** A new replay at {@code speed}: running, nothing pending, no feed attached yet. */
    public synchronized void reset(double speed) {
        this.speed = speed;
        paused = false;
        pauseAt = null;
        pauseOn = PauseOn.NONE;
        pauseOnSymbol = null;
        marketNow = null;
        pausedBecause = null;
        onPause = () -> { };
        attached = false;
        anchorMarket = null;
        notifyAll();
    }

    /** Called by the feed while it paces events through this control; {@code false} when it ends. */
    public synchronized void attached(boolean on) {
        attached = on;
    }

    public synchronized boolean attached() {
        return attached;
    }

    /**
     * Run by the feed's thread, outside this object's lock, as a pause begins (the session's feature snapshots are
     * written, so the minute is there).
     */
    public synchronized void onPause(Runnable hook) {
        onPause = hook == null ? () -> { } : hook;
    }

    public synchronized void pause() {
        pause("operator");
    }

    public synchronized void resume() {
        paused = false;
        pausedBecause = null;
        hookRan = false;                         // the next pause writes its snapshots too, however soon it comes
        anchorMarket = null;                     // the clock starts again from the next event: a pause is never caught up
        notifyAll();
    }

    /** @param speed market seconds per wall second, above 0 */
    public synchronized void speed(double speed) {
        if (!(speed > 0)) {
            throw new IllegalArgumentException("speed must be above 0");
        }
        this.speed = speed;
        notifyAll();                             // a wait at the old speed is recomputed at once
    }

    /** Runs on and pauses at the first event at or after {@code at} (market time); null clears it. */
    public synchronized void pauseAt(Instant at) {
        pauseAt = at;
    }

    /** Runs on and pauses after the next event of this kind; {@code symbol} limits an EXIT to that contract (null: any). */
    public synchronized void pauseOn(PauseOn on, String symbol) {
        pauseOn = on == null ? PauseOn.NONE : on;
        pauseOnSymbol = pauseOn == PauseOn.EXIT ? symbol : null;
    }

    /**
     * From the session: a position's entry is complete ({@code role} "ENTRY": every slice filled or given up) or a
     * position has closed (any other role) on {@code symbol}.
     */
    public synchronized void filled(String role, String symbol) {
        boolean entry = "ENTRY".equals(role);
        boolean match = switch (pauseOn) {
            case NONE -> false;
            case ENTRY -> entry;
            case EXIT -> !entry && (pauseOnSymbol == null || pauseOnSymbol.equals(symbol));
            case TRADE -> true;
        };
        if (match) {
            pauseOn = PauseOn.NONE;
            pauseOnSymbol = null;
            pause((entry ? "entry " : "exit ") + symbol);
        }
    }

    /**
     * From the feed, before it hands on the event at {@code market}: notes the time, pauses at a due {@link #pauseAt},
     * waits while paused (running the pause hook once, outside the lock), and waits until the event is due at the
     * current speed. Returns at once when {@code stopped} says the replay is over; a {@link #resume()} (which a stopping
     * feed calls) wakes it.
     */
    void pace(Instant market, BooleanSupplier stopped) throws InterruptedException {
        while (true) {
            Runnable hook;
            synchronized (this) {
                marketNow = market;
                if (pauseAt != null && market != null && !market.isBefore(pauseAt)) {
                    pauseAt = null;
                    pause("time " + HMS.format(market.atZone(IST)));
                }
                if (stopped.getAsBoolean()) {
                    return;
                }
                if (!paused) {
                    hookRan = false;
                    if (!(speed > 0) || market == null) {
                        return;
                    }
                    if (anchorMarket == null || anchorSpeed != speed) {      // start (or restart) the clock here
                        anchorMarket = market;
                        anchorWallNanos = System.nanoTime();
                        anchorSpeed = speed;
                        return;
                    }
                    long due = anchorWallNanos + (long) ((market.toEpochMilli() - anchorMarket.toEpochMilli()) * 1_000_000 / speed);
                    long waitNanos = due - System.nanoTime();
                    if (waitNanos <= 1_000_000) {
                        return;
                    }
                    wait(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                    continue;
                }
                anchorMarket = null;
                if (hookRan) {
                    wait(200);
                    continue;
                }
                hookRan = true;
                hook = onPause;
            }
            hook.run();
        }
    }

    synchronized double speed() {
        return speed;
    }

    /** The state for the UI. */
    public synchronized Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paused", paused);
        out.put("pausedBecause", pausedBecause);
        out.put("speed", speed);
        out.put("marketTime", marketNow == null ? null : marketNow.toString());
        out.put("pauseAt", pauseAt == null ? null : pauseAt.toString());
        out.put("pauseOn", pauseOn.name());
        out.put("pauseOnSymbol", pauseOnSymbol);
        out.put("attached", attached);
        return out;
    }

    private void pause(String because) {
        paused = true;
        pausedBecause = because;
        notifyAll();
    }
}
