package com.autotrade.sim;

import java.time.Instant;

/**
 * A long option trade to simulate: buy {@code quantity} of one contract when the decision is made,
 * exit on the first of premium stop, premium target or {@code exitBy}. Stop and target are premium
 * levels, checked against the bid (what a long position can actually sell at).
 */
public record TradePlan(
        long instrumentToken,
        String exchange,
        long quantity,
        Instant decisionTime,
        double stopPremium,
        double targetPremium,
        Instant exitBy) {

    public TradePlan {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (!exitBy.isAfter(decisionTime)) {
            throw new IllegalArgumentException("exitBy must be after the decision");
        }
    }
}
