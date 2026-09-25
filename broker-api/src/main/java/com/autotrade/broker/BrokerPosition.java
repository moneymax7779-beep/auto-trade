package com.autotrade.broker;

/** Net intraday position in one instrument as the broker reports it. */
public record BrokerPosition(long instrumentToken, String symbol, long netQuantity, double averagePrice) {
}
