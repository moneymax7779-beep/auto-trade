package com.autotrade.oms;

/** A tradable option contract with the exchange facts the OMS needs to price and slice orders. */
public record Contract(long token, String instrumentKey, String symbol, String exchange, int lotSize, double tickSize,
                       int maxLotsPerOrder) {

    public double roundUp(double price) {
        return round(Math.ceil(price / tickSize - 1e-9));
    }

    public double roundDown(double price) {
        return round(Math.max(1, Math.floor(price / tickSize + 1e-9)));
    }

    private double round(double ticks) {
        return Math.round(ticks * tickSize * 100.0) / 100.0;
    }
}
