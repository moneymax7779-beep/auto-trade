package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers expiry-swing-rider with {@link com.autotrade.strategy.Strategies}. */
public final class ExpirySwingRiderProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return ExpirySwingRider.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new ExpirySwingRiderFactory(config);
    }
}
