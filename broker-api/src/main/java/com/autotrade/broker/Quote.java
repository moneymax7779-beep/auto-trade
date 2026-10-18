package com.autotrade.broker;

import java.time.Instant;

/** A broker's executable top of book for one contract (a read call before an order, never market data). */
public record Quote(String instrumentKey, double bid, long bidQuantity, double ask, long askQuantity, double last,
                    Instant time) {

    public boolean hasBid() {
        return bid > 0 && bidQuantity > 0;
    }

    public boolean hasAsk() {
        return ask > 0 && askQuantity > 0;
    }
}
