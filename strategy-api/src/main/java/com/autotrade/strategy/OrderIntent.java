package com.autotrade.strategy;

/**
 * What a strategy wants done, in lots of the ATM option of the given side. Execution (contract
 * choice, sizing per account, broker orders) happens elsewhere. {@code premiumStopPct} is the resting
 * premium stop for an entry (percent below average cost); NaN means the executor's default.
 */
public record OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct) {

    public enum Action {
        ENTER,
        ADD,
        EXIT
    }

    public OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason) {
        this(action, side, lots, stage, reason, Double.NaN);
    }

    public static OrderIntent exit(OptionSide side, Stage stage, String reason) {
        return new OrderIntent(Action.EXIT, side, 0, stage, reason);
    }

    /** The stop to use: this intent's own, else {@code fallback}. */
    public double stopPctOr(double fallback) {
        return Double.isNaN(premiumStopPct) ? fallback : premiumStopPct;
    }
}
