package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * B. Break and retest (A-037): a close through ORH / ORL / PDH / PDL, a retest within the window that holds, then a turn
 * -> trade in the break's direction.
 */
final class BreakRetest extends SetupStrategy {

    /** One break being watched: its level, direction, when, the extreme close since, and whether it retested. */
    private static final class Watch {
        final double level;
        final boolean up;
        final LocalTime at;
        double extremeClose;
        boolean retested, failed, taken;

        Watch(double level, boolean up, LocalTime at, double close) {
            this.level = level;
            this.up = up;
            this.at = at;
            this.extremeClose = close;
        }
    }

    private final int retestWithinMin;
    private final double retestAtr, maxBeyondAtr;
    private final Map<String, Watch> watches = new HashMap<>();

    BreakRetest(ThresholdConfig c) {
        super(c);
        retestWithinMin = c.getInt("setup.retest_within_min");
        retestAtr = c.getDouble("setup.retest_atr");
        maxBeyondAtr = c.getDouble("setup.max_beyond_atr");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double atr = st.atr3m(), close = close(0), prev = close(1), prev2 = close(2);
        if (!(atr > 0) || !Double.isFinite(close) || !Double.isFinite(prev)) {
            return null;
        }
        Map<String, Double> up = new HashMap<>(), down = new HashMap<>();
        if (st.orComplete()) {
            up.put("ORH", st.orHigh());
            down.put("ORL", st.orLow());
        }
        up.put("PDH", st.pdh());
        down.put("PDL", st.pdl());
        up.forEach((name, l) -> watchBreak(name, l, true, close, prev, time));
        down.forEach((name, l) -> watchBreak(name, l, false, close, prev, time));

        Entry entry = null;
        for (Watch w : watches.values()) {
            double sign = w.up ? 1 : -1;
            if (w.failed || w.taken || !time.isAfter(w.at) || time.isAfter(w.at.plusMinutes(retestWithinMin))) {
                continue;                                          // the retest comes after the break minute
            }
            w.extremeClose = w.up ? Math.min(w.extremeClose, close) : Math.max(w.extremeClose, close);
            if (sign * (w.level - close) > retestAtr * atr) {
                w.failed = true;                                   // closed too far back through: not a retest
                continue;
            }
            if (sign * (close - w.level) <= retestAtr * atr) {
                w.retested = true;
            }
            boolean turn = Double.isFinite(prev2) && sign * (close - prev) > 0 && sign * (close - prev2) > 0;
            boolean near = sign * (close - w.level) <= maxBeyondAtr * atr;
            c.put((w.up ? "up_" : "down_") + "retested", w.retested);
            c.put((w.up ? "up_" : "down_") + "turn", turn);
            if (canEnter && entry == null && w.retested && turn && near) {
                w.taken = true;
                double target = w.up ? target(close, atr, true, 0.5, 1.5, st.orHigh(), st.pdh(), st.prevOrHigh())
                        : target(close, atr, false, 0.5, 1.5, st.orLow(), st.pdl(), st.prevOrLow());
                entry = new Entry(w.up ? OptionSide.CE : OptionSide.PE, w.extremeClose, target,
                        w.up ? "BREAK_RETEST_UP" : "BREAK_RETEST_DOWN");
            }
        }
        return entry;
    }

    private void watchBreak(String name, double level, boolean up, double close, double prev, LocalTime time) {
        if (!Double.isFinite(level)) {
            return;
        }
        boolean broke = up ? close > level && prev <= level : close < level && prev >= level;
        if (broke) {
            watches.put(name, new Watch(level, up, time, close));     // a new break replaces an older one of that level
        }
    }
}
