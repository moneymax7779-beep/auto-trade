package com.autotrade.broker;

import java.time.Instant;

/**
 * One order in the broker's order book, normalised; {@code clientTag} is ours (null when the order is not ours).
 * {@code product} is the broker's own product code as the book shows it (Kite MIS/NRML, Upstox I/D, ...), null when the
 * adapter does not read it (16 Nov 2026: the LIVE guard counts only orders of the product we trade, so a hand NRML trade
 * in the same account neither takes a contract slot nor lets an MIS sell pass as "held").
 */
public record BrokerOrder(String clientTag, String brokerOrderId, String instrumentKey, String symbol, OrderSide side,
                          OrderType type, long quantity, long filledQuantity, double averagePrice, double limitPrice,
                          double triggerPrice, OrderStatus status, String rawStatus, String message, Instant updatedAt,
                          String product) {

    /** The product as the book shows it, trimmed; blank or "null" is not known (null). */
    public BrokerOrder {
        product = product == null || product.isBlank() || product.trim().equalsIgnoreCase("null") ? null : product.trim();
    }

    /** An order whose product is not known (counted as ours by product-aware checks). */
    public BrokerOrder(String clientTag, String brokerOrderId, String instrumentKey, String symbol, OrderSide side,
                       OrderType type, long quantity, long filledQuantity, double averagePrice, double limitPrice,
                       double triggerPrice, OrderStatus status, String rawStatus, String message, Instant updatedAt) {
        this(clientTag, brokerOrderId, instrumentKey, symbol, side, type, quantity, filledQuantity, averagePrice,
                limitPrice, triggerPrice, status, rawStatus, message, updatedAt, null);
    }

    /**
     * Whether this order is of {@code product} (case-insensitive); true when either is unknown (null or blank), so an
     * adapter that does not read the product keeps the old behaviour of counting every order.
     */
    public boolean ofProduct(String product) {
        return product == null || product.isBlank() || this.product == null || this.product.equalsIgnoreCase(product);
    }
}
