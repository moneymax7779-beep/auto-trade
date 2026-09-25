package com.autotrade.md.store;

import com.autotrade.core.event.MarketEvent;

/** A forward-only, ordered stream of events with a current head. */
public interface EventCursor extends AutoCloseable {

    /** Moves to the next event; false when exhausted. */
    boolean advance() throws Exception;

    /** The current event; valid after {@link #advance()} returned true. */
    MarketEvent head();

    @Override
    void close();
}
