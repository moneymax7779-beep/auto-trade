package com.autotrade.strategy.egb;

import java.time.LocalDate;
import java.util.List;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds expiry-breakout-straddle instances from a versioned strategy file. */
public final class ExpiryBreakoutStraddleFactory implements StrategyFactory {

    private final StraddleConfig config;

    public ExpiryBreakoutStraddleFactory(ThresholdConfig config) {
        if (!ExpiryBreakoutStraddle.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not an expiry-breakout-straddle file: " + config.sourceName());
        }
        this.config = StraddleConfig.from(config);
    }

    @Override
    public String id() {
        return ExpiryBreakoutStraddle.ID;
    }

    @Override
    public String configHash() {
        return config.hash();
    }

    @Override
    public String version() {
        return config.version();
    }

    /** The combined stop (the straddle's legs have no resting stop of their own). */
    @Override
    public double premiumStopPct() {
        return config.stopPct();
    }

    @Override
    public List<String> requiredFeatureSections() {
        return List.of("levels", "compression", "gamma");
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return new ExpiryBreakoutStraddle(config);
    }
}
