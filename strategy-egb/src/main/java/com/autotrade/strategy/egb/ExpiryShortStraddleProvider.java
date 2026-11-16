package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers expiry-short-straddle with {@link com.autotrade.strategy.Strategies}. */
public final class ExpiryShortStraddleProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return ExpiryShortStraddle.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new ExpiryShortStraddleFactory(config);
    }
}
