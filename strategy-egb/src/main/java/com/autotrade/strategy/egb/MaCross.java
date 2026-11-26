package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;

/**
 * EMA9 / EMA20 cross with a swing stop (A-066): the minute the fast average crosses the slow one, buy the ATM option in
 * the cross's direction; stop at the last swing low (high); target twice the stop distance. The SetupStrategy frame
 * handles the window, the expiring index, the exits and the size.
 * <p>
 * v2 (A-079, {@code setup.require_vwap_side: true}): a cross up enters only with the index above the session VWAP, a
 * cross down only below it (a cross against the VWAP is a bounce inside the day's trend). Absent = v1.
 * <p>
 * v34 (A-334, live from A-335, {@code exits.early_fail_min / early_fail_pct}): one look at the option N minutes after
 * entry; a bid more than the given % under the average premium exits ({@code EARLY_FAIL_N}). v20 / v37: the time stop only
 * for a trade whose index never reached +1R. The other A-332..A-334 keys (runner, spike skip, opposite-cross exit,
 * stretch exit) failed their tests and stay only for their versioned files. Every key absent = v1.
 */
final class MaCross extends SetupStrategy {

    private static final Logger log = LoggerFactory.getLogger(MaCross.class);
    private final java.util.ArrayDeque<java.time.Instant> crosses = new java.util.ArrayDeque<>();
    private final double maxStopAtr;
    private final double rewardToRisk;
    // A-653 (setup.min_target_atr, off when absent): the index target is at least this many ATR(3m) of the entry from the
    // entry, so a swing stop close to the cross no longer makes the 2R target a small one
    private final double minTargetAtr;
    private final boolean requireVwapSide;
    // A-332 (all off when absent = v1): skip a spike cross; time stop only for a trade that never reached unlessR;
    // runner: no exit at the target, the index stop trails trailR behind the best after the target (never below lockR)
    private final double maxChg1mAtr1m;
    private final int timeStopMin;
    private final double timeStopUnlessR;
    private final double runnerTrailR;
    private final double runnerLockR;
    private double entrySpot = Double.NaN, entryRisk = Double.NaN, bestR = Double.NaN;
    private java.time.Instant entryTime;
    // A-333 (off when absent = v1): an opposite cross while a trade is open closes it ("REVERSE"; "close" = no new trade)
    // and, for "reverse", enters the new side on the next minute with the cross's swing stop
    private final String onOppositeCross;
    private final double reverseOnlyBelowR;
    private OptionSide openSide;
    private Entry pendingReverse;
    private java.time.Instant pendingAt;
    private boolean oppositeExit;
    // A-334 (off when absent = v1): early-fail exit once, at earlyFailMin after entry, when the option bid is below
    // earlyFailPct % of the average premium; stretch exit when the index is stretchAtr ATR(3m) beyond EMA9 the trade's way
    private final int earlyFailMin;
    private final double earlyFailPct;
    private final double stretchAtr;
    private boolean earlyChecked;
    private static final int EARLY_FAIL_GRACE_MIN = 5;
    // A-670 (off when absent): once the index has gone giveBackAfterAtr ATR(3m at entry) the trade's way, exit when it has
    // given back giveBackFrac of that best move; with giveBackVwapGate only while the spot is on the wrong side of VWAP
    private final double giveBackAfterAtr;
    private final double giveBackFrac;
    private final boolean giveBackVwapGate;
    private double entryAtr = Double.NaN, bestAtr = Double.NaN;
    private double previousDiff = Double.NaN;
    // A-537 (ma-cross-open): cross, swing stop and ATR from the indicators carried over from the previous session
    private final boolean carried;
    // A-546 (off when absent): skip a cross in the opening gap's direction before gapSkipUntil when |gap| >= gapSkipMinPct;
    // levelBreak: a cross arms a wait for the nearest level beyond the index in its direction (OR high/low, prior-day
    // high/low/close, prior-week and prior-month high/low) and enters when the index trades beyond it while the averages
    // still agree (an opposite flip drops the wait)
    private final LocalTime gapSkipUntil;
    private final double gapSkipMinPct;
    private final boolean levelBreak;
    private OptionSide awaitSide;
    // A-547 (exits.level_hold, off when absent): entry as before; the 2R target and the time stop are this class's
    // (frame target and time stop must be off). At entry the nearest level beyond the index in the trade's direction is
    // noted. A target or time exit that comes with the index within near_atr ATR of that unbroken level and the carried
    // averages still agreeing waits instead; once the index trades beyond the level the trade rides with no target or time
    // stop (the broken level ratchets to each further level broken) until the averages cross against it, the index comes
    // back fail_atr ATR across the broken level, the swing / premium stop, or flat_by. While waiting at the level, a
    // flip of the averages or a move reject_atr ATR back away from it exits.
    private final boolean levelHold;
    private final int holdTimeMin;
    private final double nearAtr, failAtr, rejectAtr;
    private double holdLevel = Double.NaN;
    private boolean riding, deferred;
    private double awaitLevel = Double.NaN;
    // A-559 (exits.structure_hold, off when absent): the 2R target and the time stop are this class's (frame target and
    // time stop must be off). When either would fire, the trade holds while, at the minute close (each snapshot is one:
    // features snapshot.interval_sec 60; review 22 Nov 2026: judged on the snapshot itself, not the one before), the
    // index is on its side of the session VWAP, today's 3-min EMA9 is beyond today's EMA20 in its direction and the index
    // has not crossed today's last confirmed 3-min swing low (high for a put); any missing value = no support. While
    // holding, the first minute close without that support exits (HOLD_STRUCTURE); swing / premium stop and flat_by stay.
    private final boolean structureHold;
    /** A-649: past the 60-min time stop, hold while near-ATM OI flow (5 and 10 min), EMA20 side and (optionally) futures RVOL agree. */
    private final boolean dataHold;
    private final double dataHoldRvolMin;
    private boolean dataHolding;
    private final int structureHoldTimeMin;
    // A-679 (default true): false = the hold is judged only at the target; the time stop exits regardless
    private final boolean structureHoldAtTimeStop;
    private boolean structureHolding;
    // A-560 (exits.structure_hold_floor_atr, off when absent): the index at the minute close where the hold started is a
    // floor; a later minute close back beyond it by floor_atr x the carried ATR(3m) noted then exits (HOLD_FLOOR)
    private final double holdFloorAtr;
    private double holdFloor = Double.NaN, holdFloorAtrValue = Double.NaN;
    // A-562 (exits.structure_hold_on: bar3m; default minute): the hold's support and floor judged on the last completed
    // 3-minute bar's close (structure.lastBarClose), acted on only when a new 3-minute bar has closed (its close changed or
    // a 3-minute boundary); the hold's evidence (VWAP, EMA9/20, swings) is 3-minute. A hold the 2R target starts mid-bar
    // is judged, and floored, on the previous bar's close (review 22 Nov 2026: as registered; a target-touch floor is a
    // separate test)
    private final boolean holdOnBar3m;
    private double lastBarSeen = Double.NaN;

