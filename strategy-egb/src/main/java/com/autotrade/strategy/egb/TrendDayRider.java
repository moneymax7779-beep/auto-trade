package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Trend-day rider (A-064, docs/studies/2026-10-09-trend-day-rider-and-brt-runner.md): once the day has proven a trend
 * (an hour of closes beyond the session VWAP, away from the open, a fresh session extreme, breadth agreeing), buy the
 * ATM option on a pullback toward EMA20 and the turn; ride it with the frame's VWAP trail (3-minute close back through
 * the VWAP), the pullback's extreme close as the structure stop, and {@code flat_by}. Calls shown; puts mirrored.
 */
final class TrendDayRider extends SetupStrategy {

    /** One minute: its close, the EMA20 and ATR(3m) then. */
    private record Minute(LocalTime time, double close, double ema20, double atr) {
    }

    private final int vwapMinutes;
    private final double fromOpenPct;
    private final int extremeWithinMin;
    private final double breadthMin;
    private final int pullbackWithinMin;
    private final double pullbackAtr;

    private final ArrayDeque<Minute> recent = new ArrayDeque<>();
    private int aboveVwap, belowVwap;
    private double highClose = Double.NaN, lowClose = Double.NaN;
    private LocalTime highAt, lowAt;
    /** The minute of the last entry per side: a pullback must touch after it to be traded again. */
    private LocalTime lastUpEntry, lastDownEntry;

    TrendDayRider(ThresholdConfig c) {
        super(c);
        vwapMinutes = c.getInt("trend.vwap_minutes");
        fromOpenPct = c.getDouble("trend.from_open_pct");
        extremeWithinMin = c.getInt("trend.extreme_within_min");
        breadthMin = c.getDouble("trend.day_breadth_min");
        pullbackWithinMin = c.getInt("setup.pullback_within_min");
        pullbackAtr = c.getDouble("setup.pullback_atr");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double close = close(0), prev = close(1), prev2 = close(2);
        double vwap = st.vwapSpotProxy(), ema = st.ema20(), atr = st.atr3m(), open = st.dayOpen();
        if (!Double.isFinite(close)) {
            return null;
        }
        // state that every minute updates, before any entry test
        if (Double.isFinite(vwap)) {
            aboveVwap = close > vwap ? aboveVwap + 1 : 0;
            belowVwap = close < vwap ? belowVwap + 1 : 0;
        } else {
            aboveVwap = 0;
            belowVwap = 0;
        }
        if (Double.isNaN(highClose) || close > highClose) {
            highClose = close;
            highAt = time;
        }
        if (Double.isNaN(lowClose) || close < lowClose) {
            lowClose = close;
            lowAt = time;
        }
        recent.addLast(new Minute(time, close, ema, atr));
        while (recent.size() > pullbackWithinMin + 1) {
            recent.removeFirst();                              // this minute plus the previous pullbackWithinMin
        }
        if (!(atr > 0) || !Double.isFinite(ema) || !Double.isFinite(open) || !Double.isFinite(prev) || !Double.isFinite(prev2)) {
            return null;
        }
        double breadth = s.breadth() == null ? Double.NaN : s.breadth().dayBreadth();
        Entry up = side(true, time, close, prev, prev2, open, breadth, c);
        Entry down = side(false, time, close, prev, prev2, open, breadth, c);
        if (!canEnter) {
            return null;
        }
        Entry entry = up != null ? up : down;
        if (entry == up && up != null) {
            lastUpEntry = time;
        } else if (entry != null) {
            lastDownEntry = time;
        }
        return entry;
    }

    private Entry side(boolean up, LocalTime time, double close, double prev, double prev2, double open, double breadth,
                       Map<String, Boolean> c) {
        double sign = up ? 1 : -1;
        String p = up ? "up_" : "down_";
        boolean vwapHeld = (up ? aboveVwap : belowVwap) >= vwapMinutes;
        boolean fromOpen = sign * (close - open) >= fromOpenPct / 100.0 * open;
        LocalTime extremeAt = up ? highAt : lowAt;
        boolean freshExtreme = extremeAt != null && !extremeAt.isBefore(time.minusMinutes(extremeWithinMin));
        boolean breadthOk = Double.isFinite(breadth) && sign * breadth >= breadthMin;
        LocalTime lastEntry = up ? lastUpEntry : lastDownEntry;
        boolean touched = false;
        double extremeClose = close;                           // the pullback's extreme close: the structure stop
        for (Minute m : recent) {
            if (m.time().equals(time)) {
                continue;                                      // the pullback is before the turn minute
            }
            extremeClose = up ? Math.min(extremeClose, m.close()) : Math.max(extremeClose, m.close());
            boolean near = Double.isFinite(m.ema20()) && m.atr() > 0 && sign * (m.close() - m.ema20()) <= pullbackAtr * m.atr();
            if (near && (lastEntry == null || m.time().isAfter(lastEntry))) {
                touched = true;
            }
        }
        boolean turn = sign * (close - prev) > 0 && sign * (close - prev2) > 0;
        c.put(p + "vwap_held", vwapHeld);
        c.put(p + "from_open", fromOpen);
        c.put(p + "fresh_extreme", freshExtreme);
        c.put(p + "breadth", breadthOk);
        c.put(p + "pullback", touched);
        c.put(p + "turn", turn);
        if (vwapHeld && fromOpen && freshExtreme && breadthOk && touched && turn && sign * (close - extremeClose) > 0) {
            return new Entry(up ? OptionSide.CE : OptionSide.PE, extremeClose, Double.NaN,
                    up ? "TREND_DAY_PULLBACK_UP" : "TREND_DAY_PULLBACK_DOWN");
        }
        return null;
    }
}
