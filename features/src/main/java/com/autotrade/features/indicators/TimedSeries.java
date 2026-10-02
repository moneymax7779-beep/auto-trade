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

    /**
     * Smallest value in force at any moment of the window ({@code now − window}, {@code now}]: the
     * value at the window start and every sample after it. NaN when the series does not reach back
     * to the window start, so a short history never passes for persistence.
     */
    public double min(Instant now, Duration window) {
        return extreme(now, window, true);
    }

    /** Largest value in force during the window; see {@link #min}. */
    public double max(Instant now, Duration window) {
        return extreme(now, window, false);
    }

    private double extreme(Instant now, Duration window, boolean min) {
        long start = now.minus(window).toEpochMilli();
        long end = now.toEpochMilli();
        double result = valueAt(now.minus(window));
        if (Double.isNaN(result)) {
            return Double.NaN;
        }
        for (Sample sample : samples) {
            if (sample.millis() > start && sample.millis() <= end) {
                result = min ? Math.min(result, sample.value()) : Math.max(result, sample.value());
            }
        }
        return result;
    }

    /**
     * Time-weighted mean of the value in force over ({@code now − window}, {@code now}]; over the
     * retained span when the series does not reach back that far. NaN when empty.
     */
    public double mean(Instant now, Duration window) {
        long start = now.minus(window).toEpochMilli();
        long end = now.toEpochMilli();
        double sum = 0;
        long total = 0;
        Sample prev = null;
        for (Sample sample : samples) {
            if (sample.millis() > end) {
                break;
            }
            if (prev != null) {
                long a = Math.max(prev.millis(), start);
                long b = Math.min(sample.millis(), end);
                if (b > a) {
                    sum += prev.value() * (b - a);
                    total += b - a;
                }
            }
            prev = sample;
        }
        if (prev == null) {
            return Double.NaN;
        }
        long a = Math.max(prev.millis(), start);
        if (end > a) {
            sum += prev.value() * (end - a);
            total += end - a;
        }
        return total == 0 ? prev.value() : sum / total;
    }

    /** latest − value {@code ago} before {@code now}; NaN when either is missing. */
    public double change(Instant now, Duration ago) {
        double past = valueAt(now.minus(ago));
        double current = valueAt(now);
        return Double.isNaN(past) || Double.isNaN(current) ? Double.NaN : current - past;
    }
}
