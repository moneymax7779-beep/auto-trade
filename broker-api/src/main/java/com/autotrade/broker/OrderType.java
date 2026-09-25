package com.autotrade.broker;

/** Order types auto-trade uses. Raw market orders are deliberately absent for options. */
public enum OrderType {
    /** Limit order; a "marketable limit" is a limit priced through the touch. */
    LIMIT,
    /** Stop-limit: rests until the last traded price reaches the trigger, then works as a limit. */
    STOP_LIMIT
}
