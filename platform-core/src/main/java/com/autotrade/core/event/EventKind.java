package com.autotrade.core.event;

/** Ordered so that events with the same receipt time and sequence replay in a fixed order. */
public enum EventKind {
    INDEX,
    FUTURE,
    OPTION,
    CONSTITUENT,
    SESSION_PHASE,
    AUCTION
}
