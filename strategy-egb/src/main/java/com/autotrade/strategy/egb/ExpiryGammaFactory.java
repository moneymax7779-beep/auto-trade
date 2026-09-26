package com.autotrade.strategy.egb;

import java.time.LocalDate;
import java.util.List;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds expiry-gamma-breakout instances from a versioned strategy file. */
public final class ExpiryGammaFactory implements StrategyFactory {

    private final EgbConfig config;

    public ExpiryGammaFactory(ThresholdConfig config) {
        if (!ExpiryGammaStrategy.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not an expiry-gamma-breakout file: " + config.sourceName());
        }
        this.config = EgbConfig.from(config);
    }

    @Override
    public String id() {
        return ExpiryGammaStrategy.ID;
    }

    @Override
    public String configHash() {
        return config.hash();
    }

    @Override
    public String version() {
        return config.version();
    }

    @Override
    public double premiumStopPct() {
        return config.premiumStopPct();
    }

    @Override
    public List<String> requiredFeatureSections() {
        return List.of("levels", "compression", "gamma");
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return new ExpiryGammaStrategy(config);
    }
}
