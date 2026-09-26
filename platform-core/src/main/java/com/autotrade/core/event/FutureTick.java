package com.autotrade.core.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A tick of an index future. {@code totalBuyQuantity}/{@code totalSellQuantity} are the order book's
 * total pending bid and ask quantities when the feed provides them (Upstox full mode), else null.
 */
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
        Double sessionVwap,
        Double totalBuyQuantity,
        Double totalSellQuantity) implements MarketEvent {

    /** Without book totals (sources that do not carry them, such as zt-tiger-v2). */
    public FutureTick(Instant receivedAt, Instant exchangeTime, String underlying, long sourceSequence,
                      long instrumentToken, String symbol, LocalDate expiry, double price, Long cumulativeVolume,
                      Double openInterest, Double sessionVwap) {
        this(receivedAt, exchangeTime, underlying, sourceSequence, instrumentToken, symbol, expiry, price,
                cumulativeVolume, openInterest, sessionVwap, null, null);
    }

    @Override
    public EventKind kind() {
        return EventKind.FUTURE;
    }
}
