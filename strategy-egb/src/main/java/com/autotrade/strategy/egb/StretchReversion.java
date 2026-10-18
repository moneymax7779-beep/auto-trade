package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Stretch reversion (A-130), both indices, every day including the expiring index. From the 13-day feature screen (9 Oct):
 * the one intraday signal with the same sign in every group of days was the 30-minute reversion of a stretch of the index
 * from VWAP, ORH or ORL measured in ATR(3m). At a 3-minute close in the window, the index at least {@code min_stretch_atr}
 * ATR below the VWAP or below the ORL, and this close above the previous 3-minute close (the turn): buy the ATM call
 * (mirrored above VWAP / ORH: the put). Target the VWAP at entry; stop {@code stop_atr_beyond} ATR beyond the day's extreme;
 * the frame's time stop ({@code time_stop_min}, the screen's 30-minute horizon), premium stop and flat-by. One trade per
 * side per index per day.
 */
final class StretchReversion extends SetupStrategy {

    private final double minStretchAtr;
    private final double stopAtrBeyond;
    private double prevClose3m = Double.NaN;
    private boolean upTaken, downTaken;

    StretchReversion(ThresholdConfig c) {
        super(c);
        minStretchAtr = c.getDouble("setup.min_stretch_atr");
        stopAtrBeyond = c.getDouble("setup.stop_atr_beyond");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        if (!threeMinuteClose(time)) {
            return null;
        }
        StructureFeatures st = s.structure();
        double spot = close(0), prev = prevClose3m, atr = st.atr3m(), vwap = st.vwapSpotProxy();
        prevClose3m = spot;
        if (!Double.isFinite(spot) || !(atr > 0) || !Double.isFinite(vwap)) {
            return null;
        }
        double belowVwap = (vwap - spot) / atr, aboveVwap = (spot - vwap) / atr;
        double belowOrl = st.orComplete() ? (st.orLow() - spot) / atr : Double.NaN;
        double aboveOrh = st.orComplete() ? (spot - st.orHigh()) / atr : Double.NaN;
        boolean stretchedDown = belowVwap >= minStretchAtr || belowOrl >= minStretchAtr;     // NaN compares false
        boolean stretchedUp = aboveVwap >= minStretchAtr || aboveOrh >= minStretchAtr;
        boolean turnUp = spot > prev, turnDown = spot < prev;
        c.put("stretched_down", stretchedDown);
        c.put("stretched_up", stretchedUp);
        c.put("turn_up", turnUp);
        c.put("turn_down", turnDown);
        if (!canEnter) {
            return null;
        }
        if (stretchedDown && turnUp && !upTaken) {
            upTaken = true;
            return new Entry(OptionSide.CE, st.dayLow() - stopAtrBeyond * atr, vwap, "STRETCH_REVERSION_UP");
        }
        if (stretchedUp && turnDown && !downTaken) {
            downTaken = true;
            return new Entry(OptionSide.PE, st.dayHigh() + stopAtrBeyond * atr, vwap, "STRETCH_REVERSION_DOWN");
        }
        return null;
    }
}
