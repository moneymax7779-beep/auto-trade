package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers expiry-trend-rider with {@link com.autotrade.strategy.Strategies}. */
public final class ExpiryTrendRiderProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return ExpiryTrendRider.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new ExpiryTrendRiderFactory(config);
    }
}
