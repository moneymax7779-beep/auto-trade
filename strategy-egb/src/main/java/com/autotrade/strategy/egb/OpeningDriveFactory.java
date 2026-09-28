package com.autotrade.strategy.egb;

import java.time.LocalDate;
import java.util.List;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds opening-drive instances from a versioned strategy file. */
public final class OpeningDriveFactory implements StrategyFactory {

    private final OpeningDriveConfig config;

    public OpeningDriveFactory(ThresholdConfig config) {
        if (!OpeningDrive.ID.equals(config.strategy())) {
            throw new IllegalArgumentException("not an opening-drive file: " + config.sourceName());
        }
        this.config = OpeningDriveConfig.from(config);
    }

    @Override
    public String id() {
        return OpeningDrive.ID;
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

    /** The whole position is one entry: 1 plan lot, scaled so it costs the budget at the entry ask. */
    @Override
    public double premiumBudget() {
        return config.premiumBudget();
    }

    /** v2: a smaller budget when an index expires today, keeping capital for the expiry-day strategies. */
    @Override
    public double expiryDayPremiumBudget() {
        return config.expiryDayPremiumBudget();
    }

    @Override
    public int intendedLots() {
        return 1;
    }

    @Override
    public List<String> requiredFeatureSections() {
        return List.of();
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return new OpeningDrive(config);
    }
}
