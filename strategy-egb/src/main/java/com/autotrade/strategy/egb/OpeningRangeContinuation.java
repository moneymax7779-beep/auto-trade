package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Opening-range continuation (A-076): on the index that does not expire today, the first 3-minute close beyond the
 * opening range (ORH / ORL, by at least {@code break_buffer_atr} ATR) after 09:30, in a trend (above / below the session
 * VWAP, EMA9 above / below EMA20) with the futures trading at least {@code min_futures_rvol} times their normal volume
 * for the time of day, buys the ATM option in the break's direction. No retest is waited for. Stop
 * {@code stop_atr_beyond_edge} ATR back inside the broken edge (outside one minute's noise); no target; exits on a
 * 3-minute close back through the VWAP or at {@code flat_by}. One trade per direction per day.
 * <p>
 * v2 (A-078, {@code setup.trend_before_ema20: true}): EMA20 of 3-minute bars exists only from about 10:15 (20 bars),
 * so v1's trend check is false before then and v1 cannot take a break between 09:33 and 10:15 (12 Oct NIFTY broke its
 * ORL at about 10:03). Until EMA20 exists, v2's trend is the VWAP side plus the 3-minute close on the same side of EMA9
 * (EMA9 exists from about 09:42); before EMA9 exists, the VWAP side alone. Once EMA20 exists, v2 is v1. Absent = v1.
 */
final class OpeningRangeContinuation extends SetupStrategy {

    private final double breakBufferAtr;
    private final double minFuturesRvol;
    private final double stopAtrBeyondEdge;
    private final boolean trendBeforeEma20;
    private double previousBarClose = Double.NaN;
    private boolean upTaken, downTaken;

    OpeningRangeContinuation(ThresholdConfig c) {
        super(c);
        breakBufferAtr = c.getDouble("setup.break_buffer_atr");
        minFuturesRvol = c.getDouble("setup.min_futures_rvol");
        stopAtrBeyondEdge = c.getDouble("setup.stop_atr_beyond_edge");
        trendBeforeEma20 = c.has("setup.trend_before_ema20") && c.getBoolean("setup.trend_before_ema20");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double spot = close(0), atr = st.atr3m();
        c.put("or_complete", st.orComplete());
        if (!threeMinuteClose(time)) {
            return null;                                     // decisions on 3-minute closes only (bars from 09:15)
        }
        double previous = previousBarClose;
        previousBarClose = spot;
        if (!st.orComplete() || !(atr > 0) || !Double.isFinite(spot)) {
            return null;
        }
        double up = st.orHigh() + breakBufferAtr * atr, down = st.orLow() - breakBufferAtr * atr;
        // the first 3-minute close beyond the edge: the bar before it was not already beyond
        boolean breakUp = spot > up && !(previous > up);
        boolean breakDown = spot < down && !(previous < down);
        double vwap = st.vwapSpotProxy();
        double ema9 = st.ema9(), ema20 = st.ema20();
        boolean emaUp = ema9 > ema20, emaDown = ema9 < ema20;  // NaN compares false: v1 has no trend before EMA20
        if (trendBeforeEma20 && !Double.isFinite(ema20)) {
            emaUp = !Double.isFinite(ema9) || spot > ema9;     // v2: the close on EMA9's side, or VWAP alone before EMA9
            emaDown = !Double.isFinite(ema9) || spot < ema9;
        }
        c.put("ema20_ready", Double.isFinite(ema20));
        boolean trendUp = Double.isFinite(vwap) && spot > vwap && emaUp;
        boolean trendDown = Double.isFinite(vwap) && spot < vwap && emaDown;
        double rvol = s.futures().rvolTod();
        boolean volume = rvol >= minFuturesRvol;             // NaN compares false: no volume history, no entry
        c.put("break_up", breakUp);
        c.put("break_down", breakDown);
        c.put("trend_up", trendUp);
        c.put("trend_down", trendDown);
        c.put("futures_volume", volume);
        if (!canEnter) {
            return null;
        }
        if (breakUp && trendUp && volume && !upTaken) {
            upTaken = true;
            return new Entry(OptionSide.CE, st.orHigh() - stopAtrBeyondEdge * atr, Double.NaN, "OR_CONTINUATION_UP");
        }
        if (breakDown && trendDown && volume && !downTaken) {
            downTaken = true;
            return new Entry(OptionSide.PE, st.orLow() + stopAtrBeyondEdge * atr, Double.NaN, "OR_CONTINUATION_DOWN");
        }
        return null;
    }
}
