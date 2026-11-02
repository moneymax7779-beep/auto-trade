package com.autotrade.broker;

import java.time.Instant;
import java.util.List;

/**
 * A broker's executable book for one contract (a read call before an order, never market data): the top of book, and
 * the levels behind it when the broker reports them ({@code bids} and {@code asks} best first; empty when only the top
 * is known).
 */
public record Quote(String instrumentKey, double bid, long bidQuantity, double ask, long askQuantity, double last,
                    Instant time, List<Level> bids, List<Level> asks) {

    /** One price level: its price and the quantity resting there. */
    public record Level(double price, long quantity) {
    }

    public Quote {
        bids = bids == null ? List.of() : List.copyOf(bids);
        asks = asks == null ? List.of() : List.copyOf(asks);
    }

    /** The top of book only. */
    public Quote(String instrumentKey, double bid, long bidQuantity, double ask, long askQuantity, double last,
                 Instant time) {
        this(instrumentKey, bid, bidQuantity, ask, askQuantity, last, time, List.of(), List.of());
    }

    public boolean hasBid() {
        return bid > 0 && bidQuantity > 0;
    }

    public boolean hasAsk() {
        return ask > 0 && askQuantity > 0;
    }
}
