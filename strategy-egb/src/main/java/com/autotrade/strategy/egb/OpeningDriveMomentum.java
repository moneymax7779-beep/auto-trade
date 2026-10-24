package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Opening drive momentum (A-085): at the first 3-minute close from 09:21 to 09:30 at which the index is at least
 * {@code min_move_pct} % from the day's open and still in the outer {@code max_pullback_frac} of the session's range
 * on the move's side, buy the ATM option in the move's direction (a fall: the put). Stop at the session's opposite
 * extreme so far (the day high for a put); no target; flat at {@code flat_by}. One trade per index per day. From the
 * index-point screen of 12 Oct (`.local/opening-screen`): following the drive beat fading it in Jan-Jun, Jul-Sep and Oct.
 * <p>
 * v9 (A-172, {@code setup.fresh_extreme_min}): the drive must be fresh: a put needs the session low lower than it was
 * that many minutes earlier (a call: the high higher); a stale drive skips that check, a later check may still enter.
 */
final class OpeningDriveMomentum extends SetupStrategy {

    private final double minMovePct;
    private final double maxPullbackFrac;
    private final int freshExtremeMin;
    private final java.util.ArrayDeque<double[]> extremes = new java.util.ArrayDeque<>();   // [minute, high, low]
    private boolean taken;

    OpeningDriveMomentum(ThresholdConfig c) {
        super(c);
        minMovePct = c.getDouble("setup.min_move_pct");
        maxPullbackFrac = c.getDouble("setup.max_pullback_frac");
        freshExtremeMin = c.has("setup.fresh_extreme_min") ? c.getInt("setup.fresh_extreme_min") : 0;   // 0 = off (v1-v8)
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        int minute = time.getHour() * 60 + time.getMinute();
        extremes.addLast(new double[] {minute, st.dayHigh(), st.dayLow()});
        while (extremes.size() > 30) {
            extremes.removeFirst();
        }
        if (!threeMinuteClose(time)) {
            return null;
        }
        double spot = close(0), open = st.dayOpen(), high = st.dayHigh(), low = st.dayLow();
        boolean ready = Double.isFinite(spot) && open > 0 && high > low;
        double movePct = ready ? (spot - open) / open * 100.0 : Double.NaN;
        boolean down = ready && movePct <= -minMovePct && spot - low <= maxPullbackFrac * (high - low);
        boolean up = ready && movePct >= minMovePct && high - spot <= maxPullbackFrac * (high - low);
        c.put("drive_down", down);
        c.put("drive_up", up);
        if (freshExtremeMin > 0 && (up || down)) {
            double[] then = null;                                // the session extremes freshExtremeMin minutes ago
            for (double[] e : extremes) {
                if (e[0] <= minute - freshExtremeMin) {
                    then = e;
                }
            }
            boolean fresh = then != null && (down ? low < then[2] : high > then[1]);
            c.put("fresh_drive", fresh);
            if (!fresh) {
                return null;                                     // stale: no entry at this check
            }
        }
        if (!canEnter || taken || !(up || down)) {
            return null;
        }
        taken = true;
        return up ? new Entry(OptionSide.CE, low, Double.NaN, "OPENING_DRIVE_UP")
                : new Entry(OptionSide.PE, high, Double.NaN, "OPENING_DRIVE_DOWN");
    }
}
