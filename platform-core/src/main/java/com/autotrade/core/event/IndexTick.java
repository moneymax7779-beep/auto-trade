package com.autotrade.core.event;

import java.time.Instant;

/** Index value. {@code exchangeTime} is null when the feed gives none. */
public record IndexTick(
        Instant receivedAt,
        Instant exchangeTime,
        String underlying,
        long sourceSequence,
        double price,
        Double previousClose,
        Integer atmStrike) implements MarketEvent {

    @Override
    public EventKind kind() {
        return EventKind.INDEX;
    }
}
