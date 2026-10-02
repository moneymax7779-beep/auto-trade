package com.autotrade.strategy.egb;

import java.time.LocalDate;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds expiry-trend-rider instances from a versioned strategy file. */
public final class ExpiryTrendRiderFactory implements StrategyFactory {

    private final ExpiryTrendRiderConfig config;

    public ExpiryTrendRiderFactory(ThresholdConfig config) {
        if (!ExpiryTrendRider.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not an expiry-trend-rider file: " + config.sourceName());
        }
        this.config = ExpiryTrendRiderConfig.from(config);
    }

    @Override
    public String id() {
        return ExpiryTrendRider.ID;
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

    /** The full plan (entry + adds) costs this much at the entry ask; each entry or add is one plan lot. */
    @Override
    public double premiumBudget() {
        return config.premiumBudget();
    }

    @Override
    public boolean pyramidRiskCap() {
        return config.pyramidRiskCap();
    }

    @Override
    public int intendedLots() {
        return config.intendedLots();
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return new ExpiryTrendRider(config);
    }
}
