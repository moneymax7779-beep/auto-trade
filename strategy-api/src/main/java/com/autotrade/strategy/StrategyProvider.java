package com.autotrade.strategy;

import com.autotrade.config.ThresholdConfig;

/**
 * A strategy implementation, found through {@link java.util.ServiceLoader}
 * ({@code META-INF/services/com.autotrade.strategy.StrategyProvider}). The config file's
 * {@code strategy:} key selects the provider.
 */
public interface StrategyProvider {

    /** The {@code strategy:} key of the files this provider reads, e.g. {@code early-confirm-runner}. */
    String strategyKey();

    StrategyFactory create(ThresholdConfig config);
}
