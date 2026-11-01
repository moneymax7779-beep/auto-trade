package com.autotrade.strategy;

import java.time.Instant;
import java.util.List;

/**
 * What the strategy may know about its own position (from the executor, never guessed). {@code bid} is
 * the executor's latest best bid of the held contract (NaN when it has none); {@code closedToday} the
 * number of this strategy's positions in this underlying that bought something and are closed this
 * session (an entry that never filled does not count; a position the executor opened and closed
 * between two decisions shows only here). {@code heldValueRatio} is what all of the strategy's open legs in the
 * underlying are worth at their bids over what they cost (a straddle's combined value; NaN when unknown, e.g. a leg
 * still filling). {@code legs} are those open legs one by one (empty when the executor did not say).
 */
public record PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt,
                           double bid, int closedToday, double heldValueRatio, List<Leg> legs) {

    /** One held contract: its strike (NaN when unknown), quantity, average cost and latest bid (NaN when none). */
    public record Leg(OptionSide side, double strike, long quantity, double averageCost, double bid) {
    }

    public static final PositionView FLAT = new PositionView(false, null, 0, Double.NaN, null);

    public PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt) {
        this(open, side, lots, averagePremium, openedAt, Double.NaN);
    }

    public PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt,
                        double bid) {
        this(open, side, lots, averagePremium, openedAt, bid, 0);
    }

    public PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt,
                        double bid, int closedToday) {
        this(open, side, lots, averagePremium, openedAt, bid, closedToday, Double.NaN, List.of());
    }

    public PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt,
                        double bid, int closedToday, double heldValueRatio) {
        this(open, side, lots, averagePremium, openedAt, bid, closedToday, heldValueRatio, List.of());
    }

    /** No position now, {@code closedToday} closed this session. */
    public static PositionView flat(int closedToday) {
        return closedToday == 0 ? FLAT : new PositionView(false, null, 0, Double.NaN, null, Double.NaN, closedToday);
    }
}
