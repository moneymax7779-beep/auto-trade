package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * E. Range-edge fade (A-039): a confirmed range (VWAP crossings, a narrow span, no new extreme lately); a close just
 * beyond its edge reclaimed within minutes -> trade back toward the other edge.
 */
final class RangeFade extends SetupStrategy {

    private final int lookback, minCrossings, noExtremeMin, reclaimWithinMin;
    private final double maxSpanAtr, maxSweepAtr, targetInsetAtr;
    private final ArrayDeque<double[]> history = new ArrayDeque<>();   // [close, close - vwap] per minute
    private double dayHigh = Double.NaN, dayLow = Double.NaN;
    private LocalTime extremeAt;
    // an edge sweep in progress: which edge, the frozen range, when, the extreme close during it
    private Boolean sweepLow;
    private double rangeLow, rangeHigh, sweepExtreme;
    private LocalTime sweepAt;

    RangeFade(ThresholdConfig c) {
        super(c);
        lookback = c.getInt("setup.lookback_min");
        minCrossings = c.getInt("setup.min_vwap_crossings");
        maxSpanAtr = c.getDouble("setup.max_span_atr");
        noExtremeMin = c.getInt("setup.no_new_extreme_min");
        maxSweepAtr = c.getDouble("setup.max_sweep_atr");
        reclaimWithinMin = c.getInt("setup.reclaim_within_min");
        targetInsetAtr = c.getDouble("setup.target_inset_atr");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        LocalTime lastExtremeBefore = extremeAt;                      // the range is read from the minutes before this one
        if (Double.isFinite(st.dayHigh()) && (Double.isNaN(dayHigh) || st.dayHigh() > dayHigh)) {
            dayHigh = st.dayHigh();
            extremeAt = time;
        }
        if (Double.isFinite(st.dayLow()) && (Double.isNaN(dayLow) || st.dayLow() < dayLow)) {
            dayLow = st.dayLow();
            extremeAt = time;
        }
        double atr = st.atr3m(), close = close(0), vwap = st.vwapSpotProxy();
        // the range is read from the minutes before this one
        boolean full = history.size() >= lookback;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        int crossings = 0;
        double lastSign = 0;
        for (double[] h : history) {
            lo = Math.min(lo, h[0]);
            hi = Math.max(hi, h[0]);
            double sign = Math.signum(h[1]);
            if (sign != 0 && lastSign != 0 && sign != lastSign) crossings++;
            if (sign != 0) lastSign = sign;
        }
        boolean ready = atr > 0 && Double.isFinite(close) && full;
        boolean range = ready && crossings >= minCrossings && hi - lo <= maxSpanAtr * atr
                && lastExtremeBefore != null && !time.isBefore(lastExtremeBefore.plusMinutes(noExtremeMin));
        c.put("range", range);

        Entry entry = null;
        if (sweepLow != null) {                                       // a sweep in progress
            boolean expired = time.isAfter(sweepAt.plusMinutes(reclaimWithinMin));
            boolean low = sweepLow;
            sweepExtreme = low ? Math.min(sweepExtreme, close) : Math.max(sweepExtreme, close);
            boolean tooFar = low ? close < rangeLow - maxSweepAtr * atr : close > rangeHigh + maxSweepAtr * atr;
            boolean reclaimed = low ? close > rangeLow : close < rangeHigh;
            if (expired || tooFar) {
                sweepLow = null;
            } else if (reclaimed) {
                if (canEnter) {
                    entry = low ? new Entry(OptionSide.CE, sweepExtreme, rangeHigh - targetInsetAtr * atr, "RANGE_LOW_RECLAIM")
                            : new Entry(OptionSide.PE, sweepExtreme, rangeLow + targetInsetAtr * atr, "RANGE_HIGH_RECLAIM");
                }
                sweepLow = null;
            }
        } else if (range) {
            if (close < lo && close >= lo - maxSweepAtr * atr) {
                sweepLow = true;
            } else if (close > hi && close <= hi + maxSweepAtr * atr) {
                sweepLow = false;
            }
            if (sweepLow != null) {
                rangeLow = lo;
                rangeHigh = hi;
                sweepExtreme = close;
                sweepAt = time;
            }
        }
        c.put("sweep", sweepLow != null);
        if (Double.isFinite(close) && Double.isFinite(vwap)) {
            history.addLast(new double[] {close, close - vwap});
            while (history.size() > lookback) {
                history.removeFirst();
            }
        }
        return entry;
    }
}
