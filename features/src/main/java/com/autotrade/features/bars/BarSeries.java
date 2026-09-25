package com.autotrade.features.bars;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Time bars aligned to an origin (the session open), so 3-minute bars start at 09:15, 09:18 ...
 * A bar closes when an update or {@link #advanceTo(Instant)} reaches its end; bars with no updates
 * are not created (the next bar starts at the next update's bucket).
 */
public final class BarSeries {

    private final Duration interval;
    private final Instant origin;
    private final int capacity;
    private final Deque<Bar> closed = new ArrayDeque<>();
    private final List<Consumer<Bar>> listeners = new ArrayList<>();

    private Instant start;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private int ticks;

    public BarSeries(Duration interval, Instant origin, int capacity) {
        this.interval = interval;
        this.origin = origin;
        this.capacity = capacity;
    }

    public void onClose(Consumer<Bar> listener) {
        listeners.add(listener);
    }

    /** Adds a price observation and any volume traded since the previous one. */
    public void update(Instant time, double price, long volumeDelta) {
        advanceTo(time);
        if (start == null) {
            start = bucketStart(time);
            open = high = low = close = price;
            volume = 0;
            ticks = 0;
        }
        high = Math.max(high, price);
        low = Math.min(low, price);
        close = price;
        volume += Math.max(0, volumeDelta);
        ticks++;
    }

    /** Closes the forming bar if {@code time} is at or past its end. */
    public void advanceTo(Instant time) {
        if (start != null && !time.isBefore(start.plus(interval))) {
            Bar bar = new Bar(start, start.plus(interval), open, high, low, close, volume, ticks);
            closed.addLast(bar);
            if (closed.size() > capacity) {
                closed.removeFirst();
            }
            start = null;
            for (Consumer<Bar> listener : listeners) {
                listener.accept(bar);
            }
        }
    }

    public Instant bucketStart(Instant time) {
        long millis = interval.toMillis();
        long offset = Math.floorDiv(time.toEpochMilli() - origin.toEpochMilli(), millis) * millis;
        return origin.plusMillis(offset);
    }

    /** Closed bars, oldest first. */
    public List<Bar> closed() {
        return List.copyOf(closed);
    }

    public Bar lastClosed() {
        return closed.peekLast();
    }

    /** The forming bar, or null when none is open. */
    public Bar current() {
        return start == null ? null : new Bar(start, start.plus(interval), open, high, low, close, volume, ticks);
    }

    public Duration interval() {
        return interval;
    }
}
