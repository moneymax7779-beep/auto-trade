package com.autotrade.strategy.ecr;

import java.nio.file.Path;
import java.time.LocalDate;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** Builds early-confirm-runner instances from a versioned strategy file. */
public final class EarlyConfirmRunnerFactory implements StrategyFactory {

    private final EcrConfig config;
    private final double premiumBudget;

    public EarlyConfirmRunnerFactory(ThresholdConfig config) {
        if (!"early-confirm-runner".equals(config.strategy())) {
            throw new IllegalArgumentException("not an early-confirm-runner file: " + config.sourceName());
        }
        this.config = EcrConfig.from(config);
        this.premiumBudget = config.has("sizing.premium_budget") ? config.getDouble("sizing.premium_budget") : Double.NaN;
    }

    @Override
    public double premiumBudget() {
        return premiumBudget;
    }

    @Override
    public int intendedLots() {
        return config.intendedLots();
    }

    public static EarlyConfirmRunnerFactory load(Path file) {
        return new EarlyConfirmRunnerFactory(ThresholdConfig.load(file));
    }

    @Override
    public String id() {
        return "early-confirm-runner";
    }

    @Override
    public String configHash() {
        return config.hash();
    }

    public String version() {
        return config.version();
    }

    /** Resting premium stop the executor must place with every entry (percent below average cost). */
    @Override
    public java.util.List<String> requiredFeatureSections() {
        // v4 onward reads the features-v3 sections (levels, book, premium, volatility, CAS); v9 / v10 box and zone breaks
        // read the trailing box / tested zone, which only a features file with them has (A-287 review)
        java.util.List<String> sections = new java.util.ArrayList<>();
        if (config.ext() != null) {
            sections.add("levels");
        }
        if (config.boxLevels()) {
            sections.add("structure.box_minutes");
        }
        if (config.zoneLevels()) {
            sections.add("structure.zone_minutes");
        }
        return java.util.List.copyOf(sections);
    }

    @Override
    public double premiumStopPct() {
        return config.premiumStopPct();
    }

    @Override
    public Strategy create(String underlying, LocalDate session) {
        return new EarlyConfirmRunnerStrategy(config);
    }
}
