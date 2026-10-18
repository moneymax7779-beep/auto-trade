package com.autotrade.broker;

/**
 * Net intraday position in one instrument as the broker reports it. {@code instrumentKey} is our key for it
 * ({@code NSE_FO|47168}; null from adapters that work in tokens only); {@code dayBuyValue} / {@code daySellValue} are
 * today's traded values in rupees (NaN when the adapter does not report them).
 */
public record BrokerPosition(long instrumentToken, String symbol, long netQuantity, double averagePrice,
                             String instrumentKey, long dayBuyQuantity, long daySellQuantity, double dayBuyValue,
                             double daySellValue) {

    public BrokerPosition(long instrumentToken, String symbol, long netQuantity, double averagePrice) {
        this(instrumentToken, symbol, netQuantity, averagePrice, null, 0, 0, Double.NaN, Double.NaN);
    }
}
