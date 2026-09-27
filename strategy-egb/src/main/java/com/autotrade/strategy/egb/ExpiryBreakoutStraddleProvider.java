package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers expiry-breakout-straddle with {@link com.autotrade.strategy.Strategies}. */
public final class ExpiryBreakoutStraddleProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return ExpiryBreakoutStraddle.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new ExpiryBreakoutStraddleFactory(config);
    }
}
