package com.autotrade.broker;

public enum OrderStatus {
    /** Sent, not yet acknowledged. */
    PENDING,
    /** Working at the exchange. */
    OPEN,
    /** A stop order waiting for its trigger. */
    TRIGGER_PENDING,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