    MaCross(ThresholdConfig c) {
        super(c);
        maxStopAtr = c.getDouble("setup.max_stop_atr");
        carried = c.has("setup.carried_emas") && c.getBoolean("setup.carried_emas");
        gapSkipUntil = c.has("setup.gap_skip_until") ? LocalTime.parse(c.getString("setup.gap_skip_until")) : null;
        gapSkipMinPct = c.has("setup.gap_skip_min_pct") ? c.getDouble("setup.gap_skip_min_pct") : Double.NaN;
        levelBreak = c.has("setup.level_break") && c.getBoolean("setup.level_break");
        requirePair(c, "setup.gap_skip_until", "setup.gap_skip_min_pct");
        levelHold = c.has("exits.level_hold") && c.getBoolean("exits.level_hold");
        holdTimeMin = levelHold ? c.getInt("exits.level_hold_time_min") : 0;
        nearAtr = levelHold ? c.getDouble("exits.level_hold_near_atr") : Double.NaN;
        failAtr = levelHold ? c.getDouble("exits.level_hold_fail_atr") : Double.NaN;
        rejectAtr = levelHold ? c.getDouble("exits.level_hold_reject_atr") : Double.NaN;
        if (levelHold && (c.getInt("scope.time_stop_min") != 0 || !c.has("exits.use_target") || c.getBoolean("exits.use_target"))) {
            throw new IllegalArgumentException("exits.level_hold needs scope.time_stop_min: 0 and exits.use_target: false");
        }
        structureHold = c.has("exits.structure_hold") && c.getBoolean("exits.structure_hold");
        dataHold = c.has("exits.data_hold") && c.getBoolean("exits.data_hold");
        dataHoldRvolMin = c.has("exits.data_hold_rvol_min") ? c.getDouble("exits.data_hold_rvol_min") : Double.NaN;
        structureHoldTimeMin = structureHold ? c.getInt("exits.structure_hold_time_min") : 0;
        structureHoldAtTimeStop = !c.has("exits.structure_hold_at_time_stop") || c.getBoolean("exits.structure_hold_at_time_stop");
        if (c.has("exits.structure_hold_at_time_stop") && !structureHold) {
            throw new IllegalArgumentException("exits.structure_hold_at_time_stop needs exits.structure_hold");
        }
        holdFloorAtr = c.has("exits.structure_hold_floor_atr") ? c.getDouble("exits.structure_hold_floor_atr") : Double.NaN;
        String holdOn = c.has("exits.structure_hold_on") ? c.getString("exits.structure_hold_on") : "minute";
        if (!java.util.Set.of("minute", "bar3m").contains(holdOn) || (!"minute".equals(holdOn) && !structureHold)) {
            throw new IllegalArgumentException("exits.structure_hold_on must be minute or bar3m, with exits.structure_hold");
        }
        holdOnBar3m = "bar3m".equals(holdOn);
        if (c.has("exits.structure_hold_floor_atr") && !(structureHold && holdFloorAtr >= 0)) {
            throw new IllegalArgumentException("exits.structure_hold_floor_atr needs exits.structure_hold and a value >= 0");
        }
        if (structureHold && (levelHold || c.getInt("scope.time_stop_min") != 0 || !c.has("exits.use_target")
                || c.getBoolean("exits.use_target"))) {
            throw new IllegalArgumentException(
                    "exits.structure_hold needs scope.time_stop_min: 0, exits.use_target: false and no exits.level_hold");
        }
        rewardToRisk = c.getDouble("setup.reward_to_risk");
        minTargetAtr = c.has("setup.min_target_atr") ? c.getDouble("setup.min_target_atr") : Double.NaN;
        if (c.has("setup.min_target_atr") && !(Double.isFinite(minTargetAtr) && minTargetAtr > 0)) {
            throw new IllegalArgumentException("setup.min_target_atr must be a finite number above 0");
        }
        requireVwapSide = c.has("setup.require_vwap_side") && c.getBoolean("setup.require_vwap_side");
        maxChg1mAtr1m = c.has("setup.max_chg1m_atr1m") ? c.getDouble("setup.max_chg1m_atr1m") : Double.NaN;
        timeStopMin = c.has("exits.cross_time_stop_min") ? c.getInt("exits.cross_time_stop_min") : 0;
        timeStopUnlessR = c.has("exits.cross_time_stop_unless_r") ? c.getDouble("exits.cross_time_stop_unless_r") : Double.NaN;
        runnerTrailR = c.has("exits.runner_trail_r") ? c.getDouble("exits.runner_trail_r") : Double.NaN;
        runnerLockR = c.has("exits.runner_lock_r") ? c.getDouble("exits.runner_lock_r") : Double.NaN;
        // review 26 Nov 2026: the floor moves the frame's index target; the runner and the level hold judge "the target" as
        // reward_to_risk R and would silently ignore it, so those combinations are refused, not half applied. The structure
        // hold judges the floored distance itself (structureHoldExit, A-679), so it may combine with the floor.
        if (c.has("setup.min_target_atr") && (Double.isFinite(runnerTrailR) || levelHold)) {
            throw new IllegalArgumentException("setup.min_target_atr cannot be combined with exits.runner_trail_r or "
                    + "exits.level_hold (they judge the target as reward_to_risk R)");
        }
        onOppositeCross = c.has("setup.on_opposite_cross") ? c.getString("setup.on_opposite_cross") : "ignore";
        if (!java.util.Set.of("ignore", "close", "reverse").contains(onOppositeCross)) {
            throw new IllegalArgumentException("setup.on_opposite_cross must be ignore, close or reverse: " + onOppositeCross);
        }
        giveBackAfterAtr = c.has("exits.giveback_after_atr") ? c.getDouble("exits.giveback_after_atr") : Double.NaN;
        giveBackFrac = c.has("exits.giveback_frac") ? c.getDouble("exits.giveback_frac") : Double.NaN;
        giveBackVwapGate = c.has("exits.giveback_vwap_gate") && c.getBoolean("exits.giveback_vwap_gate");
        requirePair(c, "exits.giveback_after_atr", "exits.giveback_frac");
        if (c.has("exits.giveback_after_atr") && !(giveBackAfterAtr > 0 && giveBackFrac > 0 && giveBackFrac < 1)) {
            throw new IllegalArgumentException("exits.giveback_after_atr must be > 0 and exits.giveback_frac between 0 and 1");
        }
        if (c.has("exits.giveback_vwap_gate") && !c.has("exits.giveback_after_atr")) {
            throw new IllegalArgumentException("exits.giveback_vwap_gate needs exits.giveback_after_atr and exits.giveback_frac");
        }
        earlyFailMin = c.has("exits.early_fail_min") ? c.getInt("exits.early_fail_min") : 0;
        earlyFailPct = c.has("exits.early_fail_pct") ? c.getDouble("exits.early_fail_pct") : Double.NaN;
        stretchAtr = c.has("exits.stretch_atr") ? c.getDouble("exits.stretch_atr") : Double.NaN;
        reverseOnlyBelowR = c.has("setup.opposite_cross_only_below_r") ? c.getDouble("setup.opposite_cross_only_below_r")
                : Double.NaN;
        // a key whose partner is missing would turn its exit silently off (NaN compares false): refuse the file instead
        requirePair(c, "exits.early_fail_min", "exits.early_fail_pct");
        requirePair(c, "exits.cross_time_stop_min", "exits.cross_time_stop_unless_r");
        requirePair(c, "exits.runner_trail_r", "exits.runner_lock_r");
        if (c.has("exits.early_fail_min") && !(earlyFailMin >= 1 && Double.isFinite(earlyFailPct))) {
            throw new IllegalArgumentException("exits.early_fail_min must be >= 1 and early_fail_pct a number");
        }
    }

