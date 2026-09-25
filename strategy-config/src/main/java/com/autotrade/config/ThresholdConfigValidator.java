package com.autotrade.config;

import java.util.ArrayList;
import java.util.List;

/** Structural checks that catch typing mistakes in a threshold file before it is used. */
final class ThresholdConfigValidator {

    private static final double WEIGHT_TOTAL = 100.0;
    private static final double TOLERANCE = 1e-9;

    private ThresholdConfigValidator() {
    }

    static List<String> validate(ThresholdConfig config, String fileName) {
        List<String> problems = new ArrayList<>();
        for (String key : List.of("version", "strategy", "status")) {
            if (!config.has(key)) {
                problems.add("missing key: " + key);
            }
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        try {
            config.status();
        } catch (IllegalArgumentException | ThresholdConfigException e) {
            problems.add("status must be one of " + List.of(ThresholdStatus.values()));
        }
        String version = config.version();
        String versionSuffix = version.substring(version.lastIndexOf('-') + 1);
        if (!fileName.endsWith("." + versionSuffix + ".yaml") && !fileName.endsWith("." + versionSuffix + ".yml")) {
            problems.add("file name " + fileName + " does not end with the version suffix ." + versionSuffix + ".yaml");
        }
        if (!fileName.startsWith(config.strategy() + ".")) {
            problems.add("file name " + fileName + " does not start with the strategy name " + config.strategy());
        }

        checkFractionsSumToOne(config, "sizing.standard", problems);
        checkFractionsSumToOne(config, "sizing.conservative", problems);
        checkWeightsSumTo100(config, "runner.weights", problems);
        checkWeightsSumTo100(config, "cas.weights", problems);
        if (config.has("regime.weights")) {
            for (String regime : config.getMap("regime.weights").keySet()) {
                checkWeightsSumTo100(config, "regime.weights." + regime, problems);
            }
        }
        return problems;
    }

    private static void checkFractionsSumToOne(ThresholdConfig config, String path, List<String> problems) {
        if (!config.has(path)) {
            return;
        }
        double total = sum(config, path, problems);
        if (Math.abs(total - 1.0) > TOLERANCE) {
            problems.add(path + " fractions sum to " + total + ", expected 1.0");
        }
    }

    private static void checkWeightsSumTo100(ThresholdConfig config, String path, List<String> problems) {
        if (!config.has(path)) {
            return;
        }
        double total = sum(config, path, problems);
        if (Math.abs(total - WEIGHT_TOTAL) > TOLERANCE) {
            problems.add(path + " weights sum to " + total + ", expected 100");
        }
    }

    private static double sum(ThresholdConfig config, String path, List<String> problems) {
        try {
            return config.getNumberMap(path).values().stream().mapToDouble(Double::doubleValue).sum();
        } catch (ThresholdConfigException e) {
            problems.addAll(e.problems());
            return Double.NaN;
        }
    }
}
