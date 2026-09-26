package com.autotrade.core.history;

import java.time.Instant;

/** One-minute OHLC bar starting at {@code start}; it is known from {@code start + 1 minute}. */
public record MinuteBar(Instant start, double open, double high, double low, double close) {

    public Instant end() {
        return start.plusSeconds(60);
    }
}
