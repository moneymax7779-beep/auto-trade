package com.autotrade.broker;

/**
 * A broker that settles an expired option for cash itself: the paper broker. A real broker's positions are settled by the
 * exchange after the close, so it does not implement this.
 */
public interface ExpirySettlement {

    /**
     * Exchange cash settlement of {@code instrumentToken} at {@code price} (its intrinsic value): fills every working sell
     * of it, up to the quantity held, at that price, and cancels its other working orders.
     */
    void settle(long instrumentToken, double price);
}
