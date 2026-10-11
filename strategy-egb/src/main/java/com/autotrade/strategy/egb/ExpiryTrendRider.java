package com.autotrade.strategy.egb;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.features.time.SessionPhase;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.MarketState;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.SideView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategy;

/**
 * Expiry trend rider: on the day the index expires, rides a trend that keeps making new day lows (highs)
 * beyond the opening range while breadth, futures OI and ATM IV agree. Each new extreme enters (flat) or adds
 * one plan lot (up to {@code max_adds}); the whole position exits on a 3-minute close back beyond the last
 * swing, at {@code flat_by}, or at the resting premium stop. No limit on trades: after an exit the next new
 * extreme may enter again. Study: docs/studies/2026-10-01-expiry-trend-rider.md (ledger A-029).
 */
final class ExpiryTrendRider implements Strategy {

    static final String ID = "expiry-trend-rider";

    private final ExpiryTrendRiderConfig config;
    private double previousLow = Double.NaN;
    private double previousHigh = Double.NaN;
    private int adds;
    private boolean exitSent;
    private Instant lastExtremeAt;          // v4: the later of the entry and the last new extreme in the trade's direction
    private boolean timeStopped;            // v5: a time-stop exit happened today: no new entry
    private double trailPeak = Double.NaN;  // v6: the held option's highest bid since the trail armed (NaN = not armed)
    private boolean lockArmed;              // v7: the bid has been profit_lock_arm_pct above the average premium
    /** v8: the index each minute over the last hour, for its 60-minute high-low range: {epoch minute, spot}. */
    private final java.util.ArrayDeque<double[]> lastHour = new java.util.ArrayDeque<>();

    ExpiryTrendRider(ExpiryTrendRiderConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String configHash() {
        return config.hash();
    }

    @Override
    public Decision decide(FeatureSnapshot snapshot, PositionView position) {
        Instant now = snapshot.time();
        LocalTime time = now.atZone(MarketTime.IST).toLocalTime();
        StructureFeatures st = snapshot.structure();
        boolean expiryDay = snapshot.regime().dteTradingDays() == config.dte();
        boolean continuous = SessionPhase.valueOf(snapshot.phase()).isContinuous();
        boolean window = expiryDay && continuous && !time.isBefore(config.entryFrom()) && time.isBefore(config.lastNewEntry());
        boolean newLow = Double.isFinite(previousLow) && st.dayLow() < previousLow;
        boolean newHigh = Double.isFinite(previousHigh) && st.dayHigh() > previousHigh;
        if (Double.isFinite(st.dayLow())) {
            previousLow = st.dayLow();
        }
        if (Double.isFinite(st.dayHigh())) {
            previousHigh = st.dayHigh();
        }
        double minute = now.getEpochSecond() / 60.0;
        if (Double.isFinite(snapshot.spot())) {
            lastHour.addLast(new double[] {minute, snapshot.spot()});
        }
        while (!lastHour.isEmpty() && minute - lastHour.peekFirst()[0] > 60) {
            lastHour.removeFirst();
        }
        Map<OptionSide, Map<String, Boolean>> conditions = new java.util.EnumMap<>(OptionSide.class);
        for (OptionSide side : OptionSide.values()) {
            conditions.put(side, conditions(side, snapshot, side == OptionSide.PE ? newLow : newHigh, expiryDay, window));
        }

        List<OrderIntent> orders = new ArrayList<>();
        if (position.open()) {
            OptionSide side = position.side();
            if (lastExtremeAt == null || (side == OptionSide.PE ? newLow : newHigh)) {
                lastExtremeAt = now;
            }
            // v6: a peak trail on the option itself, armed once the trade is well in profit (the runner days gave back
            // a quarter of the peak premium before the swing exit fired); the swing exit stays
            if (config.trailActivationPct() > 0 && position.bid() > 0 && position.averagePremium() > 0) {
                if (Double.isNaN(trailPeak) && position.bid() >= position.averagePremium() * (1 + config.trailActivationPct() / 100.0)) {
                    trailPeak = position.bid();
                } else if (!Double.isNaN(trailPeak)) {
                    trailPeak = Math.max(trailPeak, position.bid());
                }
            }
            boolean trailHit = !Double.isNaN(trailPeak) && position.bid() > 0
                    && position.bid() <= trailPeak * (1 - config.trailGivebackPct() / 100.0);
            // v7: a position that was well in profit is not allowed to turn into a loss (A-059)
            if (config.profitLockArmPct() > 0 && position.bid() > 0 && position.averagePremium() > 0
                    && position.bid() >= position.averagePremium() * (1 + config.profitLockArmPct() / 100.0)) {
                lockArmed = true;
            }
            boolean lockHit = lockArmed && position.bid() > 0
                    && position.bid() <= position.averagePremium() * (1 + config.profitLockFloorPct() / 100.0);
            String exit = null;
            if (!time.isBefore(config.flatBy())) {
                exit = "FLAT_BY";
            } else if (side == OptionSide.PE ? st.lastBarClose() > st.lastSwingHigh() : st.lastBarClose() < st.lastSwingLow()) {
                exit = side == OptionSide.PE ? "SWING_HIGH_RECLAIMED" : "SWING_LOW_LOST";     // NaN compares false
            } else if (trailHit) {
                exit = "TRAIL_" + Math.round(config.trailGivebackPct());
            } else if (lockHit) {
                exit = "PROFIT_LOCK";
            } else if (config.noNewExtremeMin() > 0
                    && java.time.Duration.between(lastExtremeAt, now).toMinutes() >= config.noNewExtremeMin()) {
                exit = "NO_NEW_EXTREME_" + config.noNewExtremeMin();     // v4: a stalled trend bleeds premium
                timeStopped = config.noReentryAfterTimeStop();
            }
            if (exit != null && !exitSent) {
                orders.add(OrderIntent.exit(side, Stage.RUNNER, exit));
                exitSent = true;
            } else if (exit == null && !exitSent && adds < config.maxAdds() && conditions.get(side).get("trigger")) {
                orders.add(new OrderIntent(OrderIntent.Action.ADD, side, 1, Stage.RUNNER,
                        side == OptionSide.PE ? "ADD_NEW_DAY_LOW" : "ADD_NEW_DAY_HIGH", config.premiumStopPct(),
                        config.strikeOffset()));
                adds++;
            }
        } else {
            exitSent = false;
            adds = 0;
            trailPeak = Double.NaN;
            lockArmed = false;
            for (OptionSide side : List.of(OptionSide.PE, OptionSide.CE)) {
                if (conditions.get(side).get("trigger") && !timeStopped && timeValueWithinBudget(snapshot)) {
                    orders.add(new OrderIntent(OrderIntent.Action.ENTER, side, 1, Stage.CONFIRMED,
                            side == OptionSide.PE ? "NEW_DAY_LOW" : "NEW_DAY_HIGH", config.premiumStopPct(),
                            config.strikeOffset()));
                    lastExtremeAt = now;                 // the entry is on a new extreme: the time stop counts from here
                    break;
                }
            }
        }

        SideView ce = view(OptionSide.CE, conditions.get(OptionSide.CE), position, expiryDay);
        SideView pe = view(OptionSide.PE, conditions.get(OptionSide.PE), position, expiryDay);
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                expiryDay ? "EXPIRY_TREND" : "NOT_EXPIRY", null);
        return new Decision(now, snapshot.underlying(), state, ce, pe, List.copyOf(orders));
    }

