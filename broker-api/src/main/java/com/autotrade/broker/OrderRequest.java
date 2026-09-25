package com.autotrade.broker;

/**
 * A new order. {@code clientOrderId} is chosen by the OMS and is the idempotency key: placing the
 * same id twice must not create two orders. Prices are in rupees; {@code triggerPrice} is used only
 * by {@link OrderType#STOP_LIMIT}.
 */
public record OrderRequest(
        String clientOrderId,
        long instrumentToken,
        String instrumentKey,
        String symbol,
        String exchange,
        OrderSide side,
        OrderType type,
        long quantity,
        double limitPrice,
        double triggerPrice,
        String tag) {

    public OrderRequest {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (!(limitPrice > 0)) {
            throw new IllegalArgumentException("limit price must be positive");
        }
        if (type == OrderType.STOP_LIMIT && !(triggerPrice > 0)) {
            throw new IllegalArgumentException("stop-limit needs a trigger price");
        }
    }
}
