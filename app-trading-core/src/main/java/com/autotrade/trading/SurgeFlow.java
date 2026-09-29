package com.autotrade.trading;

import java.util.List;

/**
 * The standard open-interest reading used by the futures-surge panel (display only, never a trading
 * input). Futures: OI and price together; calls and puts: the ATM ± 2 strikes' total OI with the ATM
 * option's mid price. Each source reads +1 (bullish), −1 (bearish) or 0; the flow is bullish when at
 * least two agree and none disagree (docs/studies/2026-09-29-surge-oi-flow.md).
 */
final class SurgeFlow {

    static final double FUTURES_OI_MIN = 0.002;      // 0.2 % of futures OI
    static final double OPTIONS_OI_MIN = 0.01;       // 1 % of the five strikes' OI

    record Reading(String label, int score) {
    }

    private SurgeFlow() {
    }

    static Reading futures(double price0, double oi0, double price1, double oi1) {
        double change = oi0 > 0 ? (oi1 - oi0) / oi0 : 0;
        if (Math.abs(change) < FUTURES_OI_MIN || price1 == price0) {
            return new Reading("flat", 0);
        }
        boolean up = price1 > price0;
        if (change > 0) {
            return up ? new Reading("long build", 1) : new Reading("short build", -1);
        }
        return up ? new Reading("short covering", 1) : new Reading("long unwinding", -1);
    }

    /** {@code call} true for calls: writing and long unwinding are bearish; for puts they are bullish. */
    static Reading options(boolean call, double oi0, double oi1, double mid0, double mid1) {
        double change = oi0 > 0 ? (oi1 - oi0) / oi0 : 0;
        if (Math.abs(change) < OPTIONS_OI_MIN || mid1 == mid0) {
            return new Reading("flat", 0);
        }
        boolean oiUp = change > 0;
        boolean priceUp = mid1 > mid0;
        String label = oiUp ? (priceUp ? "buying" : "writing") : (priceUp ? "short covering" : "long unwinding");
        int score = priceUp ? 1 : -1;               // a call rising is bullish, a put rising is bearish
        return new Reading(label, call ? score : -score);
    }

    static String overall(List<Integer> scores) {
        int total = scores.stream().mapToInt(Integer::intValue).sum();
        int min = scores.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = scores.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (total >= 2 && min >= 0) {
            return "bullish";
        }
        if (total <= -2 && max <= 0) {
            return "bearish";
        }
        return "mixed";
    }
}
