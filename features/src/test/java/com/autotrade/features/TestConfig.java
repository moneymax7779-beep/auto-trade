package com.autotrade.features;

import java.nio.file.Path;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.config.FeatureConfig;

/** The repository's own feature and exchange files, so tests exercise the real definitions. */
public final class TestConfig {

    private TestConfig() {
    }

    public static FeatureConfig load() {
        return FeatureConfig.from(
                ThresholdConfig.load(Path.of("..", "config", "features", "features.v2.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v2.yaml")));
    }
}
