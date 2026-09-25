package com.autotrade.core.event;

import java.time.Instant;

/** A constituent stock of an index, with its index weight in percent at capture time. */
public record ConstituentTick(
        Instant receivedAt,
        Instant exchangeTime,
        String underlying,
        long sourceSequence,
        long instrumentToken,
        String symbol,
        double price,
        Double previousClose,
        Long cumulativeVolume,
        Double sessionVwap,
        Double weightPercent) implements MarketEvent {

    @Override
    public EventKind kind() {
        return EventKind.CONSTITUENT;
    }
}
