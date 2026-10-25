package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * EMA9 / EMA20 cross with a swing stop (A-066): the minute the fast average crosses the slow one, buy the ATM option in
 * the cross's direction; stop at the last swing low (high); target twice the stop distance. The SetupStrategy frame
 * handles the window, the expiring index, the exits and the size.
 * <p>
 * v2 (A-079, {@code setup.require_vwap_side: true}): a cross up enters only with the index above the session VWAP, a
 * cross down only below it (a cross against the VWAP is a bounce inside the day's trend). Absent = v1.
 * <p>
 * v8 (A-224, from the A-223 trade anatomy): optional entry filters, each absent = off: {@code setup.skip_gap_against_pct}
 * (the open gapped more than that % against the cross), {@code setup.max_vwap_against_atr} (the index more than that many
 * ATR on the wrong side of VWAP), {@code setup.no_entry_from} / {@code setup.no_entry_until} (a midday pause),
 * {@code setup.skip_counter_drift_min_pct} / {@code setup.skip_counter_drift_max_pct} (a mild move against the cross since
 * the open), {@code setup.min_stop_atr} (a swing stop closer than that many ATR).
 */
final class MaCross extends SetupStrategy {

    private final double maxStopAtr;
    private final double rewardToRisk;
    private final boolean requireVwapSide;
    private final double skipGapAgainstPct, maxVwapAgainstAtr, counterDriftMin, counterDriftMax, minStopAtr;
    private final java.time.LocalTime pauseFrom, pauseUntil;
    private double previousDiff = Double.NaN;

    MaCross(ThresholdConfig c) {
        super(c);
        maxStopAtr = c.getDouble("setup.max_stop_atr");
        rewardToRisk = c.getDouble("setup.reward_to_risk");
        requireVwapSide = c.has("setup.require_vwap_side") && c.getBoolean("setup.require_vwap_side");
        skipGapAgainstPct = c.has("setup.skip_gap_against_pct") ? c.getDouble("setup.skip_gap_against_pct") : Double.NaN;
        maxVwapAgainstAtr = c.has("setup.max_vwap_against_atr") ? c.getDouble("setup.max_vwap_against_atr") : Double.NaN;
        counterDriftMin = c.has("setup.skip_counter_drift_min_pct") ? c.getDouble("setup.skip_counter_drift_min_pct") : Double.NaN;
        counterDriftMax = c.has("setup.skip_counter_drift_max_pct") ? c.getDouble("setup.skip_counter_drift_max_pct") : Double.NaN;
        minStopAtr = c.has("setup.min_stop_atr") ? c.getDouble("setup.min_stop_atr") : Double.NaN;
        pauseFrom = c.has("setup.no_entry_from") ? c.getTime("setup.no_entry_from") : null;
        pauseUntil = c.has("setup.no_entry_until") ? c.getTime("setup.no_entry_until") : null;
        // code review 25 Oct 2026: half a pair must not silently switch its filter off
        pair(c, "setup.no_entry_from", "setup.no_entry_until");
        pair(c, "setup.skip_counter_drift_min_pct", "setup.skip_counter_drift_max_pct");
        if (pauseFrom != null && !pauseFrom.isBefore(pauseUntil)) {
            throw new IllegalArgumentException("setup.no_entry_from must be before setup.no_entry_until");
        }
    }

    private static void pair(ThresholdConfig c, String a, String b) {
        if (c.has(a) != c.has(b)) {
            throw new IllegalArgumentException(a + " and " + b + " must be set together");
        }
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double diff = st.ema9() - st.ema20();
        boolean crossUp = Double.isFinite(diff) && Double.isFinite(previousDiff) && previousDiff < 0 && diff > 0;
        boolean crossDown = Double.isFinite(diff) && Double.isFinite(previousDiff) && previousDiff > 0 && diff < 0;
        previousDiff = Double.isFinite(diff) ? diff : previousDiff;
        c.put("cross_up", crossUp);
        c.put("cross_down", crossDown);
        if (!canEnter || !(crossUp || crossDown)) {
            return null;
        }
        double spot = s.spot(), atr = st.atr3m();
        if (requireVwapSide) {
            double vwap = st.vwapSpotProxy();
            boolean withVwap = crossUp ? spot > vwap : spot < vwap;   // NaN compares false: no VWAP, no entry
            c.put("vwap_side", withVwap);
            if (!withVwap) {
                return null;
            }
        }
        double stop = crossUp ? st.lastSwingLow() : st.lastSwingHigh();
        double risk = crossUp ? spot - stop : stop - spot;
        boolean stopOk = Double.isFinite(stop) && atr > 0 && risk > 0 && risk <= maxStopAtr * atr;
        c.put("swing_stop_ok", stopOk);
        if (!stopOk) {
            return null;
        }
        if (!v8FiltersPass(st, spot, atr, risk, crossUp ? 1 : -1, time, c)) {
            return null;
        }
        double target = crossUp ? spot + rewardToRisk * risk : spot - rewardToRisk * risk;
        return new Entry(crossUp ? OptionSide.CE : OptionSide.PE, stop, target, crossUp ? "EMA_CROSS_UP" : "EMA_CROSS_DOWN");
    }

    /** A-224 filters (each off when its key is absent); records which one refused in the conditions. */
    private boolean v8FiltersPass(StructureFeatures st, double spot, double atr, double risk, int sign, LocalTime time,
                                  Map<String, Boolean> c) {
        double open = st.dayOpen(), prev = st.prevClose();
        if (!Double.isNaN(skipGapAgainstPct) && open > 0 && prev > 0 && sign * (open - prev) / prev * 100 < -skipGapAgainstPct) {
            c.put("gap_ok", false);
            return false;
        }
        double vwap = st.vwapSpotProxy();
        if (!Double.isNaN(maxVwapAgainstAtr) && Double.isFinite(vwap) && atr > 0 && sign * (spot - vwap) < -maxVwapAgainstAtr * atr) {
            c.put("vwap_ok", false);
            return false;
        }
        if (pauseFrom != null && pauseUntil != null && !time.isBefore(pauseFrom) && time.isBefore(pauseUntil)) {
            c.put("pause_ok", false);
            return false;
        }
        if (!Double.isNaN(counterDriftMin) && !Double.isNaN(counterDriftMax) && open > 0) {
            double drift = sign * (spot - open) / open * 100;
            if (drift >= -counterDriftMax && drift < -counterDriftMin) {
                c.put("drift_ok", false);
                return false;
            }
        }
        if (!Double.isNaN(minStopAtr) && atr > 0 && risk < minStopAtr * atr) {
            c.put("stop_room_ok", false);
            return false;
        }
        return true;
    }
}
