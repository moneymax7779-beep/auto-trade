package com.autotrade.strategy;

/**
 * What a strategy wants done, in lots of an option of the given side. Execution (contract choice,
 * sizing per account, broker orders) happens elsewhere. {@code premiumStopPct} is the resting premium
 * stop for an entry (percent below average cost); NaN means the executor's default. {@code strikeOffset}
 * is the strike in strikes from ATM: 0 = ATM, +1 = one strike in the money, −1 = one out.
 *
 * <p>{@link Action#ENTER_STRADDLE} buys a call and a put together (side null): {@code strikeOffset} 0 is
 * the ATM straddle, −1 the one-strike-OTM strangle; the executor sizes it to {@code premiumBudget}
 * (rupees, capped by free capital) and closes both legs when their combined bid reaches
 * {@code targetPct} above or {@code premiumStopPct} below the combined premium paid.
 */
public record OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct,
                          int strikeOffset, double targetPct, double premiumBudget) {

    public enum Action {
        ENTER,
        ADD,
        EXIT,
        ENTER_STRADDLE
    }

    public OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct,
                       int strikeOffset) {
        this(action, side, lots, stage, reason, premiumStopPct, strikeOffset, Double.NaN, Double.NaN);
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

    /** Buy a call and a put together, sized to {@code premiumBudget}, with a combined target and stop. */
    public static OrderIntent straddle(int strikeOffset, double premiumBudget, double targetPct, double stopPct,
                                       Stage stage, String reason) {
        return new OrderIntent(Action.ENTER_STRADDLE, null, 0, stage, reason, stopPct, strikeOffset, targetPct,
                premiumBudget);
    }

    /** The strike of one straddle leg: offset 0 is ATM for both; −1 puts each leg one strike out of the money. */
    public double legStrike(OptionSide leg, double atmStrike, double strikeStep) {
        if (strikeOffset == 0 || !(strikeStep > 0)) {
            return atmStrike;
        }
        return atmStrike - leg.sign() * strikeOffset * strikeStep;
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
