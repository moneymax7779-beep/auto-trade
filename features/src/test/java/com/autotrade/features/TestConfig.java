package com.autotrade.features;

import java.nio.file.Path;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.config.FeatureConfig;

/** The repository's own feature and exchange files, so tests exercise the real definitions. */
public final class TestConfig {

    private TestConfig() {
    }

    /** features v3: the v2 definitions plus levels, book, premium, volatility and CAS. */
    public static FeatureConfig loadV3() {
        return FeatureConfig.from(
                ThresholdConfig.load(Path.of("..", "config", "features", "features.v3.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v2.yaml")));
    }

    /** features v12 (A-090): v11 plus bar backfill, period levels and OI z-scores. */
    public static FeatureConfig loadV12() {
        return FeatureConfig.from(
                ThresholdConfig.load(Path.of("..", "config", "features", "features.v12.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
    }

    /** Any repository features file {@code features.<version>.yaml} with the live exchange calendar (v3). */
    public static FeatureConfig load(String version) {
        return FeatureConfig.from(
                ThresholdConfig.load(Path.of("..", "config", "features", "features." + version + ".yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
    }

    public static FeatureConfig load() {
        return FeatureConfig.from(
                ThresholdConfig.load(Path.of("..", "config", "features", "features.v2.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v2.yaml")));
    }
}
