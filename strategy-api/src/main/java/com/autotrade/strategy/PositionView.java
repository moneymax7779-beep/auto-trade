package com.autotrade.strategy;

import java.time.Instant;

/** What the strategy may know about its own position (from the executor, never guessed). */
public record PositionView(boolean open, OptionSide side, int lots, double averagePremium, Instant openedAt) {

    public static final PositionView FLAT = new PositionView(false, null, 0, Double.NaN, null);
}
