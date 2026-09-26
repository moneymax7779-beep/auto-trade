package com.autotrade.strategy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import com.autotrade.config.ThresholdConfig;

/** Loads a strategy factory from a versioned strategy file, choosing the implementation by its {@code strategy:} key. */
public final class Strategies {

    private Strategies() {
    }

    public static StrategyFactory load(Path file) {
        return create(ThresholdConfig.load(file));
    }

    public static StrategyFactory create(ThresholdConfig config) {
        String key = config.strategy();
        List<String> known = new ArrayList<>();
        for (StrategyProvider provider : ServiceLoader.load(StrategyProvider.class)) {
            if (provider.strategyKey().equals(key)) {
                return provider.create(config);
            }
            known.add(provider.strategyKey());
        }
        throw new IllegalArgumentException("no strategy implementation for '" + key + "' in " + config.sourceName()
                + " (known: " + known + ")");
    }
}
