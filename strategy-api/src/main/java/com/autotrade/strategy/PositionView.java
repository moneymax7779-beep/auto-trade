package com.autotrade.strategy;

import java.time.Instant;

/**
 * What the strategy may know about its own position (from the executor, never guessed). {@code bid} is
 * the executor's latest best bid of the held contract (NaN when it has none).
 */
public record PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt,
                           double bid) {

    public static final PositionView FLAT = new PositionView(false, null, 0, Double.NaN, null);

    public PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt) {
        this(open, side, lots, averagePremium, openedAt, Double.NaN);
    }
}
