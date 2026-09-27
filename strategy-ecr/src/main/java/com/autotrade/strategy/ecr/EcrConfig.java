package com.autotrade.strategy.ecr;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Tranches;

/** Typed view of early-confirm-runner.vN.yaml (v2 onward; {@code ext} holds the v4 additions, null before). */
record EcrConfig(
        String hash,
        String version,
        LocalTime earliestEntry,
        LocalTime lastNewEntry,
        LocalTime flatBy,
        double watchDistanceAtr,
        double earlyDistanceNormal,
        double earlyDistanceExpiry,
        double earlyMaxBeyondAtr,
        double breadthMin,
        int intendedLots,
        int minLotsForScaling,
        double[] standardFractions,
        double expiryEarlyFraction,
        double confirmRvolMin,
        double bodyRatioMin,
        double closeLocationTop,
        double wickMax,
        double rangeVsAvgMin,
        List<Window> confirmWindows,
        int closesAboveMin,
        boolean retestRequired,
        double runnerScoreMin,
        Map<String, Double> runnerWeights,
        Map<String, Map<String, Double>> regimeWeights,
        Map<String, Double> futuresStateMap,
        double volumeFullAtRvol,
        double premiumResponseGood,
        double premiumResponsePoor,
        double maxSpreadPct,
        double earlyMin,
        double premiumStopPct,
        int probeMaxMinutes,
        double probeFailAtr,
        double invalidationAtr,
        Set<String> ceRunnerExitStates,
        Set<String> peRunnerExitStates,
        int expiryStallMinutes,
        EcrExtensions ext,
        /** v8: open new campaigns only when the index expires today (DTE bucket EXPIRY); v7 and earlier: every day. */
        boolean expiryDaysOnly) {

    record Window(LocalTime from, LocalTime to, double score) {
    }

    static EcrConfig from(ThresholdConfig c) {
        String profile = c.getString("sizing.profile");
        Map<String, Double> sizing = c.getNumberMap("sizing." + profile);
        List<Window> windows = new ArrayList<>();
        c.getNumberMap("confirmation.required_confirm_score_by_window").forEach((range, score) -> {
            String[] parts = range.split("-");
            windows.add(new Window(LocalTime.parse(parts[0]), LocalTime.parse(parts[1]), score));
        });
        Map<String, Map<String, Double>> regimeWeights = new LinkedHashMap<>();
        for (String regime : c.getMap("regime.weights").keySet()) {
            regimeWeights.put(regime, c.getNumberMap("regime.weights." + regime));
        }
        return new EcrConfig(
                c.contentHash(), c.version(),
                c.getTime("rules.earliest_entry"), c.getTime("rules.last_new_entry"), c.getTime("rules.flat_by"),
                c.getDouble("rules.watch_distance_atr"),
                c.getDouble("early_entry.distance_to_orh_atr_max.normal"),
                c.getDouble("early_entry.distance_to_orh_atr_max.expiry"),
                c.getDouble("rules.early_max_beyond_level_atr"),
                c.getDouble("early_entry.weighted_breadth_min"),
                c.getInt("rules.intended_lots"), c.getInt("sizing.min_lots_for_scaling"),
                new double[] {sizing.get("early"), sizing.get("confirm"), sizing.get("runner")},
                c.getDouble("sizing.expiry_early_fraction"),
                c.getDouble("confirmation.tod_rvol_min"),
                c.getDouble("confirmation.breakout_candle.body_to_range_min"),
                c.getDouble("confirmation.breakout_candle.close_location_top_pct"),
                c.getDouble("confirmation.breakout_candle.upper_wick_max_pct"),
                c.getDouble("confirmation.breakout_candle.range_vs_avg_min"),
                List.copyOf(windows),
                c.getInt("level_acceptance.closes_above_min"), c.getBoolean("level_acceptance.retest_required"),
                c.getDouble("runner.score_min"), c.getNumberMap("runner.weights"), Map.copyOf(regimeWeights),
                c.getNumberMap("scores.futures_state_map"), c.getDouble("scores.volume_full_at_rvol"),
                c.getDouble("scores.premium_response_good"), c.getDouble("scores.premium_response_poor"),
                c.getDouble("scores.max_spread_pct"), c.getDouble("scores.early_min"),
                c.getDouble("exits.premium_stop_pct"), c.getInt("exits.early_probe_max_minutes"),
                c.getDouble("exits.early_probe_fail_atr"), c.getDouble("exits.invalidation_close_atr"),
                strings(c, "exits.runner_exit_futures_states.CE"), strings(c, "exits.runner_exit_futures_states.PE"),
                c.getInt("exits.expiry_runner_stall_minutes"),
                EcrExtensions.from(c),
                c.has("rules.expiry_days_only") && c.getBoolean("rules.expiry_days_only"));
    }

    /** Minimum confirm score for the window containing {@code time}; the last window's if none. */
    double requiredConfirmScore(LocalTime time) {
        for (Window window : confirmWindows) {
            if (!time.isBefore(window.from()) && time.isBefore(window.to())) {
                return window.score();
            }
        }
        return confirmWindows.getLast().score();
    }

    int[] tranches(boolean expiry) {
        return tranches(expiry, intendedLots);
    }

    /** Lots per tranche [early, confirm, runner] by largest remainder, summing to {@code intendedLots}. */
    int[] tranches(boolean expiry, int intendedLots) {
        double[] fractions = standardFractions.clone();
        if (expiry) {
            double rest = fractions[1] + fractions[2];
            double scale = (1 - expiryEarlyFraction) / rest;
            fractions = new double[] {expiryEarlyFraction, fractions[1] * scale, fractions[2] * scale};
        }
        return Tranches.split(intendedLots, fractions);
    }

    boolean scaling() {
        return scaling(intendedLots);
    }

    boolean scaling(int lots) {
        return lots >= minLotsForScaling;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> strings(ThresholdConfig c, String path) {
        return Set.copyOf((List<String>) c.get(path));
    }
}
