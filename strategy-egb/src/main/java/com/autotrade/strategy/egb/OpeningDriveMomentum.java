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
 */
final class OpeningDriveMomentum extends SetupStrategy {

    private final double minMovePct;
    private final double maxPullbackFrac;
    private boolean taken;
    /** A-127: exit on a real level broken against the trade AND near-ATM OI reversed over 5 or 10 minutes; no index stop. */
    private final boolean levelOiExit;
    private double entrySpot = Double.NaN, barrierAtEntry = Double.NaN;
    /** A-128: also exit when a level broken in the trade's favour after entry is reclaimed (ORL for a put, ORH for a call...). */
    private final boolean reclaimLevels;
    /** A-128 option C: the OI condition is "no longer confirming" (5 min) or the 10-minute reversal, not only a reversal. */
    private final boolean oiNotConfirming;
    private double supportAtEntry = Double.NaN;
    private final java.util.Set<Double> brokenInFavour = new java.util.HashSet<>();
    /** A-129: take profit at a major level (PDH/PWH/PMH; puts PDL/PWL/PML) defended by option writers; NaN = off. */
    private final double defendedLevelAtr;

    OpeningDriveMomentum(ThresholdConfig c) {
        super(c);
        minMovePct = c.getDouble("setup.min_move_pct");
        maxPullbackFrac = c.getDouble("setup.max_pullback_frac");
        levelOiExit = c.has("exits.level_oi_exit") && c.getBoolean("exits.level_oi_exit");
        reclaimLevels = c.has("exits.reclaim_levels") && c.getBoolean("exits.reclaim_levels");
        oiNotConfirming = c.has("exits.oi_not_confirming") && c.getBoolean("exits.oi_not_confirming");
        defendedLevelAtr = c.has("exits.defended_level_atr") ? c.getDouble("exits.defended_level_atr") : Double.NaN;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        if (!threeMinuteClose(time)) {
            return null;
        }
        StructureFeatures st = s.structure();
        double spot = close(0), open = st.dayOpen(), high = st.dayHigh(), low = st.dayLow();
        boolean ready = Double.isFinite(spot) && open > 0 && high > low;
        double movePct = ready ? (spot - open) / open * 100.0 : Double.NaN;
        boolean down = ready && movePct <= -minMovePct && spot - low <= maxPullbackFrac * (high - low);
        boolean up = ready && movePct >= minMovePct && high - spot <= maxPullbackFrac * (high - low);
        c.put("drive_down", down);
        c.put("drive_up", up);
        if (!canEnter || taken || !(up || down)) {
            return null;
        }
        taken = true;
        entrySpot = spot;
        barrierAtEntry = up ? s.options().putSupportStrike() : s.options().callBarrierStrike();
        supportAtEntry = up ? s.options().callBarrierStrike() : s.options().putSupportStrike();   // the level beyond, in favour
        brokenInFavour.clear();
        double stopUp = levelOiExit ? Double.NaN : low, stopDown = levelOiExit ? Double.NaN : high;   // A-127: no index stop
        return up ? new Entry(OptionSide.CE, stopUp, Double.NaN, "OPENING_DRIVE_UP")
                : new Entry(OptionSide.PE, stopDown, Double.NaN, "OPENING_DRIVE_DOWN");
    }

