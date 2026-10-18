package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Opening-cluster break (A-139; user 18 Oct 2026, charts of 16 Oct, 23 Sep, 12 Oct, 15 Oct: the day's directional leg
 * leaving the opening cluster between 09:30 and 10:30, after odm-v4's 09:16–09:25 window and before ORB's 10:15
 * candle). Decided on every 1-minute close inside the entry window, both indices, every day.
 * <p>
 * Up (down mirrors it): this close is the first close of the day above a cluster level the previous close was at or
 * below (ORH, the day open, PDH, the previous close, the prior day's ORH; ORL, PDL, the prior day's ORL going down), and
 * the index is above the session VWAP → buy the ATM call. At most {@code max_entries} entries per index per day; the
 * second only in the opposite direction (the first leg failed: the shakeout, then the real leg). Exit: a 1-minute close
 * back through the broken level (LEVEL_BACK); otherwise held to flat_by; the resting premium stop is the emergency stop.
 */
final class OpeningClusterBreak extends SetupStrategy {

    private final int maxEntries;
    private final boolean requireVwapSide;
    private final Set<String> broken = new HashSet<>();
    private double prevSpot = Double.NaN;
    private int entries;
    private OptionSide lastSide;
    private double brokenLevel = Double.NaN;
    private double curSpot = Double.NaN;

    OpeningClusterBreak(ThresholdConfig c) {
        super(c);
        maxEntries = c.getInt("setup.max_entries");
        requireVwapSide = c.getBoolean("setup.require_vwap_side");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double spot = close(0), vwap = st.vwapSpotProxy();
        curSpot = spot;
        Map<String, Double> up = new LinkedHashMap<>(), down = new LinkedHashMap<>();
        if (st.orComplete()) {
            up.put("ORH", st.orHigh());
            down.put("ORL", st.orLow());
        }
        up.put("OPEN", st.dayOpen());
        down.put("OPEN", st.dayOpen());
        up.put("PDH", st.pdh());
        down.put("PDL", st.pdl());
        up.put("PC", st.prevClose());
        down.put("PC", st.prevClose());
        up.put("pORH", st.prevOrHigh());
        down.put("pORL", st.prevOrLow());
        double brokeUp = Double.NaN, brokeDown = Double.NaN;
        if (Double.isFinite(prevSpot) && Double.isFinite(spot)) {
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
        prevSpot = spot;
        boolean aboveVwap = !requireVwapSide || spot > vwap, belowVwap = !requireVwapSide || spot < vwap;
        c.put("cluster_break_up", Double.isFinite(brokeUp));
        c.put("cluster_break_down", Double.isFinite(brokeDown));
        c.put("vwap_side", Double.isFinite(brokeUp) ? aboveVwap : Double.isFinite(brokeDown) && belowVwap);
        c.put("entries_left", entries < maxEntries);
        if (!canEnter || entries >= maxEntries) {
            return null;
        }
        if (Double.isFinite(brokeUp) && aboveVwap && (entries == 0 || lastSide == OptionSide.PE)) {
            entries++;
            lastSide = OptionSide.CE;
            brokenLevel = brokeUp;
            return new Entry(OptionSide.CE, Double.NaN, Double.NaN, "CLUSTER_BREAK_UP");
        }
        if (Double.isFinite(brokeDown) && belowVwap && (entries == 0 || lastSide == OptionSide.CE)) {
            entries++;
            lastSide = OptionSide.PE;
            brokenLevel = brokeDown;
            return new Entry(OptionSide.PE, Double.NaN, Double.NaN, "CLUSTER_BREAK_DOWN");
        }
        return null;
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        if (!Double.isFinite(brokenLevel) || !Double.isFinite(curSpot)) {
            return null;
        }
        boolean back = side == OptionSide.PE ? curSpot > brokenLevel : curSpot < brokenLevel;
        return back ? "LEVEL_BACK" : null;
    }
}
