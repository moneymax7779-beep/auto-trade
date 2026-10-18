package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * OI flow break (A-120), decided on every 1-minute close, trend continuation either way, on the index that does not
 * expire today. Up (puts mirror it): this close breaks fresh above at least one level the previous close was at or
 * below (ORH, PDH, the prior day's ORH, PWH, VWAP, the call barrier strike, the last swing high); near-ATM put OI is
 * building and near-ATM call OI unwinding (one side written, the other covered); the futures OI state agrees (fresh
 * long or short covering); futures volume at least {@code min_futures_rvol} times normal; the index above VWAP: buy the
 * ATM call. Index stop {@code stop_atr_inside} ATR back below the highest level broken; VWAP trail; one trade per side
 * per day. The OI change is the 1-minute one, or the 3-minute one when the 1-minute change is zero (BSE option OI
 * updates every 2-3 minutes).
 * <p>
 * v2 (A-121, {@code setup.require_level_break: false}): no level break is needed; the index stop is then
 * {@code stop_atr_from_entry} ATR from the entry. Absent = v1.
 * <p>
 * v3 (A-122, {@code exits.level_oi_reversal: true}, no ATR stop, no VWAP trail): the position is closed at a 1-minute
 * close when the price is back through a level that was on the other side at entry (for a put: above the nearest of
 * ORH, PDH, the prior day's ORH, PWH and the call barrier that were above the entry) or through the latest swing (a put:
 * above the last swing high; a call: below the last swing low), and at the same close the option OI flow has reversed
 * (a put: put OI building and call OI unwinding). The premium stop and flat-by stay.
 * <p>
 * v4 (A-123, {@code setup.one_trade_per_day: true}): one trade per index per day, either side: the first flow signal sets
 * the day's direction; no opposite trade after it (on the 27 tick days every later same-index trade of v2 lost).
 */
final class OiFlowBreak extends SetupStrategy {

    private static final Set<String> LONG_STATES = Set.of("FRESH_LONG", "SHORT_COVERING");
    private static final Set<String> SHORT_STATES = Set.of("FRESH_SHORT", "LONG_UNWIND");

    private final double minFuturesRvol;
    private final double stopAtrInside;
    private final boolean requireLevelBreak;
    private final double stopAtrFromEntry;
    private double prevSpot = Double.NaN;
    private double[] prevUp, prevDown;
    private boolean upTaken, downTaken;
    private final boolean levelOiReversal;
    private final boolean oneTradePerDay;
    private double againstLevel = Double.NaN;                // the nearest level on the far side at entry
    private double curSpot = Double.NaN, curSwingHigh = Double.NaN, curSwingLow = Double.NaN;
    private boolean curFlowUp, curFlowDown;

    OiFlowBreak(ThresholdConfig c) {
        super(c);
        minFuturesRvol = c.getDouble("setup.min_futures_rvol");
        stopAtrInside = c.getDouble("setup.stop_atr_inside");
        requireLevelBreak = !c.has("setup.require_level_break") || c.getBoolean("setup.require_level_break");
        stopAtrFromEntry = c.has("setup.stop_atr_from_entry") ? c.getDouble("setup.stop_atr_from_entry") : Double.NaN;
        levelOiReversal = c.has("exits.level_oi_reversal") && c.getBoolean("exits.level_oi_reversal");
        oneTradePerDay = c.has("setup.one_trade_per_day") && c.getBoolean("setup.one_trade_per_day");
    }

    private static double flow(double oneMinute, double threeMinute) {
        return oneMinute != 0 && Double.isFinite(oneMinute) ? oneMinute : threeMinute;
    }

    /** The highest level in {@code now} that this close is above while the previous close was at or below it. */
    private static double freshUp(double prevSpot, double spot, double[] before, double[] now) {
        double best = Double.NaN;
        for (int i = 0; i < now.length; i++) {
            if (Double.isFinite(now[i]) && Double.isFinite(before[i]) && prevSpot <= before[i] && spot > now[i]
                    && !(best >= now[i])) {
                best = now[i];
            }
        }
        return best;
    }

    private static double freshDown(double prevSpot, double spot, double[] before, double[] now) {
        double best = Double.NaN;
        for (int i = 0; i < now.length; i++) {
            if (Double.isFinite(now[i]) && Double.isFinite(before[i]) && prevSpot >= before[i] && spot < now[i]
                    && !(best <= now[i])) {
                best = now[i];
            }
        }
        return best;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        OptionsFeatures o = s.options();
        double spot = close(0), vwap = st.vwapSpotProxy(), atr = st.atr3m();
        double[] up = {st.orComplete() ? st.orHigh() : Double.NaN, st.pdh(), st.prevOrHigh(), st.pwh(), vwap,
                o.callBarrierStrike(), st.lastSwingHigh()};
        double[] down = {st.orComplete() ? st.orLow() : Double.NaN, st.pdl(), st.prevOrLow(), st.pwl(), vwap,
                o.putSupportStrike(), st.lastSwingLow()};
        double brokeUp = Double.NaN, brokeDown = Double.NaN;
        if (prevUp != null && Double.isFinite(prevSpot) && Double.isFinite(spot)) {
            brokeUp = freshUp(prevSpot, spot, prevUp, up);
            brokeDown = freshDown(prevSpot, spot, prevDown, down);
        }
        prevSpot = spot;
        prevUp = up;
        prevDown = down;
        double ce = flow(o.ceOiChange1m(), o.ceOiChange3m()), pe = flow(o.peOiChange1m(), o.peOiChange3m());
        String state = s.futures().oiState();
        boolean volume = s.futures().rvolTod() >= minFuturesRvol;
        boolean flowUp = pe > 0 && ce < 0 && state != null && LONG_STATES.contains(state);
        boolean flowDown = ce > 0 && pe < 0 && state != null && SHORT_STATES.contains(state);
        curSpot = spot;
        curSwingHigh = st.lastSwingHigh();
        curSwingLow = st.lastSwingLow();
        curFlowUp = pe > 0 && ce < 0;                        // option OI only: puts written, calls covered
        curFlowDown = ce > 0 && pe < 0;
        boolean levelUp = !requireLevelBreak || Double.isFinite(brokeUp);
        boolean levelDown = !requireLevelBreak || Double.isFinite(brokeDown);
        boolean goUp = levelUp && flowUp && volume && spot > vwap;
        boolean goDown = levelDown && flowDown && volume && spot < vwap;
        c.put("level_break_up", Double.isFinite(brokeUp));
        c.put("level_break_down", Double.isFinite(brokeDown));
        c.put("flow_up", flowUp);
        c.put("flow_down", flowDown);
        c.put("futures_volume", volume);
        if (!canEnter || !(atr > 0)) {
            return null;
        }
        if (goUp && !upTaken) {
            upTaken = true;
            downTaken |= oneTradePerDay;                     // v4: the first signal is the day's only trade
            double stop = requireLevelBreak ? brokeUp - stopAtrInside * atr : spot - stopAtrFromEntry * atr;
            againstLevel = nearest(down, spot, false);
            return new Entry(OptionSide.CE, stop, Double.NaN, "OI_FLOW_BREAK_UP");
        }
        if (goDown && !downTaken) {
            downTaken = true;
            upTaken |= oneTradePerDay;
            double stop = requireLevelBreak ? brokeDown + stopAtrInside * atr : spot + stopAtrFromEntry * atr;
            againstLevel = nearest(up, spot, true);
            return new Entry(OptionSide.PE, stop, Double.NaN, "OI_FLOW_BREAK_DOWN");
        }
        return null;
    }

    /** The nearest level above (or below) the price, ignoring the VWAP (index 4 of the level arrays); NaN if none. */
    private static double nearest(double[] levels, double spot, boolean above) {
        double best = Double.NaN;
        for (int i = 0; i < levels.length; i++) {
            if (i == 4 || !Double.isFinite(levels[i])) {
                continue;
            }
            if (above ? levels[i] > spot && !(best <= levels[i]) : levels[i] < spot && !(best >= levels[i])) {
                best = levels[i];
            }
        }
        return best;
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        if (!levelOiReversal || !Double.isFinite(curSpot)) {
            return null;
        }
        boolean put = side == OptionSide.PE;
        boolean levelAgainst = put ? curSpot > againstLevel : curSpot < againstLevel;          // NaN compares false
        boolean structureAgainst = put ? curSpot > curSwingHigh : curSpot < curSwingLow;
        boolean flowAgainst = put ? curFlowUp : curFlowDown;
        return (levelAgainst || structureAgainst) && flowAgainst ? "LEVEL_OI_REVERSAL" : null;
    }
}
