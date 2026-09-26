package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers expiry-gamma-breakout with {@link com.autotrade.strategy.Strategies}. */
public final class ExpiryGammaProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return ExpiryGammaStrategy.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new ExpiryGammaFactory(config);
    }
}
