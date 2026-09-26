package com.autotrade.strategy;

/**
 * What a strategy wants done, in lots of an option of the given side. Execution (contract choice,
 * sizing per account, broker orders) happens elsewhere. {@code premiumStopPct} is the resting premium
 * stop for an entry (percent below average cost); NaN means the executor's default. {@code strikeOffset}
 * is the strike in strikes from ATM: 0 = ATM, +1 = one strike in the money, −1 = one out.
 */
public record OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct,
                          int strikeOffset) {

    public enum Action {
        ENTER,
        ADD,
        EXIT
    }

    public OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason) {
        this(action, side, lots, stage, reason, Double.NaN, 0);
    }

    public OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct) {
        this(action, side, lots, stage, reason, premiumStopPct, 0);
    }

    public static OrderIntent exit(OptionSide side, Stage stage, String reason) {
        return new OrderIntent(Action.EXIT, side, 0, stage, reason);
    }

    /** The stop to use: this intent's own, else {@code fallback}. */
    public double stopPctOr(double fallback) {
        return Double.isNaN(premiumStopPct) ? fallback : premiumStopPct;
    }

    /** The strike to trade given the ATM strike and strike step (ITM is below spot for calls, above for puts). */
    public double strike(double atmStrike, double strikeStep) {
        if (strikeOffset == 0 || !(strikeStep > 0)) {
            return atmStrike;
        }
        return atmStrike - side.sign() * strikeOffset * strikeStep;
    }
}
