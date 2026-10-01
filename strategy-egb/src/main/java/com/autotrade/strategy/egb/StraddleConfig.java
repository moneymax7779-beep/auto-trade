package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.OptionSide;

/** Typed view of expiry-breakout-straddle.vN.yaml. */
record StraddleConfig(
        String hash,
        String version,
        int dte,
        LocalTime entryFrom,
        LocalTime entryUntil,
        LocalTime flatBy,
        Map<OptionSide, List<String>> triggerLevels,
        double rangeVsSessionMax,
        double emaGapAtrMax,
        double volumeRateRatioMax,
        int compressionMemoryMin,
        int strikeOffset,
        double premiumBudget,
        double targetPct,
        double stopPct,
        int refusedRetryMin,
        int maxTrades) {

    static StraddleConfig from(ThresholdConfig c) {
        return new StraddleConfig(c.contentHash(), c.version(), c.getInt("scope.dte"), c.getTime("scope.entry_from"),
                c.getTime("scope.entry_until"), c.getTime("scope.flat_by"),
                Map.of(OptionSide.CE, strings(c, "scope.trigger_levels.CE"), OptionSide.PE,
                        strings(c, "scope.trigger_levels.PE")),
                c.getDouble("compression.range_vs_session_max"), c.getDouble("compression.ema_gap_atr_max"),
                c.getDouble("compression.volume_rate_ratio_max"), c.getInt("compression.memory_min"),
                c.getInt("position.strike_offset"), c.getDouble("position.premium_budget"),
                c.getDouble("exits.target_pct"), c.getDouble("exits.stop_pct"), c.getInt("retry.refused_retry_min"),
                c.has("retry.max_trades") ? c.getInt("retry.max_trades") : 1);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(ThresholdConfig c, String path) {
        return List.copyOf((List<String>) c.get(path));
    }
}
