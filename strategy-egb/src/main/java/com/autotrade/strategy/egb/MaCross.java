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
 */
final class MaCross extends SetupStrategy {

    private final double maxStopAtr;
    private final double rewardToRisk;
    private final boolean requireVwapSide;
    private double previousDiff = Double.NaN;

    MaCross(ThresholdConfig c) {
        super(c);
        maxStopAtr = c.getDouble("setup.max_stop_atr");
        rewardToRisk = c.getDouble("setup.reward_to_risk");
        requireVwapSide = c.has("setup.require_vwap_side") && c.getBoolean("setup.require_vwap_side");
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
        double target = crossUp ? spot + rewardToRisk * risk : spot - rewardToRisk * risk;
        return new Entry(crossUp ? OptionSide.CE : OptionSide.PE, stop, target, crossUp ? "EMA_CROSS_UP" : "EMA_CROSS_DOWN");
    }
}
