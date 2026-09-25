package com.autotrade.risk;

import java.time.LocalTime;

/** Everything the risk engine needs to judge one new entry or add. */
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
        int ordersLastMinute) {
}
