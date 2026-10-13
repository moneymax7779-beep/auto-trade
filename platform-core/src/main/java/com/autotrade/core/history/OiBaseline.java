package com.autotrade.core.history;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Features v12 (A-090): the normal 5-minute change of near-ATM option OI, as a share of the near-ATM OI, by side (CE/PE),
 * calendar days to expiry (0-4, 4 = four or more) and 15-minute bucket of the session (0 = 09:15-09:29, last 25 =
 * 15:30), measured on the stored one-minute bars with the live feature's strikes, weights and window ({@link Params}),
 * OI carried forward over minutes without a trade as the live chain does. {@link #z} turns a live value into a robust
 * z-score (median and 1.4826 x MAD); NaN without enough history.
 */
public record OiBaseline(Params params, Map<Cell.Key, Cell> cells) {

    /** At least this many observations behind a cell before a z-score is given. */
    public static final int MIN_COUNT = 30;
    /** Buckets 0-24 cover 09:15-15:29; 15:30 on is the post-close and has no baseline. */
    public static final int LAST_BUCKET = 24;

    public static final OiBaseline NONE = new OiBaseline(null, Map.of());

    public OiBaseline {
        cells = Map.copyOf(cells);
    }

    /** Whether an engine's OI z-scores have a usable baseline. */
    public enum Status {
        /** OI z-scores are off in this features file. */
        OFF,
        ACCEPTED,
        /** No baseline loaded (ref.oi_baseline empty or not copied): z-scores stay NaN. */
        MISSING,
        /** Measured with other strikes, weights or window than the features file, or mixed definitions: refused. */
        MISMATCH
    }

    /** The live feature's definition the baseline was measured with (null when the stored rows disagree). */
    public record Params(int nearAtmStrikes, int heavyWeightStrikes, double outerStrikeWeight, int windowMin) {
    }

    /** One cell's robust statistics. */
    public record Cell(double median, double mad, int count) {

        public record Key(String side, int dteBucket, int minuteBucket) {
        }
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** Calendar days from the session to the expiry, capped at 4. */
    public static int dteBucket(LocalDate session, LocalDate expiry) {
        return (int) Math.max(0, Math.min(4, ChronoUnit.DAYS.between(session, expiry)));
    }

    /** The 15-minute bucket of {@code time} counted from {@code open}; -1 before the open or after the last bucket. */
    public static int minuteBucket(LocalTime open, LocalTime time) {
        long minutes = Duration.between(open, time).toMinutes();
        if (minutes < 0 || time.isBefore(open)) {
            return -1;
        }
        int bucket = (int) (minutes / 15);
        return bucket > LAST_BUCKET ? -1 : bucket;
    }

    /** {@code value}'s robust z-score in its cell: (value - median) / (1.4826 x MAD); NaN when unknown. */
    public double z(String side, int dteBucket, int minuteBucket, double value) {
        Cell cell = cells.get(new Cell.Key(side, dteBucket, minuteBucket));
        if (cell == null || cell.count() < MIN_COUNT || !(cell.mad() > 0) || Double.isNaN(value)) {
            return Double.NaN;
        }
        return (value - cell.median()) / (1.4826 * cell.mad());
    }
}
