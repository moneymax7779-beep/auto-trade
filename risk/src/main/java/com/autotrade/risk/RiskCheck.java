package com.autotrade.risk;

import java.time.LocalTime;

/**
 * Everything the risk engine needs to judge one new entry or add. {@code premium} is what this entry
 * would pay (rupees, 0 when unknown) and {@code premiumInUse} what every live position already holds.
 */
public record RiskCheck(
        String account,
        String strategy,
        String underlying,
        boolean add,
        int lots,
        int lotsAlreadyHeldInUnderlying,
        int openPositions,
        double dayPnl,
        double secondsSinceSpot,
        double secondsSinceOption,
        double spreadPct,
        LocalTime time,
        int ordersLastMinute,
        double premium,
        double premiumInUse) {

    public RiskCheck(String account, String strategy, String underlying, boolean add, int lots,
                     int lotsAlreadyHeldInUnderlying, int openPositions, double dayPnl, double secondsSinceSpot,
                     double secondsSinceOption, double spreadPct, LocalTime time, int ordersLastMinute) {
        this(account, strategy, underlying, add, lots, lotsAlreadyHeldInUnderlying, openPositions, dayPnl,
                secondsSinceSpot, secondsSinceOption, spreadPct, time, ordersLastMinute, 0, 0);
    }
}
