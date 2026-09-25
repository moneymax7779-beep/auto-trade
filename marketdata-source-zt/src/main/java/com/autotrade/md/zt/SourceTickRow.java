package com.autotrade.md.zt;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One market_tick_records row as read from the source. */
public record SourceTickRow(
        long sequence,
        String tickType,
        Long instrumentToken,
        String symbol,
        Long exchangeTimestampMs,
        LocalDateTime receivedAtIst,
        LocalDate contractExpiry,
        Integer contractLotSize,
        String exchangeSegment,
        String instrumentType,
        String quoteSource,
        Boolean analyticsComplete,
        Boolean depthComplete,
        String payloadHash,
        String payloadJson) {
}
