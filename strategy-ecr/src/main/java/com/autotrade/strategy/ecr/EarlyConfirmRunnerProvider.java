package com.autotrade.strategy.ecr;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers early-confirm-runner with {@link com.autotrade.strategy.Strategies}. */
public final class EarlyConfirmRunnerProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return "early-confirm-runner";
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new EarlyConfirmRunnerFactory(config);
    }
}
