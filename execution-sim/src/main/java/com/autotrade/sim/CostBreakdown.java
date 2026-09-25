package com.autotrade.sim;

/** Charges for one order leg, in rupees. */
public record CostBreakdown(double turnover, double brokerage, double stt, double exchange, double sebi, double stamp,
                            double gst, String rateSet) {

    public double total() {
        return brokerage + stt + exchange + sebi + stamp + gst;
    }

    public static CostBreakdown none() {
        return new CostBreakdown(0, 0, 0, 0, 0, 0, 0, "none");
    }
}
