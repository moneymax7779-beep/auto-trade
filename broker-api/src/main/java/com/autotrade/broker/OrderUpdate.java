package com.autotrade.broker;

import java.time.Instant;

/** The broker's view of an order after a change. {@code lastFill*} describe the fill that caused it, if any. */
public record OrderUpdate(
        String clientOrderId,
        String brokerOrderId,
        OrderStatus status,
        long filledQuantity,
        double averagePrice,
        long lastFillQuantity,
        double lastFillPrice,
        Instant time,
        String message) {

    public boolean isFill() {
        return lastFillQuantity > 0;
    }
}
