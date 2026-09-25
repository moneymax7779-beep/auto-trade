package com.autotrade.core.event;

import java.time.Instant;
import java.time.LocalDate;

public record FutureTick(
        Instant receivedAt,
        Instant exchangeTime,
        String underlying,
        long sourceSequence,
        long instrumentToken,
        String symbol,
        LocalDate expiry,
        double price,
        Long cumulativeVolume,
        Double openInterest,
        Double sessionVwap) implements MarketEvent {

    @Override
    public EventKind kind() {
        return EventKind.FUTURE;
    }
}
