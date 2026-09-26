package com.autotrade.strategy.ecr;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;

/**
 * Strategy v4 additions: the volatility regime and its rule changes, order-book imbalance as a small
 * confirmation, level acceptance and breakout volume, runner gates (premium response, room, opposite
 * wall on expiry) and the closing-auction mode. Null for v1–v3 files, which then behave as before.
 */
record EcrExtensions(
        List<Bucket> volBuckets,
        Map<String, String> volPrimary,
        double realizedEscalationPct,
        Map<String, Adjustment> adjustments,
        double bookImbalanceMin,
        double bookConfirmPoints,
        double minTravelAtr,
        double minVolumeAfterBreak,
        double breakoutVolumeRatioMin,
        double premiumResponseRatioMin,
        double wallScoreMin,
        double expiryRunnerRvolMin,
        Map<String, Double> futuresStateScore,
        Cas cas) {

    /** A volatility bucket on the percentile scale: {@code from <= p < to}. */
    record Bucket(String name, double from, double to) {
    }

    /** How a volatility regime changes the rules. */
    record Adjustment(double sizeFactor, double confirmAdd, double premiumStopPct, boolean allowEarly) {
    }

    record Cas(
            boolean enabled,
            double runnerHoldMin,
            LocalTime entryFrom,
            LocalTime entryTo,
            double earlySmallMin,
            double confirmedMin,
            double highConvictionMin,
            boolean entriesRequireConstituents,
            double minConstituentCoveragePct,
            double minFuturesAlignment,
            LocalTime exitBy,
            double expiryBandAdd,
            double expiryRunnerHoldMin,
            Map<String, Double> weights,
            Set<String> constituentComponents,
            double iepReturnScalePct,
            double moveScaleAtr,
            double imbalanceChangeScale,
            boolean indexFallbackNeedsMovingIndicative) {
    }

    static EcrExtensions from(ThresholdConfig c) {
        if (!c.has("vol_regime")) {
            return null;
        }
        List<Bucket> buckets = new ArrayList<>();
        for (Map.Entry<String, Object> entry : c.getMap("regime.vix_buckets").entrySet()) {
            buckets.add(bucket(entry.getKey(), String.valueOf(entry.getValue())));
        }
        Map<String, String> primary = new LinkedHashMap<>();
        c.getMap("vol_regime.primary").forEach((underlying, source) -> primary.put(underlying, String.valueOf(source)));
        Map<String, Adjustment> adjustments = new LinkedHashMap<>();
        for (String regime : c.getMap("vol_regime.adjustments").keySet()) {
            String path = "vol_regime.adjustments." + regime;
            adjustments.put(regime, new Adjustment(c.getDouble(path + ".size_factor"), c.getDouble(path + ".confirm_add"),
                    c.getDouble(path + ".premium_stop_pct"), c.getBoolean(path + ".allow_early")));
        }
        Map<String, Double> weights = c.getNumberMap("cas.weights");
        @SuppressWarnings("unchecked")
        List<String> constituent = (List<String>) c.get("cas.requires_constituent_auction_feed");
        String[] window = c.getString("cas_mode.entry_window").split("-");
        return new EcrExtensions(
                List.copyOf(buckets), Map.copyOf(primary), c.getDouble("vol_regime.realized_escalation_pct"),
                Map.copyOf(adjustments),
                c.getDouble("order_book.imbalance_min"), c.getDouble("order_book.confirm_points"),
                c.getDouble("level_acceptance.min_travel_atr"), c.getDouble("level_acceptance.min_volume_after_break"),
                c.getDouble("confirmation.breakout_candle.volume_ratio_min"),
                c.getDouble("runner.premium_response_ratio_min"),
                c.getDouble("expiry_runner.opposite_wall_score_min"), c.getDouble("expiry_runner.rvol_min"),
                c.getNumberMap("runner.futures_state_score"),
                new Cas(c.getBoolean("cas_mode.enabled"), c.getDouble("cas_mode.runner_hold_min_score"),
                        LocalTime.parse(window[0]), LocalTime.parse(window[1]),
                        c.getDouble("cas_mode.entry_bands.EARLY_SMALL"), c.getDouble("cas_mode.entry_bands.CONFIRMED"),
                        c.getDouble("cas_mode.entry_bands.HIGH_CONVICTION"),
                        c.getBoolean("cas_mode.entries_require_constituent_data"),
                        c.getDouble("cas_mode.min_constituent_coverage_pct"),
                        c.getDouble("cas_mode.min_futures_alignment"),
                        c.getTime("cas_mode.exit_by"),
                        c.getDouble("cas_mode.expiry.band_add"), c.getDouble("cas_mode.expiry.runner_hold_min_score"),
                        Map.copyOf(weights), Set.copyOf(constituent),
                        c.getDouble("cas_mode.scales.iep_return_pct"), c.getDouble("cas_mode.scales.move_atr"),
                        c.getDouble("cas_mode.scales.imbalance_change"),
                        c.has("cas_mode.index_fallback_needs_moving_indicative")
                                && c.getBoolean("cas_mode.index_fallback_needs_moving_indicative")));
    }

    /** Rule changes for a volatility regime (NORMAL's for UNKNOWN or an unlisted regime). */
    Adjustment adjustment(String volRegime) {
        return adjustments.getOrDefault(volRegime, adjustments.get("NORMAL"));
    }

    /** Parses "<20", "20-70" or ">90" into a percentile bucket. */
    static Bucket bucket(String name, String spec) {
        String text = spec.replace(" ", "");
        if (text.startsWith("<")) {
            return new Bucket(name, Double.NEGATIVE_INFINITY, Double.parseDouble(text.substring(1)));
        }
        if (text.startsWith(">")) {
            return new Bucket(name, Double.parseDouble(text.substring(1)), Double.POSITIVE_INFINITY);
        }
        String[] parts = text.split("-");
        return new Bucket(name, Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
    }
}
