package com.autotrade.strategy.egb;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds the five setup families (A-036 to A-040) from their versioned files. */
public final class SetupFactory implements StrategyFactory {

    static final Map<String, Function<ThresholdConfig, Strategy>> FAMILIES = Map.of(
            "opening-reclaim", OpeningReclaim::new,
            "break-retest", BreakRetest::new,
            "failed-breakout", FailedBreakout::new,
            "range-fade", RangeFade::new,
            "auction-pressure", AuctionPressure::new);

    private final ThresholdConfig config;
    private final Function<ThresholdConfig, Strategy> family;

    public SetupFactory(ThresholdConfig config) {
        this.family = FAMILIES.get(config.strategy());
        if (family == null) {
            throw new IllegalArgumentException("not a setup-family file: " + config.sourceName());
        }
        this.config = config;
        family.apply(config);                                         // fail at load on a missing key, not mid-session
    }

    @Override
    public String id() {
        return config.strategy();
    }

    @Override
    public String configHash() {
        return config.contentHash();
    }

    @Override
    public String version() {
        return config.version();
    }

    @Override
    public double premiumStopPct() {
        return config.getDouble("exits.premium_stop_pct");
    }

    @Override
    public double premiumBudget() {
        return config.getDouble("position.premium_budget");
    }

    @Override
    public int intendedLots() {
        return config.getInt("position.intended_lots");
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return family.apply(config);
    }
}
