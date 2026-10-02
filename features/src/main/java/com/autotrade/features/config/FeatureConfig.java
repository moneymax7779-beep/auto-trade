package com.autotrade.features.config;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.autotrade.config.ThresholdConfig;

/**
 * Typed view of {@code config/features/features.vN.yaml} plus the exchange session file. The two
 * content hashes identify exactly which definitions produced a feature snapshot.
 */
public record FeatureConfig(
        String featuresHash,
        String exchangeHash,
        Map<String, LocalTime[]> sessionWindows,
        int openingRangeMinutes,
        int atrPeriod,
        int emaFast,
        int emaSlow,
        int emaSlopeBars,
        int swingStrength,
        double retestBandAtr,
        LocalTime pdhPdlUntil,
        List<Integer> momentumWindowsSec,
        int accelerationWindowSec,
        List<Integer> basisChangeWindowsMin,
        int oiStateWindowMin,
        double oiStateMinPriceAtr,
        double oiStateMinOiFraction,
        int rvolSlotMinutes,
        Map<String, Integer> rvolSlotMinutesByUnderlying,
        double rvolMinSlotMedianVolume,
        int rvolLookbackSessions,
        int rvolMinHistorySessions,
        int rvolSlopePoints,
        int nearAtmStrikes,
        int heavyWeightStrikes,
        double outerStrikeWeight,
        List<Integer> oiDeltaWindowsMin,
        double oiFlowFlatFraction,
        List<Integer> wallWeakeningLookbackMin,
        double barrierOiWeight,
        double barrierFreshOiWeight,
        double barrierVolumeWeight,
        double proximityDecayStrikes,
        int straddleChangeWindowMin,
        List<Integer> ivChangeWindowsMin,
        double premiumResponseMinMoveAtr,
        int breadthReturnWindowMin,
        double breadthReturnScalePct,
        int concentrationTopN,
        double riskFreeRate,
        LocalTime expiryClose,
        int snapshotIntervalSec,
        String expectedMoveMethod,
        int tradingMinutesPerDay,
        LocalTime marketOpen,
        LocalTime continuousClose,
        LocalTime derivativesClose,
        LocalTime casStart,
        LocalTime casReferenceEnd,
        LocalTime casMarketAndLimitEnd,
        LocalTime casLimitOnlyEnd,
        LocalTime casEnd,
        Set<LocalDate> holidays,
        FeatureExtensions extensions,
        Map<LocalDate, String> marketEvents,
        String casSettlementMethod,
        Boolean indexIepAvailable,
        /** v7: seed the 1- and 3-minute ATR from the previous session's bars (ready at 09:15, not ~09:57). */
        boolean atrSeedPreviousSession,
        /** v8: the trailing box is the high / low of this many 1-minute bars before the latest (0 = off). */
        int boxMinutes,
        /**
         * v9: the tested zone: the low / high of this many 1-minute bars before the latest (0 = off), and
         * how many separate touches it had (bars within zoneToleranceAtr x ATR3m of it, a new touch when
         * more than zoneTouchGapMin minutes after the previous touching bar).
         */
        int zoneMinutes,
        double zoneToleranceAtr,
        int zoneTouchGapMin,
        /**
         * v10: the breakout bar's range and futures volume are compared only with 3-minute bars after the
         * opening range (the 09:15-09:29 bars are left out of both averages).
         */
        boolean breakoutExcludesOpeningRange,
        /**
         * v11: the spot VWAP proxy subtracts the basis averaged over this many minutes instead of the
         * latest tick's basis (0 = latest tick, as before). Thin futures make the tick basis jump.
         */
        int vwapBasisMinutes) {

    public static FeatureConfig from(ThresholdConfig features, ThresholdConfig exchange) {
        Map<String, LocalTime[]> windows = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : features.getMap("session_windows").entrySet()) {
            String[] parts = String.valueOf(entry.getValue()).split("-");
            windows.put(entry.getKey(), new LocalTime[] {LocalTime.parse(parts[0]), LocalTime.parse(parts[1])});
        }
        Set<LocalDate> holidays = new TreeSet<>();
        if (exchange.get("holidays") instanceof List<?> list) {
            for (Object day : list) {
                holidays.add(LocalDate.parse(String.valueOf(day)));
            }
        }
        return new FeatureConfig(
                features.contentHash(),
                exchange.contentHash(),
                Map.copyOf(windows),
                features.getInt("structure.opening_range_minutes"),
                features.getInt("structure.atr_period"),
                features.getInt("structure.ema_fast"),
                features.getInt("structure.ema_slow"),
                features.getInt("structure.ema_slope_bars"),
                features.getInt("structure.swing_strength"),
                features.getDouble("structure.retest_band_atr"),
                features.getTime("structure.pdh_pdl_until"),
                ints(features, "futures.momentum_windows_sec"),
                features.getInt("futures.acceleration_window_sec"),
                ints(features, "futures.basis_change_windows_min"),
                features.getInt("futures.oi_state_window_min"),
                features.getDouble("futures.oi_state_min_price_atr"),
                features.getDouble("futures.oi_state_min_oi_fraction"),
                features.getInt("rvol.slot_minutes"),
                intMap(features, "rvol.slot_minutes_by_underlying"),
                features.has("rvol.min_slot_median_volume") ? features.getDouble("rvol.min_slot_median_volume") : 0,
                features.getInt("rvol.lookback_sessions"),
                features.getInt("rvol.min_history_sessions"),
                features.getInt("rvol.slope_points"),
                features.getInt("options_chain.near_atm_strikes"),
                features.getInt("options_chain.heavy_weight_strikes"),
                features.getDouble("options_chain.outer_strike_weight"),
                ints(features, "options_chain.oi_delta_windows_min"),
                features.getDouble("options_chain.oi_flow_flat_fraction"),
                ints(features, "options_chain.wall_weakening_lookback_min"),
                features.getDouble("options_chain.barrier_weights.oi"),
                features.getDouble("options_chain.barrier_weights.fresh_oi"),
                features.getDouble("options_chain.barrier_weights.volume"),
                features.getDouble("options_chain.proximity_decay_strikes"),
                features.getInt("options_chain.straddle_change_window_min"),
                ints(features, "options_chain.iv_change_windows_min"),
                features.getDouble("options_chain.premium_response_min_move_atr"),
                features.getInt("breadth.return_window_min"),
                features.getDouble("breadth.return_scale_pct"),
                features.getInt("breadth.concentration_top_n"),
                features.getDouble("greeks.risk_free_rate"),
                features.getTime("greeks.expiry_close"),
                features.getInt("snapshot.interval_sec"),
                features.has("expected_move.method") ? features.getString("expected_move.method") : "IV_TRADING_MINUTES",
                features.has("expected_move.trading_minutes_per_day")
                        ? features.getInt("expected_move.trading_minutes_per_day") : 375,
                exchange.getTime("normal.open"),
                exchange.getTime("normal.continuous_close"),
                exchange.getTime("normal.derivatives_close"),
                exchange.getTime("cas.start"),
                exchange.getTime("cas.reference_calc_end"),
                exchange.getTime("cas.market_and_limit_end"),
                exchange.getTime("cas.limit_only_end"),
                exchange.getTime("cas.end"),
                Set.copyOf(holidays),
                FeatureExtensions.from(features),
                events(exchange),
                exchange.has("cas.settlement_method") ? exchange.getString("cas.settlement_method") : null,
                exchange.has("cas.index_iep_available") ? exchange.getBoolean("cas.index_iep_available") : null,
                features.has("structure.atr_seed_previous_session") && features.getBoolean("structure.atr_seed_previous_session"),
                features.has("structure.box_minutes") ? features.getInt("structure.box_minutes") : 0,
                features.has("structure.zone_minutes") ? features.getInt("structure.zone_minutes") : 0,
                features.has("structure.zone_tolerance_atr") ? features.getDouble("structure.zone_tolerance_atr") : Double.NaN,
                features.has("structure.zone_touch_gap_min") ? features.getInt("structure.zone_touch_gap_min") : 0,
                features.has("structure.breakout_excludes_opening_range")
                        && features.getBoolean("structure.breakout_excludes_opening_range"),
                features.has("structure.vwap_basis_minutes") ? features.getInt("structure.vwap_basis_minutes") : 0);
    }

    /** True when the file defines the v3 feature sections. */
    public boolean extended() {
        return extensions != null;
    }

    /** RVOL slot length for an underlying (a per-underlying override, else the default). */
    public int rvolSlotMinutes(String underlying) {
        return rvolSlotMinutesByUnderlying.getOrDefault(underlying, rvolSlotMinutes);
    }

    /** Scheduled market events (exchange file v3 {@code events}: date → name), empty before. */
    private static Map<LocalDate, String> events(ThresholdConfig exchange) {
        Map<LocalDate, String> events = new java.util.TreeMap<>();
        if (exchange.has("events") && exchange.get("events") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> event) {
                    events.merge(LocalDate.parse(String.valueOf(event.get("date"))), String.valueOf(event.get("name")),
                            (a, b) -> a + "; " + b);
                }
            }
        }
        return Map.copyOf(events);
    }

    private static Map<String, Integer> intMap(ThresholdConfig config, String path) {
        if (!config.has(path)) {
            return Map.of();
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        config.getNumberMap(path).forEach((key, value) -> values.put(key, value.intValue()));
        return Map.copyOf(values);
    }

    private static List<Integer> ints(ThresholdConfig config, String path) {
        return config.getDoubleList(path).stream().map(Double::intValue).toList();
    }
}