    private static void requirePair(ThresholdConfig c, String a, String b) {
        if (c.has(a) != c.has(b)) {
            throw new IllegalArgumentException(a + " and " + b + " go together: set both or neither");
        }
    }

    /** A-333: remembers which side is open, so a cross against it can be recognised. */
    @Override
    public Decision decide(FeatureSnapshot s, PositionView position) {
        openSide = position.open() ? position.side() : null;
        if (openSide == null) {
            oppositeExit = false;
        }
        return super.decide(s, position);
    }

    /**
     * A-334: the early-fail exit, then the index exits below. The one look happens at the first minute from N with a quote
     * (no bid = no look yet); a trade with no quote until N + {@value #EARLY_FAIL_GRACE_MIN} minutes is not looked at.
     */
    @Override
    protected String customExit(FeatureSnapshot s, PositionView position, LocalTime time) {
        if (earlyFailMin > 0 && !earlyChecked && entryTime != null) {
            long held = java.time.Duration.between(entryTime, s.time()).toMinutes();
            if (held > earlyFailMin + EARLY_FAIL_GRACE_MIN) {
                earlyChecked = true;
            } else if (held >= earlyFailMin && position.averagePremium() > 0 && position.bid() > 0) {
                earlyChecked = true;
                if ((position.bid() / position.averagePremium() - 1) * 100.0 < earlyFailPct) {
                    return "EARLY_FAIL_" + earlyFailMin;
                }
            }
        }
        return super.customExit(s, position, time);
    }

