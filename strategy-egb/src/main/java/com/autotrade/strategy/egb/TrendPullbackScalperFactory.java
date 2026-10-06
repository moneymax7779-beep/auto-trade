package com.autotrade.strategy.egb;

import java.time.LocalDate;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds trend-pullback-scalper instances from a versioned strategy file. */
public final class TrendPullbackScalperFactory implements StrategyFactory {

    private final TrendPullbackScalperConfig config;

    public TrendPullbackScalperFactory(ThresholdConfig config) {
        if (!TrendPullbackScalper.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not a trend-pullback-scalper file: " + config.sourceName());
        }
        this.config = TrendPullbackScalperConfig.from(config);
    }

    @Override
    public String id() {
        return TrendPullbackScalper.ID;
    }

    @Override
    public String configHash() {
        return config.hash();
    }

    @Override
    public String version() {
        return config.version();
    }

    /** The default resting stop; each entry carries its own (tighter off the expiring index). */
    @Override
    public double premiumStopPct() {
        return config.premiumStopPct();
    }

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
        return new TrendPullbackScalper(config);
    }
}
