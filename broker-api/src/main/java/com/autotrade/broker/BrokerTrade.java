package com.autotrade.broker;

import java.time.Instant;

/** One fill from the broker's trade book (the contract note's lines), for the end-of-day reconciliation. */
public record BrokerTrade(String tradeId, String brokerOrderId, String instrumentKey, String symbol, OrderSide side,
                          long quantity, double price, Instant time) {
}
