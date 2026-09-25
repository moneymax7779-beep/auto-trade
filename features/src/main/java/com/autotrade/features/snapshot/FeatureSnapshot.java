package com.autotrade.features.snapshot;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Everything the platform knew about one underlying at {@code time}, computed only from events
 * received before {@code time}. The config hashes identify the definitions used.
 */
public record FeatureSnapshot(
        Instant time,
        String underlying,
        LocalDate session,
        String phase,
        double spot,
        double secondsSinceSpot,
        double secondsSinceFutures,
        double secondsSinceOption,
        StructureFeatures structure,
        FuturesFeatures futures,
        OptionsFeatures options,
        BreadthFeatures breadth,
        RegimeFeatures regime,
        CasFeatures cas,
        String featuresHash,
        String exchangeHash) {
}
