package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.Tranches;

/** Typed view of expiry-gamma-breakout.vN.yaml. */
record EgbConfig(
        String hash,
        String version,
        int dte,
        LocalTime earliestEntry,
        LocalTime lastNewEntry,
        LocalTime flatBy,
        Map<OptionSide, List<String>> triggerLevels,
        int intendedLots,
        double[] fractions,
        boolean compressionRequired,
        double rangeVsSessionMax,
        double emaGapAtrMax,
        double volumeRateRatioMax,
        int compressionMemoryMin,
        double armedDistanceAtr,
        double earlyMaxBeyondAtr,
        double accelerationMin,
        double breadthMin,
        double bodyToRangeMin,
        double closeLocationTopPct,
        double upperWickMaxPct,
        double volumeRatioMin,
        double roomMinAtr,
        double premiumResponseMin,
        boolean requireRetest,
        double pullbackVolumeRatioMax,
        Map<OptionSide, Set<String>> runnerRejectStates,
        int probeMaxMinutes,
        double probeFailAtr,
        double invalidationAtr,
        int thetaStopMinutes,
        int stallMinutes,
        Map<OptionSide, Set<String>> exitFuturesStates,
        double premiumStopPct,
        int vwapCrossesMax,
        double breakoutWickMaxPct,
        double maxSpreadPct,
        double targetAbsDelta) {

    static EgbConfig from(ThresholdConfig c) {
        return new EgbConfig(
                c.contentHash(), c.version(),
                c.getInt("scope.dte"), c.getTime("scope.earliest_entry"), c.getTime("scope.last_new_entry"),
                c.getTime("scope.flat_by"),
                Map.of(OptionSide.CE, strings(c, "scope.trigger_levels.CE"), OptionSide.PE,
                        strings(c, "scope.trigger_levels.PE")),
                c.getInt("sizing.intended_lots"),
                new double[] {c.getDouble("sizing.fractions.early"), c.getDouble("sizing.fractions.confirm"),
                        c.getDouble("sizing.fractions.runner")},
                c.getBoolean("compression.required"), c.getDouble("compression.range_vs_session_max"),
                c.getDouble("compression.ema_gap_atr_max"), c.getDouble("compression.volume_rate_ratio_max"),
                c.getInt("compression.memory_min"),
                c.getDouble("armed.distance_atr"),
                c.getDouble("early.max_beyond_level_atr"), c.getDouble("early.acceleration_min"),
                c.getDouble("early.breadth_min"),
                c.getDouble("confirmation.body_to_range_min"), c.getDouble("confirmation.close_location_top_pct"),
                c.getDouble("confirmation.upper_wick_max_pct"), c.getDouble("confirmation.volume_ratio_min"),
                c.getDouble("confirmation.room_min_atr"), c.getDouble("confirmation.premium_response_min"),
                c.getBoolean("runner.require_retest"), c.getDouble("runner.pullback_volume_ratio_max"),
                Map.of(OptionSide.CE, Set.copyOf(strings(c, "runner.reject_futures_states.CE")),
                        OptionSide.PE, Set.copyOf(strings(c, "runner.reject_futures_states.PE"))),
                c.getInt("exits.probe_max_minutes"), c.getDouble("exits.probe_fail_atr"),
                c.getDouble("exits.invalidation_atr"), c.getInt("exits.theta_stop_minutes"),
                c.getInt("exits.stall_minutes"),
                Map.of(OptionSide.CE, Set.copyOf(strings(c, "exits.exit_futures_states.CE")),
                        OptionSide.PE, Set.copyOf(strings(c, "exits.exit_futures_states.PE"))),
                c.getDouble("exits.premium_stop_pct"),
                c.getInt("rejections.vwap_crosses_max"), c.getDouble("rejections.breakout_wick_max_pct"),
                c.getDouble("rejections.max_spread_pct"),
                c.getDouble("option.target_abs_delta"));
    }

    /** Lots per tranche [early, confirm, runner] by largest remainder, summing to the intended lots. */
    int[] tranches() {
        return Tranches.split(intendedLots, fractions);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(ThresholdConfig c, String path) {
        return List.copyOf((List<String>) c.get(path));
    }
}