    /**
     * v8 (A-073): the ATM option's time value per leg, (straddle − |spot − ATM strike|) / 2 in index points, is at most
     * {@code time_value_max_range_ratio} × the index's high-low range over the last 60 minutes. Off (always true) when the
     * ratio is 0; true when the straddle or the hour of history is missing (missing data never blocks).
     */
    boolean timeValueWithinBudget(FeatureSnapshot s) {
        double ratio = config.timeValueMaxRangeRatio();
        if (!(ratio > 0)) {
            return true;
        }
        double straddle = s.options().straddle(), atm = s.options().atmStrike(), spot = s.spot();
        if (!(straddle > 0) || !Double.isFinite(atm) || !Double.isFinite(spot) || lastHour.size() < 30) {
            return true;
        }
        double timeValue = (straddle - Math.abs(spot - atm)) / 2;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] p : lastHour) {
            lo = Math.min(lo, p[1]);
            hi = Math.max(hi, p[1]);
        }
        return timeValue <= ratio * (hi - lo);
    }

    private Map<String, Boolean> conditions(OptionSide side, FeatureSnapshot s, boolean newExtreme, boolean expiryDay,
                                            boolean window) {
        StructureFeatures st = s.structure();
        double spot = s.spot();
        boolean put = side == OptionSide.PE;
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("expiry_day", expiryDay);
        c.put("window", window);
        c.put("beyond_opening_range", st.orComplete() && (put ? spot < st.orLow() : spot > st.orHigh()));
        double breadth = s.breadth().moverBreadth();
        c.put("breadth_agrees", put ? breadth <= -config.breadthMin() : breadth >= config.breadthMin());
        String oi = s.futures().oiState();
        c.put("futures_oi_ok", oi != null && !config.rejectFuturesStates().get(side).contains(oi));
        c.put("iv_rising", s.options().atmIvChange3m() > config.ivChange3mMin());
        c.put("new_extreme", newExtreme);
        boolean trend = c.get("beyond_opening_range") && c.get("breadth_agrees") && c.get("futures_oi_ok") && c.get("iv_rising");
        c.put("trend", trend);
        c.put("trigger", window && trend && newExtreme);
        if (config.timeValueMaxRangeRatio() > 0) {
            c.put("time_value_ok", timeValueWithinBudget(s));      // v8: shown in the stage changes; gates entries only
        }
        return Collections.unmodifiableMap(c);
    }

    private SideView view(OptionSide side, Map<String, Boolean> c, PositionView position, boolean expiryDay) {
        Stage stage = position.open() && position.side() == side ? (adds > 0 ? Stage.RUNNER : Stage.CONFIRMED)
                : c.get("trend") && c.get("window") ? Stage.ARMED : expiryDay ? Stage.WATCH : Stage.IDLE;
        return new SideView(side, stage, Double.NaN, Double.NaN, Double.NaN, Double.NaN, c);
    }
}
