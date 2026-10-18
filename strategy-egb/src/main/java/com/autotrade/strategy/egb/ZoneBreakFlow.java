package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Zone break with OI flow and a futures volume surge (A-131; user 17 Oct 2026 on 7 Sep and 1 Oct NIFTY: "support and
 * resistances are dynamically formed ... came near ORL and broke, can we combine this with OI change at that or 1 to 3
 * minutes point in time to identify the trend and immediately trade?"; event study
 * docs/studies/2026-10-17-break-oi-volume-study.md). Decided on every 1-minute close, either direction, both indices.
 * <p>
 * Down (up mirrors it): this close is the first close of the day below a level the previous close was at or above.
 * The levels are ORL, PDL, the prior day's ORL, PWL (ORH, PDH, prior ORH, PWH going up) and the day's zones: three or
 * more 1-minute pivots (a close that is the extreme of the 3 closes either side, known 3 minutes later) within
 * {@code zone_tolerance_atr} ATR of each other. The near-ATM option OI agrees over 1 or 3 minutes (call OI building and
 * put OI unwinding), and the futures volume of this or the previous minute is at least {@code volume_surge_x} times the
 * median 1-minute futures volume since 09:20 → buy the ATM put.
 * <p>
 * Exit (the user's choices): the index closes back above the broken level (the nearest one, when several broke at once)
 * and at that close the OI flow has reversed (put OI building and call OI unwinding); otherwise held to flat_by. The
 * resting premium stop is the emergency stop.
 * <p>
 * v5 (A-136, the four fixes after the 9 / 12 / 16 Oct charts): a break stays open for {@code confirm_minutes} closes while
 * the index stays beyond the level, for the OI flow and volume to confirm; the volume test is the futures time-of-day
 * RVOL ({@code min_rvol_tod}); PDL reclaimed from below and PDH lost from above count; and once {@code protect_arm_atr}
 * ATR in favour the trade exits on the OI flow against it for {@code protect_flow_minutes} closes in a row or a
 * {@code protect_giveback_atr} ATR giveback from the best close.
 * v6 (A-137, {@code exits.protect_skip_expiring}): v5 without the profit protection on the index's own expiry day.
 * v9 (A-141, {@code setup.day_open_level}, {@code setup.orl_reclaim}): v2 with the day open as a level both ways and ORL
 * reclaimed from below as an up-level.
 * v10 (A-145, {@code setup.trade_underlyings}): v5 limited to NIFTY in its own file (= v7's run-wide flag), for the live set.
 */
final class ZoneBreakFlow extends SetupStrategy {

    private final double zoneToleranceAtr;
    private final int zoneMinPivots;
    private final double volumeSurgeX;
    /** A-133: "level_oi" (v1), "level" (level back alone) or "atr" (index stop stop_atr_from_entry ATR). */
    private final String stopMode;
    private final double stopAtrFromEntry;
    /** A-135: index target this many ATR(3m) from the entry (NaN = none: hold to flat_by). */
    private final double targetAtr;
    /** A-136: a break stays open for this many closes after it for the OI flow and volume to confirm (0 = same minute). */
    private final int confirmMinutes;
    /** A-136: the volume test is the futures time-of-day RVOL at least this (NaN = v1's surge over the day's median). */
    private final double minRvolTod;
    /** A-136: PDL also counts as a level broken upward (reclaimed) and PDH as one broken downward (lost). */
    private final boolean reclaimLevels;
    /** A-141: the day open counts as a level both ways, and ORL as one broken upward (reclaimed from below). */
    private final boolean dayOpenLevel;
    private final boolean orlReclaim;
    /** A-145: the underlyings this strategy may trade ("NIFTY"; comma-separated; absent = every traded underlying). */
    private final java.util.Set<String> tradeUnderlyings;
    /**
     * A-136 profit protection, armed once the index is protectArmAtr ATR in favour (NaN = off): then exit when the OI flow
     * is against the trade for protectFlowMinutes closes in a row, or the index gives back protectGivebackAtr ATR from
     * its best close.
     */
    private final double protectArmAtr;
    private final int protectFlowMinutes;
    private final double protectGivebackAtr;
    /** A-137: no profit protection on the index's own expiry day (the expiry-day trend runs are held to flat_by). */
    private final boolean protectSkipExpiring;
    /** Open breaks: {direction (+1 / -1), level, minute of the break}. */
    private final List<double[]> pending = new ArrayList<>();
    private int minute;
    private double entrySpot = Double.NaN, bestSpot = Double.NaN, curAtr = Double.NaN;
    private boolean protectArmed;
    private int flowAgainstRun;
    private final List<Double> pivots = new ArrayList<>();
    private final List<Double> dayCloses = new ArrayList<>();
    private final List<Double> volumes = new ArrayList<>();
    private final Set<String> broken = new HashSet<>();
    private double prevSpot = Double.NaN;
    private double prevVolume = Double.NaN;
    private double brokenLevel = Double.NaN;
    private boolean curFlowUp, curFlowDown;
    private double curSpot = Double.NaN;

    ZoneBreakFlow(ThresholdConfig c) {
        super(c);
        zoneToleranceAtr = c.getDouble("setup.zone_tolerance_atr");
        zoneMinPivots = c.getInt("setup.zone_min_pivots");
        volumeSurgeX = c.getDouble("setup.volume_surge_x");
        stopMode = c.has("exits.stop_mode") ? c.getString("exits.stop_mode") : "level_oi";
        stopAtrFromEntry = c.has("exits.stop_atr_from_entry") ? c.getDouble("exits.stop_atr_from_entry") : Double.NaN;
        targetAtr = c.has("exits.target_atr") ? c.getDouble("exits.target_atr") : Double.NaN;
        confirmMinutes = c.has("setup.confirm_minutes") ? c.getInt("setup.confirm_minutes") : 0;
        minRvolTod = c.has("setup.min_rvol_tod") ? c.getDouble("setup.min_rvol_tod") : Double.NaN;
        reclaimLevels = c.has("setup.reclaim_levels") && c.getBoolean("setup.reclaim_levels");
        dayOpenLevel = c.has("setup.day_open_level") && c.getBoolean("setup.day_open_level");
        orlReclaim = c.has("setup.orl_reclaim") && c.getBoolean("setup.orl_reclaim");
        tradeUnderlyings = c.has("setup.trade_underlyings")
                ? java.util.Set.of(c.getString("setup.trade_underlyings").replace(" ", "").split(",")) : null;
        protectArmAtr = c.has("exits.protect_arm_atr") ? c.getDouble("exits.protect_arm_atr") : Double.NaN;
        protectFlowMinutes = c.has("exits.protect_flow_minutes") ? c.getInt("exits.protect_flow_minutes") : Integer.MAX_VALUE;
        protectGivebackAtr = c.has("exits.protect_giveback_atr") ? c.getDouble("exits.protect_giveback_atr") : Double.NaN;
        protectSkipExpiring = c.has("exits.protect_skip_expiring") && c.getBoolean("exits.protect_skip_expiring");
    }

    /** The day's zones: clusters of at least zoneMinPivots pivots within the tolerance; their mean prices. */
    private List<Double> zones(double atr) {
        List<Double> sorted = new ArrayList<>(pivots);
        sorted.sort(null);
        List<Double> out = new ArrayList<>();
        int j = 0;
        while (j < sorted.size()) {
            int start = j;
            while (j + 1 < sorted.size() && sorted.get(j + 1) - sorted.get(start) <= zoneToleranceAtr * atr) {
                j++;
            }
            if (j - start + 1 >= zoneMinPivots) {
                double sum = 0;
                for (int k = start; k <= j; k++) {
                    sum += sorted.get(k);
                }
                out.add(sum / (j - start + 1));
            }
            j++;
        }
        return out;
    }

    /** Pivot known now: the close 3 minutes ago is the highest or lowest of the 7 closes around it. */
    private void updatePivots() {
        int n = dayCloses.size();
        if (n < 7) {
            return;
        }
        double mid = dayCloses.get(n - 4), hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (int i = n - 7; i < n; i++) {
            hi = Math.max(hi, dayCloses.get(i));
            lo = Math.min(lo, dayCloses.get(i));
        }
        if (mid == hi || mid == lo) {
            pivots.add(mid);
        }
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        if (tradeUnderlyings != null && !tradeUnderlyings.contains(s.underlying())) {
            canEnter = false;                                    // A-145: state still updates, no entries
        }
        StructureFeatures st = s.structure();
        OptionsFeatures o = s.options();
        double spot = close(0), atr = st.atr3m();
        double vol = s.futures().volumeLastMinute();
        boolean surge = false;
        if (Double.isFinite(minRvolTod)) {
            surge = s.futures().rvolTod() >= minRvolTod;            // NaN (short history) compares false
        } else if (!time.isBefore(LocalTime.of(9, 20)) && volumes.size() >= 10) {
            double med = median(volumes);
            double recent = Math.max(vol, Double.isFinite(prevVolume) ? prevVolume : 0);
            surge = med > 0 && recent >= volumeSurgeX * med;
        }
        boolean flowUp = (o.peOiChange1m() > 0 && o.ceOiChange1m() < 0) || (o.peOiChange3m() > 0 && o.ceOiChange3m() < 0);
        boolean flowDown = (o.ceOiChange1m() > 0 && o.peOiChange1m() < 0) || (o.ceOiChange3m() > 0 && o.peOiChange3m() < 0);
        curFlowUp = flowUp;
        curFlowDown = flowDown;
        curSpot = spot;
        curAtr = atr;
        minute++;

        double brokeUp = Double.NaN, brokeDown = Double.NaN;          // the nearest level broken this close
        if (Double.isFinite(prevSpot) && Double.isFinite(spot) && atr > 0 && !time.isBefore(LocalTime.of(9, 30))) {
            Map<String, Double> up = new java.util.LinkedHashMap<>(), down = new java.util.LinkedHashMap<>();
            if (st.orComplete()) {
                up.put("ORH", st.orHigh());
                down.put("ORL", st.orLow());
            }
            up.put("PDH", st.pdh());
            down.put("PDL", st.pdl());
            up.put("pORH", st.prevOrHigh());
            down.put("pORL", st.prevOrLow());
            up.put("PWH", st.pwh());
            down.put("PWL", st.pwl());
            if (dayOpenLevel) {
                up.put("OPEN", st.dayOpen());
                down.put("OPEN", st.dayOpen());
            }
            if (orlReclaim && st.orComplete()) {
                up.put("ORL^", st.orLow());                          // reclaimed from below
            }
            if (reclaimLevels) {
                up.put("PDL^", st.pdl());                            // reclaimed from below
                down.put("PDH_", st.pdh());                          // lost from above
            }
            for (double z : zones(atr)) {
                String key = "Z" + Math.round(z / (zoneToleranceAtr * atr));
                up.put(key, z);
                down.put(key, z);
            }
            for (var e : up.entrySet()) {
                double l = e.getValue();
                if (Double.isFinite(l) && prevSpot <= l && spot > l && broken.add(e.getKey() + "+")) {
                    brokeUp = Double.isFinite(brokeUp) ? Math.max(brokeUp, l) : l;
                }
            }
            for (var e : down.entrySet()) {
                double l = e.getValue();
                if (Double.isFinite(l) && prevSpot >= l && spot < l && broken.add(e.getKey() + "-")) {
                    brokeDown = Double.isFinite(brokeDown) ? Math.min(brokeDown, l) : l;
                }
            }
        }
        if (Double.isFinite(spot)) {
            dayCloses.add(spot);
            updatePivots();
        }
        if (!time.isBefore(LocalTime.of(9, 20)) && vol >= 0) {
            volumes.add((double) vol);
        }
        prevSpot = spot;
        prevVolume = vol;
        c.put("level_break_up", Double.isFinite(brokeUp));
        c.put("level_break_down", Double.isFinite(brokeDown));
        c.put("flow_up", flowUp);
        c.put("flow_down", flowDown);
        c.put("volume_surge", surge);
        if (Double.isFinite(brokeUp)) {
            pending.add(new double[] {1, brokeUp, minute});
        }
        if (Double.isFinite(brokeDown)) {
            pending.add(new double[] {-1, brokeDown, minute});
        }
        // the breaks still open: within confirm_minutes closes of the break and the index still beyond the level; the
        // nearest such level per direction (v1: confirm_minutes 0 = this close's breaks only)
        double openUp = Double.NaN, openDown = Double.NaN;
        for (var it = pending.iterator(); it.hasNext();) {
            double[] p = it.next();
            boolean beyond = p[0] > 0 ? spot > p[1] : spot < p[1];
            if (minute - p[2] > confirmMinutes || !beyond) {
                it.remove();
            } else if (p[0] > 0) {
                openUp = Double.isFinite(openUp) ? Math.max(openUp, p[1]) : p[1];
            } else {
                openDown = Double.isFinite(openDown) ? Math.min(openDown, p[1]) : p[1];
            }
        }
        c.put("break_open_up", Double.isFinite(openUp));
        c.put("break_open_down", Double.isFinite(openDown));
        if (!canEnter) {
            return null;
        }
        if (Double.isFinite(openUp) && flowUp && surge) {
            enter(openUp, spot);
            return new Entry(OptionSide.CE, indexStop(spot, atr, true), indexTarget(spot, atr, true), "ZONE_BREAK_FLOW_UP");
        }
        if (Double.isFinite(openDown) && flowDown && surge) {
            enter(openDown, spot);
            return new Entry(OptionSide.PE, indexStop(spot, atr, false), indexTarget(spot, atr, false), "ZONE_BREAK_FLOW_DOWN");
        }
        return null;
    }

    private void enter(double level, double spot) {
        brokenLevel = level;
        pending.clear();
        entrySpot = spot;
        bestSpot = spot;
        protectArmed = false;
        flowAgainstRun = 0;
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        if (!Double.isFinite(brokenLevel) || !Double.isFinite(curSpot)) {
            return null;
        }
        boolean put = side == OptionSide.PE;
        boolean levelBack = put ? curSpot > brokenLevel : curSpot < brokenLevel;
        boolean flowAgainst = put ? curFlowUp : curFlowDown;
        String stop = switch (stopMode) {
            case "level" -> levelBack ? "LEVEL_BACK" : null;
            case "atr" -> null;                             // the frame's index STOP
            default -> levelBack && flowAgainst ? "LEVEL_BACK_OI_REVERSED" : null;
        };
        if (stop != null || (protectSkipExpiring && s.regime().dteTradingDays() == 0)) {
            return stop;
        }
        return protect(put, flowAgainst);
    }

    /** A-136: the profit protection's exit reason, or null (off, not armed, or neither condition met). */
    private String protect(boolean put, boolean flowAgainst) {
        if (Double.isNaN(protectArmAtr) || !Double.isFinite(entrySpot) || !(curAtr > 0)) {
            return null;
        }
        double sign = put ? -1 : 1;
        bestSpot = put ? Math.min(bestSpot, curSpot) : Math.max(bestSpot, curSpot);
        protectArmed |= sign * (bestSpot - entrySpot) >= protectArmAtr * curAtr;
        flowAgainstRun = flowAgainst ? flowAgainstRun + 1 : 0;
        if (!protectArmed) {
            return null;
        }
        if (flowAgainstRun >= protectFlowMinutes) {
            return "PROTECT_OI_AGAINST";
        }
        return sign * (bestSpot - curSpot) >= protectGivebackAtr * curAtr ? "PROTECT_GIVEBACK" : null;
    }

    /** The index stop: stop_atr_from_entry ATR against the entry in "atr" mode, else none. */
    private double indexStop(double spot, double atr, boolean call) {
        return "atr".equals(stopMode) ? spot + (call ? -1 : 1) * stopAtrFromEntry * atr : Double.NaN;
    }

    /** The index target: target_atr ATR in the trade's direction, else none. */
    private double indexTarget(double spot, double atr, boolean call) {
        return Double.isFinite(targetAtr) ? spot + (call ? 1 : -1) * targetAtr * atr : Double.NaN;
    }
}
