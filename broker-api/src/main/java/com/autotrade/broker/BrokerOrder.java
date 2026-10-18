package com.autotrade.broker;

import java.time.Instant;

/** One order in the broker's order book, normalised; {@code clientTag} is ours (null when the order is not ours). */
public record BrokerOrder(String clientTag, String brokerOrderId, String instrumentKey, String symbol, OrderSide side,
                          OrderType type, long quantity, long filledQuantity, double averagePrice, double limitPrice,
                          double triggerPrice, OrderStatus status, String rawStatus, String message, Instant updatedAt) {
}
