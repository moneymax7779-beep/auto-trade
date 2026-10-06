package com.autotrade.strategy.egb;

import java.time.LocalDate;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds expiry-swing-rider instances from a versioned strategy file. */
public final class ExpirySwingRiderFactory implements StrategyFactory {

    private final ExpirySwingRiderConfig config;

    public ExpirySwingRiderFactory(ThresholdConfig config) {
        if (!ExpirySwingRider.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not an expiry-swing-rider file: " + config.sourceName());
        }
        this.config = ExpirySwingRiderConfig.from(config);
    }

    @Override
    public String id() {
        return ExpirySwingRider.ID;
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

    /** One entry at a time, the whole budget at the entry ask. */
    @Override
    public double premiumBudget() {
        return config.premiumBudget();
    }


    @Override
    public int intendedLots() {
        return config.intendedLots();
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return new ExpirySwingRider(config);
    }
}
