package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/** Registers opening-drive with {@link com.autotrade.strategy.Strategies}. */
public final class OpeningDriveProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return OpeningDrive.ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new OpeningDriveFactory(config);
    }
}
