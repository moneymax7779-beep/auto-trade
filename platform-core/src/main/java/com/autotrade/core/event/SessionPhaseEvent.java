package com.autotrade.core.event;

import java.time.Instant;

/**
 * An index value observed in a named session phase (e.g. the closing auction). {@code priceSemantics}
 * says what the price means in that phase; CAS values are indicative, not traded prices.
 */
public record SessionPhaseEvent(
        Instant receivedAt,
        Instant eventTime,
        String underlying,
        String sessionPhase,
        String priceSemantics,
        double price,
        boolean officialFinal) implements MarketEvent {

    @Override
    public long sourceSequence() {
        return 0L;
    }

    @Override
    public EventKind kind() {
        return EventKind.SESSION_PHASE;
    }
}
