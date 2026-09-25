package com.autotrade.features.indicators;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/** Recent (time, value) samples kept for a retention window, for "value N seconds ago" lookups. */
public final class TimedSeries {

    private record Sample(long millis, double value) {
    }

    private final long retentionMillis;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public TimedSeries(Duration retention) {
        this.retentionMillis = retention.toMillis();
    }

    public void add(Instant time, double value) {
        long millis = time.toEpochMilli();
        if (!samples.isEmpty() && samples.peekLast().millis() == millis) {
            samples.removeLast();
        }
        samples.addLast(new Sample(millis, value));
        while (!samples.isEmpty() && samples.peekFirst().millis() < millis - retentionMillis) {
            samples.removeFirst();
        }
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    public double latest() {
        return samples.isEmpty() ? Double.NaN : samples.peekLast().value();
    }

    public Instant latestTime() {
        return samples.isEmpty() ? null : Instant.ofEpochMilli(samples.peekLast().millis());
    }

    /**
     * The last value at or before {@code time}, or NaN when the series does not reach back that far
     * (the oldest retained sample is after {@code time}).
     */
    public double valueAt(Instant time) {
        long millis = time.toEpochMilli();
        Iterator<Sample> descending = samples.descendingIterator();
        while (descending.hasNext()) {
            Sample sample = descending.next();
            if (sample.millis() <= millis) {
                return sample.value();
            }
        }
        return Double.NaN;
    }

    /** latest − value {@code ago} before {@code now}; NaN when either is missing. */
    public double change(Instant now, Duration ago) {
        double past = valueAt(now.minus(ago));
        double current = valueAt(now);
        return Double.isNaN(past) || Double.isNaN(current) ? Double.NaN : current - past;
    }
}
