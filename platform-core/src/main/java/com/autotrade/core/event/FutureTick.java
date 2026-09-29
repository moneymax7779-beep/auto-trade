package com.autotrade.core.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A tick of an index future. {@code totalBuyQuantity}/{@code totalSellQuantity} are the order book's
 * total pending bid and ask quantities when the feed provides them (Upstox full mode), else null;
 * {@code bids}/{@code asks} are the best five levels when the feed provides them, else empty.
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
        Double totalSellQuantity,
        DepthLevels bids,
        DepthLevels asks) implements MarketEvent {

    public FutureTick {
        bids = bids == null ? DepthLevels.empty() : bids;
        asks = asks == null ? DepthLevels.empty() : asks;
    }

    /** With book totals but no depth. */
    public FutureTick(Instant receivedAt, Instant exchangeTime, String underlying, long sourceSequence,
                      long instrumentToken, String symbol, LocalDate expiry, double price, Long cumulativeVolume,
                      Double openInterest, Double sessionVwap, Double totalBuyQuantity, Double totalSellQuantity) {
        this(receivedAt, exchangeTime, underlying, sourceSequence, instrumentToken, symbol, expiry, price,
                cumulativeVolume, openInterest, sessionVwap, totalBuyQuantity, totalSellQuantity, null, null);
    }

    /** Without book totals (sources that do not carry them, such as zt-tiger-v2). */
    public FutureTick(Instant receivedAt, Instant exchangeTime, String underlying, long sourceSequence,
                      long instrumentToken, String symbol, LocalDate expiry, double price, Long cumulativeVolume,
                      Double openInterest, Double sessionVwap) {
        this(receivedAt, exchangeTime, underlying, sourceSequence, instrumentToken, symbol, expiry, price,
                cumulativeVolume, openInterest, sessionVwap, null, null, null, null);
    }

    @Override
    public EventKind kind() {
        return EventKind.FUTURE;
    }
}
