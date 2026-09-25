package com.autotrade.md.store;

import java.time.Instant;
import java.time.LocalDate;

public record CandleRow(
        String underlying,
        String instrumentKey,
        String timeframe,
        String source,
        Instant barStart,
        double open,
        double high,
        double low,
        double close,
        Double volume,
        Boolean volumeComplete,
        String volumeSource,
        String volumeContractSymbol,
        LocalDate volumeContractExpiry,
        Integer mss) {
}
