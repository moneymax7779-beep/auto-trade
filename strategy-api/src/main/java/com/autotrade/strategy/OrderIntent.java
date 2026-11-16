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
 *
 * <p>{@link Action#SELL_STRADDLE} (A-358) sells a call and a put together (side null), {@code lots} of each at
 * {@code strikeOffset}; the executor counts the risk file's short margin as capital in use and buys both back when
 * their combined ask reaches {@code premiumStopPct} above the combined credit. PAPER only: a signal-source session
 * refuses it, so it is never copied to a member's broker account.
 *
 * <p>{@code stopPoints} is the index-point distance from spot to the strategy's structure stop at entry
 * (NaN = the strategy has none); risk v6 sizes single-leg entries from it and the option's delta.
 */
public record OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct,
                          int strikeOffset, double targetPct, double premiumBudget, double stopPoints) {

    /**
     * A-285: as a straddle's {@code strikeOffset}, the executor picks the call and the put each from ATM - 1, ATM, ATM + 1
     * strikes so that their asks are closest (ties: the pair nearest ATM).
     */
    public static final int BALANCED_PAIR = 99;

    public enum Action {
        ENTER,
        ADD,
        EXIT,
        ENTER_STRADDLE,
        /** A-358: sell a call and a put together (a short straddle); PAPER only. */
        SELL_STRADDLE
    }

    public OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct,
                       int strikeOffset, double targetPct, double premiumBudget) {
        this(action, side, lots, stage, reason, premiumStopPct, strikeOffset, targetPct, premiumBudget, Double.NaN);
    }

    public OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason, double premiumStopPct,
                       int strikeOffset) {
        this(action, side, lots, stage, reason, premiumStopPct, strikeOffset, Double.NaN, Double.NaN);
    }

    /** This intent with the structure stop's distance from spot, in index points. */
    public OrderIntent withStopPoints(double points) {
        return new OrderIntent(action, side, lots, stage, reason, premiumStopPct, strikeOffset, targetPct, premiumBudget,
                points);
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

    /**
     * A-358: sell {@code lots} of a call and a put together at {@code strikeOffset} (0 = ATM); the executor buys both
     * back when their combined ask is {@code stopPct} above the combined credit. No target: a short straddle that is not
     * stopped is held (the strategy exits it, or it settles at expiry).
     */
    public static OrderIntent shortStraddle(int strikeOffset, int lots, double stopPct, Stage stage, String reason) {
        return new OrderIntent(Action.SELL_STRADDLE, null, lots, stage, reason, stopPct, strikeOffset, Double.NaN,
                Double.NaN);
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
