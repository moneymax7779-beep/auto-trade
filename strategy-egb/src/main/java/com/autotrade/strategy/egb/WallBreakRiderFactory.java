package com.autotrade.strategy.egb;

import java.time.LocalDate;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds wall-break-rider instances from a versioned strategy file. */
public final class WallBreakRiderFactory implements StrategyFactory {

    private final WallBreakRiderConfig config;

    public WallBreakRiderFactory(ThresholdConfig config) {
        if (!WallBreakRider.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not an wall-break-rider file: " + config.sourceName());
        }
        this.config = WallBreakRiderConfig.from(config);
    }

    @Override
    public String id() {
        return WallBreakRider.ID;
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
        return new WallBreakRider(config);
    }
}