    /**
     * A-127 exit, at a 3-minute close: the index is back through the nearest real level that was beyond the entry on the
     * trade's losing side (a put: the lowest of the day open, ORH, PDH, the prior day's ORH, PWH and the call barrier seen
     * at entry that is above the entry price; a call mirrored), AND near-ATM OI has turned against the trade over 5 or
     * 10 minutes (a put: put OI up and call OI down; "slowly reversing" = the 10-minute window).
     */
    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        if (!levelOiExit || !threeMinuteClose(time) || !Double.isFinite(entrySpot)) {
            return null;
        }
        StructureFeatures st = s.structure();
        double spot = close(0);
        boolean put = side == OptionSide.PE;
        double[] levels = put
                ? new double[] {st.dayOpen(), st.orComplete() ? st.orHigh() : Double.NaN, st.pdh(), st.prevOrHigh(), st.pwh(), barrierAtEntry}
                : new double[] {st.dayOpen(), st.orComplete() ? st.orLow() : Double.NaN, st.pdl(), st.prevOrLow(), st.pwl(), barrierAtEntry};
        double level = Double.NaN;
        for (double l : levels) {
            if (Double.isFinite(l) && (put ? l > entrySpot && !(level <= l) : l < entrySpot && !(level >= l))) {
                level = l;
            }
        }
        if (!Double.isNaN(defendedLevelAtr) && (put ? spot < entrySpot : spot > entrySpot)) {   // A-129, in profit only
            String take = defendedLevelExit(s, st, put, spot);
            if (take != null) {
                return take;
            }
        }
        boolean broken = put ? spot > level : spot < level;                       // NaN compares false
        if (reclaimLevels) {                                                       // A-128: a favourable break given back
            double[] favour = put
                    ? new double[] {st.orComplete() ? st.orLow() : Double.NaN, st.pdl(), st.prevOrLow(), st.pwl(), supportAtEntry}
                    : new double[] {st.orComplete() ? st.orHigh() : Double.NaN, st.pdh(), st.prevOrHigh(), st.pwh(), supportAtEntry};
            for (double l : favour) {
                if (Double.isFinite(l) && (put ? spot < l : spot > l)) {
                    brokenInFavour.add(l);
                }
            }
            for (double l : brokenInFavour) {
                broken |= put ? spot > l : spot < l;
            }
        }
        var o = s.options();
        boolean reversed5 = put ? o.peOiChange5m() > 0 && o.ceOiChange5m() < 0 : o.ceOiChange5m() > 0 && o.peOiChange5m() < 0;
        boolean reversed10 = put ? o.peOiChange10m() > 0 && o.ceOiChange10m() < 0 : o.ceOiChange10m() > 0 && o.peOiChange10m() < 0;
        // A-128 option C: a put is confirmed while call OI grows faster than put OI over 5 minutes (calls written / puts
        // covered); once it no longer does, the OI has stopped confirming. Calls mirrored.
        boolean notConfirming = oiNotConfirming && (put ? !(o.ceOiChange5m() > o.peOiChange5m()) : !(o.peOiChange5m() > o.ceOiChange5m()));
        return broken && (reversed5 || reversed10 || notConfirming) ? "LEVEL_OI_REVERSAL" : null;
    }

    /**
     * A-129: the index is within {@code defended_level_atr} ATR(3m) short of the nearest major level ahead (calls: PDH,
     * PWH, PMH above; puts: PDL, PWL, PML below) and the options' strongest wall (calls: the call barrier; puts: the put
     * support) is within one strike of that level with its OI up over 10 minutes: writers are defending it, take profit.
     */
    private String defendedLevelExit(FeatureSnapshot s, StructureFeatures st, boolean put, double spot) {
        double atr = st.atr3m(), step = s.options().strikeStep();
        if (!(atr > 0) || !(step > 0)) {
            return null;
        }
        double[] majors = put ? new double[] {st.pdl(), st.pwl(), st.pml()} : new double[] {st.pdh(), st.pwh(), st.pmh()};
        double ahead = Double.NaN;
        for (double l : majors) {
            if (Double.isFinite(l) && (put ? l < spot && !(ahead >= l) : l > spot && !(ahead <= l))) {
                ahead = l;
            }
        }
        if (!Double.isFinite(ahead) || Math.abs(ahead - spot) > defendedLevelAtr * atr) {
            return null;
        }
        var o = s.options();
        double wall = put ? o.putSupportStrike() : o.callBarrierStrike();
        double wallOi = put ? o.putFloorOiChangePct() : o.callWallOiChangePct();
        boolean defended = Double.isFinite(wall) && Math.abs(wall - ahead) <= step && wallOi > 0;   // NaN compares false
        return defended ? "DEFENDED_LEVEL" : null;
    }
}
