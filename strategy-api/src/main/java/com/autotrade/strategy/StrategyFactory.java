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

    /** The strategy file's version label (for reports). */
    default String version() {
        return configHash().substring(0, Math.min(19, configHash().length()));
    }
}
