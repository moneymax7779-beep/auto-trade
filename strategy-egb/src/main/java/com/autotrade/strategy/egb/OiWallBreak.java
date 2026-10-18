package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * OI wall break (A-119), on the index that does not expire today, decided on 3-minute closes.
 * <p>
 * Call side (puts mirror it): the session low is at least {@code base_min} minutes old; at the previous 3-minute close
 * the strongest call barrier above the index (strike W) was being built (its OI up over 10 minutes) and the put support
 * below was being written (its OI up over 10 minutes); this close is above W, near-ATM call OI has fallen over the last
 * 3 minutes (writers covering), the futures trade at least {@code min_futures_rvol} times their normal volume for the
 * time of day, and the index is above the session VWAP: buy the ATM call. Index stop {@code stop_atr_inside} ATR back
 * below W (a failed break); exit on a 3-minute close back through the VWAP; no target; one trade per side per day.
 * The wall is remembered from the previous close because the barrier feature moves to the next strike once crossed.
 */
final class OiWallBreak extends SetupStrategy {

    private final int baseMin;
    private final double minFuturesRvol;
    private final double stopAtrInside;
    private double prevWall = Double.NaN, prevSupport = Double.NaN;
    private boolean prevWallBuilt, prevSupportBuilt;
    private LocalTime lowAt, highAt;
    private double low = Double.NaN, high = Double.NaN;
    private boolean upTaken, downTaken;

    OiWallBreak(ThresholdConfig c) {
        super(c);
        baseMin = c.getInt("setup.base_min");
        minFuturesRvol = c.getDouble("setup.min_futures_rvol");
        stopAtrInside = c.getDouble("setup.stop_atr_inside");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        double spot = close(0);
        if (Double.isFinite(spot)) {                       // when the session's extremes were made (minute closes)
            if (Double.isNaN(low) || spot < low) { low = spot; lowAt = time; }
            if (Double.isNaN(high) || spot > high) { high = spot; highAt = time; }
        }
        if (!threeMinuteClose(time)) {
            return null;
        }
        StructureFeatures st = s.structure();
        OptionsFeatures o = s.options();
        double wall = prevWall, support = prevSupport;
        boolean wallBuilt = prevWallBuilt, supportBuilt = prevSupportBuilt;
        prevWall = o.callBarrierStrike();
        prevSupport = o.putSupportStrike();
        prevWallBuilt = o.callWallOiChangePct() > 0;          // NaN compares false
        prevSupportBuilt = o.putFloorOiChangePct() > 0;
        double atr = st.atr3m(), vwap = st.vwapSpotProxy();
        if (!Double.isFinite(spot) || !(atr > 0) || !Double.isFinite(vwap)) {
            return null;
        }
        boolean volume = s.futures().rvolTod() >= minFuturesRvol;
        boolean baseUp = lowAt != null && Duration.between(lowAt, time).toMinutes() >= baseMin;
        boolean baseDown = highAt != null && Duration.between(highAt, time).toMinutes() >= baseMin;
        boolean breakUp = Double.isFinite(wall) && spot > wall && wallBuilt && supportBuilt
                && o.ceOiChange3m() < 0 && spot > vwap && baseUp && volume;
        boolean breakDown = Double.isFinite(support) && spot < support && wallBuilt && supportBuilt
                && o.peOiChange3m() < 0 && spot < vwap && baseDown && volume;
        c.put("wall_built", wallBuilt);
        c.put("support_built", supportBuilt);
        c.put("futures_volume", volume);
        c.put("break_up", breakUp);
        c.put("break_down", breakDown);
        if (!canEnter) {
            return null;
        }
        if (breakUp && !upTaken) {
            upTaken = true;
            return new Entry(OptionSide.CE, wall - stopAtrInside * atr, Double.NaN, "OI_WALL_BREAK_UP");
        }
        if (breakDown && !downTaken) {
            downTaken = true;
            return new Entry(OptionSide.PE, support + stopAtrInside * atr, Double.NaN, "OI_WALL_BREAK_DOWN");
        }
        return null;
    }
}
