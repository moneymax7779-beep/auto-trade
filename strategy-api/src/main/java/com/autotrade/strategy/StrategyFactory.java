package com.autotrade.strategy;

import java.time.LocalDate;

/** Creates fresh, per-underlying, per-session strategy instances. */
public interface StrategyFactory {

    String id();

    String configHash();

    Strategy create(String underlying, LocalDate session);

    /** Resting premium stop (percent below average cost) the executor places when an intent has none. */
    double premiumStopPct();

    /** Feature-file sections this strategy reads (the session refuses a features file without them). */
    default java.util.List<String> requiredFeatureSections() {
        return java.util.List.of();
    }

    /**
     * Rupees of premium the executor sizes a whole position to (a file's {@code sizing.premium_budget});
     * NaN = trade the strategy's lots as they are. With a budget, the strategy's lots are units of its
     * {@link #intendedLots()} plan, scaled so the full plan costs the budget at the entry price.
     */
    default double premiumBudget() {
        return Double.NaN;
    }

    /**
     * The budget on a day when any traded underlying expires (the executor decides that from the
     * option quotes); NaN = {@link #premiumBudget()} every day.
     */
    default double expiryDayPremiumBudget() {
        return Double.NaN;
    }

    /** The lots a full position of this strategy adds up to (the unit of {@link #premiumBudget()}). */
    default int intendedLots() {
        return 0;
    }

    /**
     * Pyramid protection ({@code exits.pyramid_risk_cap}): when true, adding to a position never raises what
     * it can lose at its resting stop above what it risked before the first add.
     */
    default boolean pyramidRiskCap() {
        return false;
    }

    /** The strategy file's version label (for reports). */
    default String version() {
        return configHash().substring(0, Math.min(19, configHash().length()));
    }
}
