package com.autotrade.strategy.egb;

import java.time.LocalDate;
import java.util.List;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds expiry-short-straddle instances from a versioned strategy file (A-358). */
public final class ExpiryShortStraddleFactory implements StrategyFactory {

    private final ExpiryShortStraddleConfig config;

    public ExpiryShortStraddleFactory(ThresholdConfig config) {
        if (!ExpiryShortStraddle.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not an expiry-short-straddle file: " + config.sourceName());
        }
        this.config = ExpiryShortStraddleConfig.from(config);
    }

    @Override
    public String id() {
        return ExpiryShortStraddle.ID;
    }

    @Override
    public String configHash() {
        return config.hash();
    }

    @Override
    public String version() {
        return config.version();
    }

    /** The combined stop above the credit (the sold legs have no resting stop of their own). */
    @Override
    public double premiumStopPct() {
        return config.stopPct();
    }

    /** Reads the spot, the calendar regime and the ATM strike only: every features file has those. */
    @Override
    public List<String> requiredFeatureSections() {
        return List.of();
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return new ExpiryShortStraddle(config);
    }
}
