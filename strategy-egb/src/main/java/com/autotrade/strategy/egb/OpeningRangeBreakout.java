package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * 15-minute opening-range breakout with volume and OI confirmation (A-082, the user's pasted text of 12 Oct): the
 * opening range is the 09:15-09:30 candle. At a 15-minute candle close in the window (10:15-14:30 in v1) beyond the
 * range's high (low), with the futures at {@code min_futures_rvol} or more times their normal volume for the time of
 * day and the opposite side's OI building (puts on an upside break, calls on a downside break: writers defending), buy
 * the ATM call (put). Stop at the breakout candle's low (high), here the lowest (highest) minute close of the candle;
 * target {@code reward_to_risk} times the stop distance; one trade per direction per index per day.
 */
final class OpeningRangeBreakout extends SetupStrategy {

    private final double minFuturesRvol;
    private final double rewardToRisk;
    private boolean upTaken, downTaken;

    OpeningRangeBreakout(ThresholdConfig c) {
        super(c);
        minFuturesRvol = c.getDouble("setup.min_futures_rvol");
        rewardToRisk = c.getDouble("setup.reward_to_risk");
    }

    /** The snapshot at 09:45, 10:00, ... carries the close of the 15-minute candle that just ended (from 09:15). */
    static boolean fifteenMinuteClose(LocalTime time) {
        int minutes = (int) Duration.between(LocalTime.of(9, 15), time).toMinutes();
        return minutes > 0 && minutes % 15 == 0;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        if (!fifteenMinuteClose(time)) {
            return null;
        }
        StructureFeatures st = s.structure();
        double spot = close(0), high = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
        for (int back = 0; back < 15; back++) {                 // the candle's minute closes
            double v = close(back);
            if (Double.isFinite(v)) {
                high = Math.max(high, v);
                low = Math.min(low, v);
            }
        }
        boolean ready = st.orComplete() && Double.isFinite(spot);
        boolean breakUp = ready && spot > st.orHigh();
        boolean breakDown = ready && spot < st.orLow();
        boolean volume = s.futures().rvolTod() >= minFuturesRvol;    // NaN compares false
        String ceFlow = s.options().ceOiFlow(), peFlow = s.options().peOiFlow();
        boolean putOiBuilding = peFlow != null && peFlow.endsWith("BUILD");
        boolean callOiBuilding = ceFlow != null && ceFlow.endsWith("BUILD");
        c.put("break_up", breakUp);
        c.put("break_down", breakDown);
        c.put("futures_volume", volume);
        c.put("put_oi_building", putOiBuilding);
        c.put("call_oi_building", callOiBuilding);
        if (!canEnter) {
            return null;
        }
        if (breakUp && volume && putOiBuilding && !upTaken && spot - low > 0) {
            upTaken = true;
            return new Entry(OptionSide.CE, low, spot + rewardToRisk * (spot - low), "ORB_UP");
        }
        if (breakDown && volume && callOiBuilding && !downTaken && high - spot > 0) {
            downTaken = true;
            return new Entry(OptionSide.PE, high, spot - rewardToRisk * (high - spot), "ORB_DOWN");
        }
        return null;
    }
}
