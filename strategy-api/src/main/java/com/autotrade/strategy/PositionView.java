package com.autotrade.strategy;

import java.time.Instant;

/**
 * What the strategy may know about its own position (from the executor, never guessed). {@code bid} is
 * the executor's latest best bid of the held contract (NaN when it has none); {@code closedToday} the
 * number of this strategy's positions in this underlying that bought something and are closed this
 * session (an entry that never filled does not count; a position the executor opened and closed
 * between two decisions shows only here).
 */
public record PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt,
                           double bid, int closedToday) {

    public static final PositionView FLAT = new PositionView(false, null, 0, Double.NaN, null);

    public PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt) {
        this(open, side, lots, averagePremium, openedAt, Double.NaN);
    }

    public PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt,
                        double bid) {
        this(open, side, lots, averagePremium, openedAt, bid, 0);
    }

    /** No position now, {@code closedToday} closed this session. */
    public static PositionView flat(int closedToday) {
        return closedToday == 0 ? FLAT : new PositionView(false, null, 0, Double.NaN, null, Double.NaN, closedToday);
    }
}
