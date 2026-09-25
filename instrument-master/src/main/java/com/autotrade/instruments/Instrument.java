package com.autotrade.instruments;

import java.time.LocalDate;

/**
 * One derivative contract (or index) from the broker's contract master. Prices and tick size are in
 * rupees; {@code freezeQuantity} is the exchange's maximum quantity per order.
 */
public record Instrument(
        String instrumentKey,
        String exchangeToken,
        String segment,
        String exchange,
        String type,
        String underlying,
        String underlyingKey,
        String tradingSymbol,
        LocalDate expiry,
        double strike,
        int lotSize,
        double tickSize,
        long freezeQuantity,
        boolean weekly) {

    public boolean isOption() {
        return "CE".equals(type) || "PE".equals(type);
    }

    public boolean isFuture() {
        return "FUT".equals(type);
    }

    /**
     * Largest whole number of lots one order may carry. Upstox publishes {@code freeze_quantity} as
     * the largest allowed order quantity (NIFTY 1,755 = 27 × 65), so larger orders must be sliced.
     */
    public int maxLotsPerOrder() {
        if (freezeQuantity <= 0 || lotSize <= 0) {
            return 1;
        }
        return (int) Math.max(1, freezeQuantity / lotSize);
    }

    /** Rounds a price to this contract's tick, toward the given direction (+1 up, -1 down). */
    public double roundToTick(double price, int direction) {
        if (tickSize <= 0) {
            return price;
        }
        double ticks = price / tickSize;
        double rounded = direction > 0 ? Math.ceil(ticks - 1e-9) : Math.floor(ticks + 1e-9);
        return Math.round(rounded * tickSize * 100.0) / 100.0;
    }
}
