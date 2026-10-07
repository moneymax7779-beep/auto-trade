package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers wall-break-rider with {@link com.autotrade.strategy.Strategies}. */
public final class WallBreakRiderProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return WallBreakRider.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new WallBreakRiderFactory(config);
    }
}
