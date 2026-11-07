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
 */
final class MaCross extends SetupStrategy {

    private static final Logger log = LoggerFactory.getLogger(MaCross.class);
    private final java.util.ArrayDeque<java.time.Instant> crosses = new java.util.ArrayDeque<>();
    private final double maxStopAtr;
    private final double rewardToRisk;
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
    private double previousDiff = Double.NaN;

    MaCross(ThresholdConfig c) {
        super(c);
        maxStopAtr = c.getDouble("setup.max_stop_atr");
        rewardToRisk = c.getDouble("setup.reward_to_risk");
        requireVwapSide = c.has("setup.require_vwap_side") && c.getBoolean("setup.require_vwap_side");
        maxChg1mAtr1m = c.has("setup.max_chg1m_atr1m") ? c.getDouble("setup.max_chg1m_atr1m") : Double.NaN;
        timeStopMin = c.has("exits.cross_time_stop_min") ? c.getInt("exits.cross_time_stop_min") : 0;
        timeStopUnlessR = c.has("exits.cross_time_stop_unless_r") ? c.getDouble("exits.cross_time_stop_unless_r") : Double.NaN;
        runnerTrailR = c.has("exits.runner_trail_r") ? c.getDouble("exits.runner_trail_r") : Double.NaN;
        runnerLockR = c.has("exits.runner_lock_r") ? c.getDouble("exits.runner_lock_r") : Double.NaN;
        onOppositeCross = c.has("setup.on_opposite_cross") ? c.getString("setup.on_opposite_cross") : "ignore";
        if (!java.util.Set.of("ignore", "close", "reverse").contains(onOppositeCross)) {
            throw new IllegalArgumentException("setup.on_opposite_cross must be ignore, close or reverse: " + onOppositeCross);
        }
        earlyFailMin = c.has("exits.early_fail_min") ? c.getInt("exits.early_fail_min") : 0;
        earlyFailPct = c.has("exits.early_fail_pct") ? c.getDouble("exits.early_fail_pct") : Double.NaN;
        stretchAtr = c.has("exits.stretch_atr") ? c.getDouble("exits.stretch_atr") : Double.NaN;
        reverseOnlyBelowR = c.has("setup.opposite_cross_only_below_r") ? c.getDouble("setup.opposite_cross_only_below_r")
                : Double.NaN;
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

    /** A-334: the early-fail exit (needs the position's premium and bid), then the index exits below. */
    @Override
    protected String customExit(FeatureSnapshot s, PositionView position, LocalTime time) {
        if (earlyFailMin > 0 && !earlyChecked && entryTime != null
                && java.time.Duration.between(entryTime, s.time()).toMinutes() >= earlyFailMin) {
            earlyChecked = true;
            if (position.averagePremium() > 0 && position.bid() > 0
                    && (position.bid() / position.averagePremium() - 1) * 100.0 < earlyFailPct) {
                return "EARLY_FAIL_" + earlyFailMin;
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
        return null;
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
                entrySpot = s.spot();
                entryRisk = risk;
                entryTime = s.time();
                bestR = Double.NaN;
                earlyChecked = false;
                double target = next.side() == OptionSide.CE ? entrySpot + rewardToRisk * risk : entrySpot - rewardToRisk * risk;
                return new Entry(next.side(), next.stop(), target, next.reason() + "_REVERSE");
            }
        }
        if (!canEnter || !(crossUp || crossDown)) {
            return null;
        }
        Entry e = crossEntry(s, crossUp, c);
        if (e != null) {
            entrySpot = s.spot();
            entryRisk = crossUp ? entrySpot - e.stop() : e.stop() - entrySpot;
            entryTime = s.time();
            bestR = Double.NaN;
            earlyChecked = false;
        }
        return e;
    }

    /** The cross's entry after its checks (VWAP side, spike, swing stop), or null; no state change. */
    private Entry crossEntry(FeatureSnapshot s, boolean crossUp, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double spot = s.spot(), atr = st.atr3m();
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
