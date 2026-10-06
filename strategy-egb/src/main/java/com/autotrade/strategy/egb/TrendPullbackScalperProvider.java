package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers trend-pullback-scalper with {@link com.autotrade.strategy.Strategies}. */
public final class TrendPullbackScalperProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return TrendPullbackScalper.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new TrendPullbackScalperFactory(config);
    }
}
