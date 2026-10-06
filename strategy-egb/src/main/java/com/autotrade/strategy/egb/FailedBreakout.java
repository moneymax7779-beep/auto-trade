package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * D. Failed breakout (A-038): a close through ORH / PDH / prior ORH that makes a new day high, a close back below within
 * the window, then a lower-high turn -> put; mirrored at ORL / PDL / prior ORL -> call.
 */
final class FailedBreakout extends SetupStrategy {

    private static final class Watch {
        final double level;
        final boolean up;             // the break's direction (the trade is the opposite)
        final LocalTime at;
        double extreme;               // the failed high (low)
        boolean failed, taken, dead;

        Watch(double level, boolean up, LocalTime at, double extreme) {
            this.level = level;
            this.up = up;
            this.at = at;
            this.extreme = extreme;
        }
    }

    private final int failWithinMin;
    private final double vwapMinAtr;
    private final Map<String, Watch> watches = new HashMap<>();
    private double dayHigh = Double.NaN, dayLow = Double.NaN;

    FailedBreakout(ThresholdConfig c) {
        super(c);
        failWithinMin = c.getInt("setup.fail_within_min");
        vwapMinAtr = c.getDouble("setup.vwap_target_min_atr");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        boolean newHigh = Double.isFinite(st.dayHigh()) && Double.isFinite(dayHigh) && st.dayHigh() > dayHigh;
        boolean newLow = Double.isFinite(st.dayLow()) && Double.isFinite(dayLow) && st.dayLow() < dayLow;
        if (Double.isFinite(st.dayHigh())) dayHigh = Double.isNaN(dayHigh) ? st.dayHigh() : Math.max(dayHigh, st.dayHigh());
        if (Double.isFinite(st.dayLow())) dayLow = Double.isNaN(dayLow) ? st.dayLow() : Math.min(dayLow, st.dayLow());
        double atr = st.atr3m(), close = close(0), prev = close(1), prev2 = close(2), vwap = st.vwapSpotProxy();
        if (!(atr > 0) || !Double.isFinite(close) || !Double.isFinite(prev)) {
            return null;
        }
        Map<String, Double> up = new HashMap<>(), down = new HashMap<>();
        if (st.orComplete()) {
            up.put("ORH", st.orHigh());
            down.put("ORL", st.orLow());
        }
        up.put("PDH", st.pdh());
        up.put("PRIOR_ORH", st.prevOrHigh());
        down.put("PDL", st.pdl());
        down.put("PRIOR_ORL", st.prevOrLow());
        up.forEach((n, l) -> {
            if (Double.isFinite(l) && close > l && prev <= l && newHigh) watches.put(n, new Watch(l, true, time, dayHigh));
        });
        down.forEach((n, l) -> {
            if (Double.isFinite(l) && close < l && prev >= l && newLow) watches.put(n, new Watch(l, false, time, dayLow));
        });

        Entry entry = null;
        for (Watch w : watches.values()) {
            if (w.taken || w.dead || !time.isAfter(w.at)) {
                continue;
            }
            double sign = w.up ? 1 : -1;
            if (!w.failed) {
                w.extreme = w.up ? Math.max(w.extreme, dayHigh) : Math.min(w.extreme, dayLow);
                if (time.isAfter(w.at.plusMinutes(failWithinMin))) {
                    w.dead = true;                                     // the break held: nothing to fade
                } else if (sign * (close - w.level) < 0) {
                    w.failed = true;                                   // closed back inside
                }
                continue;
            }
            if (sign * ((w.up ? dayHigh : dayLow) - w.extreme) > 0) {
                w.dead = true;                                         // a new extreme beyond the failed one
                continue;
            }
            boolean turn = Double.isFinite(prev2) && sign * (close - prev) < 0 && sign * (close - prev2) < 0;
            boolean inside = sign * (close - w.level) < 0;
            c.put((w.up ? "failed_up_" : "failed_down_") + "turn", turn && inside);
            if (canEnter && entry == null && turn && inside) {
                w.taken = true;
                boolean put = w.up;
                double vwapGap = put ? close - vwap : vwap - close;
                double target = Double.isFinite(vwap) && vwapGap >= vwapMinAtr * atr ? vwap : close + (put ? -1.5 : 1.5) * atr;
                entry = new Entry(put ? OptionSide.PE : OptionSide.CE, w.extreme, target,
                        put ? "FAILED_BREAKOUT_UP" : "FAILED_BREAKDOWN");
            }
        }
        return entry;
    }
}
