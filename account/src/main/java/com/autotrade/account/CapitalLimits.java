package com.autotrade.account;

/**
 * An account's capital limits, each a percentage of its equity (or, for the buffer, of the broker's available funds).
 * Daily loss: entries stop once the day's net reaches minus this share of equity. Per trade: one entry's premium.
 * Open premium: all open positions' premium together. Broker buffer: the share of the broker's funds kept unused.
 */
public record CapitalLimits(double dailyLossPct, double maxPremiumPerTradePct, double maxOpenPremiumPct,
                            double brokerFundsBufferPct) {

    public CapitalLimits {
        check("daily loss", dailyLossPct);
        check("premium per trade", maxPremiumPerTradePct);
        check("open premium", maxOpenPremiumPct);
        if (!(brokerFundsBufferPct >= 0 && brokerFundsBufferPct < 100)) {
            throw new IllegalArgumentException("broker funds buffer must be 0 to under 100 %");
        }
    }

    private static void check(String name, double pct) {
        if (!(pct > 0 && pct <= 100)) {
            throw new IllegalArgumentException(name + " limit must be above 0 and at most 100 %");
        }
    }
}