    /** A-332: the conditional time stop and the runner, on the index in units of the entry's risk. */
    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        double spot = s.spot();
        if (!Double.isFinite(entrySpot) || !(entryRisk > 0) || !Double.isFinite(spot)) {
            return null;
        }
        double r = (side == OptionSide.CE ? spot - entrySpot : entrySpot - spot) / entryRisk;
        bestR = Double.isNaN(bestR) ? r : Math.max(bestR, r);
        if (oppositeExit) {
            return "REVERSE";
        }
        if (Double.isFinite(giveBackAfterAtr) && entryAtr > 0) {         // A-670
            double f = (side == OptionSide.CE ? spot - entrySpot : entrySpot - spot) / entryAtr;
            bestAtr = Double.isNaN(bestAtr) ? f : Math.max(bestAtr, f);
            if (bestAtr >= giveBackAfterAtr && f <= (1 - giveBackFrac) * bestAtr) {
                double vwap = s.structure().vwapSpotProxy();
                boolean wrongSide = Double.isFinite(vwap) && (side == OptionSide.CE ? spot < vwap : spot > vwap);
                if (!giveBackVwapGate || wrongSide) {
                    return "GIVEBACK";
                }
            }
        }
        if (dataHolding && !dataSupports(s, side)) {
            return "DATA_TURN";                               // A-649: held past the time stop, the data turned
        }
        double ema9 = s.structure().ema9(), atr = s.structure().atr3m();
        if (Double.isFinite(stretchAtr) && atr > 0 && Double.isFinite(ema9)
                && (side == OptionSide.CE ? spot - ema9 : ema9 - spot) / atr >= stretchAtr) {
            return "STRETCH";
        }
        if (Double.isFinite(runnerTrailR) && bestR >= rewardToRisk && r <= Math.max(runnerLockR, bestR - runnerTrailR)) {
            return "RUNNER_STOP";
        }
        if (timeStopMin > 0 && entryTime != null && java.time.Duration.between(entryTime, s.time()).toMinutes() >= timeStopMin
                && !(bestR >= timeStopUnlessR)) {
            return "TIME_" + timeStopMin;
        }
        if (levelHold) {
            return levelHoldExit(s, side, r);
        }
        if (structureHold) {
            return structureHoldExit(s, side, r, time);
        }
        return null;
    }

    /** A-649: at the time stop, hold while the data supports the trade (re-checked every minute in customExit). */
    @Override
    protected boolean holdPastTimeStop(OptionSide side) {
        if (!dataHold || current == null) {
            return false;
        }
        if (dataHolding) {
            return true;                                      // already held: customExit leaves on DATA_TURN
        }
        dataHolding = dataSupports(current, side);
        return dataHolding;
    }

    /**
     * A-649: near-ATM put − call OI change over 5 and 10 minutes with the trade (puts added for a call, calls for a put:
     * the near-ATM PCR moving the trade's way), the index on the trade's side of EMA20, and futures time-of-day RVOL at
     * least data_hold_rvol_min when set; any value missing = no support.
     */
    boolean dataSupports(FeatureSnapshot s, OptionSide side) {
        if (s.options() == null || s.structure() == null) {
            return false;
        }
        int sign = side == OptionSide.CE ? 1 : -1;
        double n5 = s.options().peOiChange5m() - s.options().ceOiChange5m();
        double n10 = s.options().peOiChange10m() - s.options().ceOiChange10m();
        double ema20 = s.structure().ema20(), spot = s.spot();
        boolean ok = Double.isFinite(n5) && Double.isFinite(n10) && Double.isFinite(ema20) && Double.isFinite(spot)
                && sign * n5 > 0 && sign * n10 > 0 && sign * (spot - ema20) > 0;
        if (ok && Double.isFinite(dataHoldRvolMin)) {
            double rv = s.futures() == null ? Double.NaN : s.futures().rvolTod();
            ok = Double.isFinite(rv) && rv >= dataHoldRvolMin;
        }
        return ok;
    }

    /** A-559: target / time exits held while the price structure supports the trade; see the field comment. */
    private String structureHoldExit(FeatureSnapshot s, OptionSide side, double r, LocalTime time) {
        // each snapshot is a minute close (features snapshot.interval_sec 60), so support and the floor are judged on it;
        // A-562 bar3m: on the last completed 3-minute bar's close, looked at only when a new bar has closed
        boolean up = side == OptionSide.CE;
        double price = holdOnBar3m ? s.structure().lastBarClose() : s.spot();
        boolean look = true;
        if (holdOnBar3m) {
            // a new bar closed: its close changed, or this snapshot is a 3-minute boundary (review 22 Nov 2026: two bars with
            // the same close are two looks; bars close only on the grid, before the boundary snapshot)
            look = Double.isFinite(price) && (price != lastBarSeen || threeMinuteClose(time));
            if (Double.isFinite(price)) {
                lastBarSeen = price;
            }
        }
        boolean support = structureSupports(s.structure(), price, up);
        if (structureHolding) {
            if (!look) {
                return null;
            }
            if (!support) {
                return "HOLD_STRUCTURE";
            }
            if (Double.isFinite(holdFloor) && Double.isFinite(holdFloorAtrValue)
                    && (up ? holdFloor - price : price - holdFloor) >= holdFloorAtr * holdFloorAtrValue) {
                return "HOLD_FLOOR";
            }
            return null;
        }
        boolean timeUp = entryTime != null
                && java.time.Duration.between(entryTime, s.time()).toMinutes() >= structureHoldTimeMin;
        boolean target = Double.isFinite(minTargetAtr)                  // A-679: mac-v53's floored target distance
                ? r * entryRisk >= targetDistance(entryRisk, entryAtr) : r >= rewardToRisk;
        if (target || timeUp) {
            if (support && (target || structureHoldAtTimeStop)) {
                structureHolding = true;
                if (Double.isFinite(holdFloorAtr)) {
                    StructureFeatures st = s.structure();
                    holdFloor = price;
                    holdFloorAtrValue = carried ? st.carriedAtr3m() : st.atr3m();
                }
                return null;
            }
            return target ? "TARGET" : "TIME_" + structureHoldTimeMin;
        }
        return null;
    }

    /** Every entry path (cross, reverse, level break) starts its trade's state here (review 22 Nov 2026). */
    private void startTrade(FeatureSnapshot s, double risk, boolean up) {
        entrySpot = s.spot();
        entryRisk = risk;
        entryTime = s.time();
        bestR = Double.NaN;
        entryAtr = carried ? s.structure().carriedAtr3m() : s.structure().atr3m();   // A-670; A-684: mco sizes on the carried ATR
        bestAtr = Double.NaN;
        earlyChecked = false;
        dataHolding = false;                                  // A-649
        holdLevel = levelHold ? nearestLevel(s.structure(), entrySpot, up) : Double.NaN;
        riding = false;
        deferred = false;
        structureHolding = false;
        holdFloor = Double.NaN;
        holdFloorAtrValue = Double.NaN;
        lastBarSeen = Double.NaN;
    }

    static boolean structureSupports(StructureFeatures st, double spot, boolean up) {
        double vwap = st.vwapSpotProxy(), e9 = st.ema9(), e20 = st.ema20();
        double swing = up ? st.lastSwingLow() : st.lastSwingHigh();
        if (!(Double.isFinite(spot) && Double.isFinite(vwap) && Double.isFinite(e9) && Double.isFinite(e20)
                && Double.isFinite(swing))) {
            return false;
        }
        return up ? spot > vwap && e9 > e20 && spot > swing : spot < vwap && e9 < e20 && spot < swing;
    }

    /** A-547: target / time exits around the nearest level; see the field comment. */
    private String levelHoldExit(FeatureSnapshot s, OptionSide side, double r) {
        StructureFeatures st = s.structure();
        boolean up = side == OptionSide.CE;
        double spot = s.spot(), atr = carried ? st.carriedAtr3m() : st.atr3m();
        double diff = carried ? st.carriedEma9() - st.carriedEma20() : st.ema9() - st.ema20();
        boolean aligned = up ? diff > 0 : diff < 0;                   // NaN = not aligned
        double sign = up ? 1 : -1;
        if (Double.isFinite(holdLevel) && sign * (spot - holdLevel) > 0) {
            riding = true;
            deferred = false;
            double next;
            while (Double.isFinite(next = nearestLevel(st, holdLevel, up)) && sign * (spot - next) > 0) {
                holdLevel = next;
            }
        }
        if (riding) {
            if (!aligned) {
                return "HOLD_OPP_CROSS";
            }
            if (atr > 0 && sign * (holdLevel - spot) >= failAtr * atr) {
                return "HOLD_LEVEL_FAIL";
            }
            return null;
        }
        boolean near = Double.isFinite(holdLevel) && atr > 0 && sign * (holdLevel - spot) <= nearAtr * atr;
        if (deferred) {
            if (!aligned) {
                return "HOLD_OPP_CROSS";
            }
            if (atr > 0 && sign * (holdLevel - spot) >= rejectAtr * atr) {
                return "HOLD_REJECT";
            }
            return null;
        }
        boolean timeUp = entryTime != null && java.time.Duration.between(entryTime, s.time()).toMinutes() >= holdTimeMin;
        boolean target = r >= rewardToRisk;
        if (target || timeUp) {
            if (near && aligned) {
                deferred = true;
                return null;
            }
            return target ? "TARGET" : "TIME_" + holdTimeMin;
        }
        return null;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double diff = carried ? st.carriedEma9() - st.carriedEma20() : st.ema9() - st.ema20();
        boolean crossUp = Double.isFinite(diff) && Double.isFinite(previousDiff) && previousDiff < 0 && diff > 0;
        boolean crossDown = Double.isFinite(diff) && Double.isFinite(previousDiff) && previousDiff > 0 && diff < 0;
        previousDiff = Double.isFinite(diff) ? diff : previousDiff;
        c.put("cross_up", crossUp);
        c.put("cross_down", crossDown);
        if (crossUp || crossDown) {
            // A-332 anatomy: every cross with its context (one line, parsed by .local/a332); no behaviour change
            while (!crosses.isEmpty() && crosses.peekFirst().isBefore(s.time().minusSeconds(3600))) {
                crosses.pollFirst();
            }
            double sw = crossUp ? st.lastSwingLow() : st.lastSwingHigh();
            log.info("A332CROSS {} {} {} canEnter={} spot={} ema9={} ema20={} slope={} atr3m={} atr1m={} swing={} riskAtr={} "
                    + "vwapAtr={} orhAtr={} orlAtr={} ema20Atr={} dayHigh={} dayLow={} open={} body={} rangeVsAvg={} chg3m={} "
                    + "chg1m={} higherLows={} lowerHighs={} prior60={} pdhAtr={} pdlAtr={}", s.underlying(), time,
                    crossUp ? "UP" : "DOWN", canEnter, s.spot(), st.ema9(), st.ema20(), st.ema9Slope(), st.atr3m(), st.atr1m(),
                    sw, (crossUp ? s.spot() - sw : sw - s.spot()) / st.atr3m(), st.distVwapAtr(), st.distOrhAtr(),
                    st.distOrlAtr(), st.distEma20Atr(), st.dayHigh(), st.dayLow(), st.dayOpen(), st.lastBarBodyRatio(),
                    st.lastBarRangeVsAvg(), st.spotChange3m(), st.spotChange1m(), st.higherLows(), st.lowerHighs(),
                    crosses.size(), st.distPdhAtr(), st.distPdlAtr());
            if (carried) {
                // review 22 Nov 2026: A332CROSS prints today's indicators; a carried cross decides on these
                double csw = crossUp ? st.carriedSwingLow() : st.carriedSwingHigh();
                log.info("MCOCROSS {} {} {} canEnter={} spot={} carriedEma9={} carriedEma20={} carriedAtr3m={} carriedSwing={} "
                        + "riskCarriedAtr={}", s.underlying(), time, crossUp ? "UP" : "DOWN", canEnter, s.spot(),
                        st.carriedEma9(), st.carriedEma20(), st.carriedAtr3m(), csw,
                        (crossUp ? s.spot() - csw : csw - s.spot()) / st.carriedAtr3m());
            }
            crosses.addLast(s.time());
        }
        if (!"ignore".equals(onOppositeCross) && openSide != null && (crossUp && openSide == OptionSide.PE
                || crossDown && openSide == OptionSide.CE) && Boolean.TRUE.equals(c.get("window"))
                && Boolean.TRUE.equals(c.get("not_expiring_index"))) {
            // A-333: a cross against the open trade; only when the trade is below the given R (NaN = always)
            double r = Double.isFinite(entrySpot) && entryRisk > 0
                    ? (openSide == OptionSide.CE ? s.spot() - entrySpot : entrySpot - s.spot()) / entryRisk : Double.NaN;
            if (!Double.isFinite(reverseOnlyBelowR) || r < reverseOnlyBelowR) {
                Entry next = "reverse".equals(onOppositeCross) ? crossEntry(s, crossUp, c) : null;
                oppositeExit = true;
                pendingReverse = next;
                pendingAt = s.time();
                c.put("opposite_cross_exit", true);
                return null;
            }
        }
        if (canEnter && pendingReverse != null) {
            // A-333: the reverse entry, the minute after the old trade closed; stale after 3 minutes or past its stop
            Entry next = pendingReverse;
            pendingReverse = null;
            double risk = next.side() == OptionSide.CE ? s.spot() - next.stop() : next.stop() - s.spot();
            if (s.time().isBefore(pendingAt.plusSeconds(180)) && risk > 0) {
                startTrade(s, risk, next.side() == OptionSide.CE);
                double reach = targetDistance(risk, carried ? s.structure().carriedAtr3m() : s.structure().atr3m());
                double target = next.side() == OptionSide.CE ? entrySpot + reach : entrySpot - reach;
                return new Entry(next.side(), next.stop(), target, next.reason() + "_REVERSE");
            }
        }
        if (levelBreak && awaitSide != null) {
            // A-546: the armed cross waits for its level; dropped when the averages flip back
            boolean aligned = awaitSide == OptionSide.CE ? diff > 0 : diff < 0;
            if (!aligned) {
                awaitSide = null;
            } else if (canEnter && !(crossUp || crossDown)) {
                boolean broke = awaitSide == OptionSide.CE ? s.spot() > awaitLevel : s.spot() < awaitLevel;
                if (broke) {
                    boolean up = awaitSide == OptionSide.CE;
                    awaitSide = null;
                    Entry b = crossEntry(s, up, c);
                    c.put("level_broken", true);
                    if (b != null) {
                        startTrade(s, up ? s.spot() - b.stop() : b.stop() - s.spot(), up);
                        return new Entry(b.side(), b.stop(), b.target(), b.reason() + "_LEVEL");
                    }
                }
            }
        }
        if (!canEnter || !(crossUp || crossDown)) {
            return null;
        }
        if (gapSkipUntil != null && time.isBefore(gapSkipUntil)) {
            StructureFeatures g = s.structure();
            double gapPct = (g.dayOpen() / g.prevClose() - 1) * 100.0;    // NaN = no gap known, no skip
            boolean withGap = Math.abs(gapPct) >= gapSkipMinPct && (crossUp ? gapPct > 0 : gapPct < 0);
            c.put("not_gap_echo", !withGap);
            if (withGap) {
                return null;
            }
        }
        Entry e = crossEntry(s, crossUp, c);
        if (e != null && levelBreak) {
            double lvl = nearestLevel(s.structure(), s.spot(), crossUp);
            if (Double.isFinite(lvl)) {
                awaitSide = e.side();
                awaitLevel = lvl;
                c.put("await_level", true);
                return null;
            }
        }
        if (e != null) {
            startTrade(s, crossUp ? s.spot() - e.stop() : e.stop() - s.spot(), crossUp);
        }
        return e;
    }

    /** The index distance from the entry to the target: reward_to_risk x risk, at least min_target_atr x ATR (A-653). */
    private double targetDistance(double risk, double atr) {
        double reach = rewardToRisk * risk;
        return Double.isFinite(minTargetAtr) && atr > 0 ? Math.max(reach, minTargetAtr * atr) : reach;
    }

    /** A-546: the nearest level strictly beyond the index in the cross's direction, NaN when there is none. */
    static double nearestLevel(StructureFeatures st, double spot, boolean up) {
        double[] lv = {st.orComplete() ? st.orHigh() : Double.NaN, st.orComplete() ? st.orLow() : Double.NaN, st.prevClose(),
                st.pdh(), st.pdl(), st.pwh(), st.pwl(), st.pmh(), st.pml()};
        double best = Double.NaN;
        for (double l : lv) {
            if (!Double.isFinite(l) || (up ? l <= spot : l >= spot)) {
                continue;
            }
            if (Double.isNaN(best) || (up ? l < best : l > best)) {
                best = l;
            }
        }
        return best;
    }

    /** The cross's entry after its checks (VWAP side, spike, swing stop), or null; no state change. */
    private Entry crossEntry(FeatureSnapshot s, boolean crossUp, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double spot = s.spot(), atr = carried ? st.carriedAtr3m() : st.atr3m();
        if (requireVwapSide) {
            double vwap = st.vwapSpotProxy();
            boolean withVwap = crossUp ? spot > vwap : spot < vwap;   // NaN compares false: no VWAP, no entry
            c.put("vwap_side", withVwap);
            if (!withVwap) {
                return null;
            }
        }
        if (Double.isFinite(maxChg1mAtr1m)) {
            // A-332 v23: the cross came on a 1-minute spike in its own direction (a chase): no entry; NaN = no veto
            double push = (crossUp ? 1 : -1) * st.spotChange1m() / st.atr1m();
            boolean calm = !(push > maxChg1mAtr1m);
            c.put("no_spike", calm);
            if (!calm) {
                return null;
            }
        }
        double stop = carried ? (crossUp ? st.carriedSwingLow() : st.carriedSwingHigh())
                : crossUp ? st.lastSwingLow() : st.lastSwingHigh();
        double risk = crossUp ? spot - stop : stop - spot;
        boolean stopOk = Double.isFinite(stop) && atr > 0 && risk > 0 && risk <= maxStopAtr * atr;
        c.put("swing_stop_ok", stopOk);
        if (!stopOk) {
            return null;
        }
        double reach = targetDistance(risk, atr);
        double target = crossUp ? spot + reach : spot - reach;
        return new Entry(crossUp ? OptionSide.CE : OptionSide.PE, stop, target, crossUp ? "EMA_CROSS_UP" : "EMA_CROSS_DOWN");
    }
}
