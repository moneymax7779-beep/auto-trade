package com.autotrade.strategy.egb;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProvider;

/**
 * A-227: registers {@code trend-rider}, the expiry trend rider's rules on the index that does not expire today
 * ({@code scope.dte} to {@code scope.dte_max}), with {@link com.autotrade.strategy.Strategies}.
 */
public final class TrendRiderProvider implements StrategyProvider {

    @Override
    public String strategyKey() {
        return ExpiryTrendRider.NX_ID;
    }

    @Override
    public StrategyFactory create(ThresholdConfig config) {
        return new ExpiryTrendRiderFactory(config);
    }
}
