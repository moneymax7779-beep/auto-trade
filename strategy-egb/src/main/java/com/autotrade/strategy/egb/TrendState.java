package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Trend state at 10:15 (A-081): on the index that does not expire today, at the first 3-minute close in the window
 * (10:15-10:30 in v1) at which EMA20 of 3-minute bars exists and the index is below the session VWAP, EMA9 is below
 * EMA20 and the index is below the opening-range low, buy the ATM put; mirrored above VWAP / EMA9 above EMA20 / above
 * the ORH, the call. One trade per index per day. Index stop at the session VWAP at entry; the frame's VWAP trail exits
 * on a 3-minute close back through the VWAP; no target; 15:15.
 */
final class TrendState extends SetupStrategy {

    private boolean taken;

    TrendState(ThresholdConfig c) {
        super(c);
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        if (!threeMinuteClose(time)) {
            return null;                                     // decisions on 3-minute closes only (bars from 09:15)
        }
        double spot = close(0), vwap = st.vwapSpotProxy(), ema9 = st.ema9(), ema20 = st.ema20();
        boolean ready = st.orComplete() && Double.isFinite(vwap) && Double.isFinite(ema9) && Double.isFinite(ema20)
                && Double.isFinite(spot);
        boolean down = ready && spot < vwap && ema9 < ema20 && spot < st.orLow();
        boolean up = ready && spot > vwap && ema9 > ema20 && spot > st.orHigh();
        c.put("ema20_ready", Double.isFinite(ema20));
        c.put("trend_down", down);
        c.put("trend_up", up);
        if (!canEnter || taken || !(up || down)) {
            return null;
        }
        taken = true;
        return up ? new Entry(OptionSide.CE, vwap, Double.NaN, "TREND_STATE_UP")
                : new Entry(OptionSide.PE, vwap, Double.NaN, "TREND_STATE_DOWN");
    }
}
