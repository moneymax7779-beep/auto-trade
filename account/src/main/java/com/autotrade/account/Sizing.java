package com.autotrade.account;

/**
 * How many lots a follower buys for one decision. A strategy's premium budget is set for its reference capital (the
 * capital its risk file sizes from); a follower scales it by its own equity and subscription multiplier, then by its
 * size step (the live ladder, docs/LIVE-TRADING-PLAN.md section 9): step 0 is the one-lot pilot, otherwise that share
 * of the scaled budget. The result is whole lots that fit the budget at the current ask; 0 means the entry is refused.
 */
public final class Sizing {

    private Sizing() {
    }

    /** The follower's premium budget for one entry, before the size step. */
    public static double followerBudget(double strategyBudget, double strategyReferenceCapital, double followerEquity,
                                        double sizeMultiplier) {
        if (!(strategyBudget > 0 && strategyReferenceCapital > 0 && followerEquity > 0 && sizeMultiplier > 0)) {
            return 0;
        }
        return strategyBudget * (followerEquity / strategyReferenceCapital) * sizeMultiplier;
    }

    /** Whole lots for {@code budget} at {@code ask} per unit; the pilot step (0 %) is exactly one lot if it fits. */
    public static long lots(double budget, double ask, int lotSize, double sizeStepPct) {
        if (!(ask > 0) || lotSize <= 0 || !(budget > 0)) {
            return 0;
        }
        double lotPremium = ask * lotSize;
        if (sizeStepPct == 0) {
            return lotPremium <= budget ? 1 : 0;
        }
        return (long) Math.floor(budget * sizeStepPct / 100 / lotPremium);
    }
}
