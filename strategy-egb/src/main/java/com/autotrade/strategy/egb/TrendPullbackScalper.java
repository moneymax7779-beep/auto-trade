package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayDeque;
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
 * Trend pullback scalper: in a trend (index beyond the VWAP, EMA20 of minute closes rising / falling, a new day
 * extreme within the last minutes), after a pullback of a measured depth that stays beyond the VWAP, buys the ATM
 * option on the turn and exits at a fixed premium target, at the index back beyond the pullback's extreme, after a
 * fixed time, or at {@code flat_by}. One entry per pullback. Study: docs/studies/2026-10-06-trend-pullback-scalper.md
 * (ledger A-035).
 */
final class TrendPullbackScalper implements Strategy {

    static final String ID = "trend-pullback-scalper";

    private final TrendPullbackScalperConfig config;

    // EMA of minute closes and its recent values (one per snapshot = one per minute)
    private double ema = Double.NaN;
    private int emaSamples;
    private final ArrayDeque<Double> emaHistory = new ArrayDeque<>();
    private double close1 = Double.NaN;      // the previous minute's close
    private double close2 = Double.NaN;      // the one before

    // up-trend leg: the day high, when it was made, the lowest close since (the pullback)
    private double dayHigh = Double.NaN;
    private Instant dayHighAt;
    private double pullbackLow = Double.NaN;
    private boolean longTaken;
    // down-trend leg, mirrored
    private double dayLow = Double.NaN;
    private Instant dayLowAt;
    private double pullbackHigh = Double.NaN;
    private boolean shortTaken;

    // the open position's plan
    private double stopLevel = Double.NaN;
    private Instant enteredAt;
    private double targetPct;
    private boolean exitSent;

    TrendPullbackScalper(TrendPullbackScalperConfig config) {
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
    public Decision decide(FeatureSnapshot s, PositionView position) {
        Instant now = s.time();
        LocalTime time = now.atZone(MarketTime.IST).toLocalTime();
        StructureFeatures st = s.structure();
        double spot = s.spot();
        boolean continuous = SessionPhase.valueOf(s.phase()).isContinuous();

        if (Double.isFinite(spot)) {
            ema = Double.isNaN(ema) ? spot : ema + (spot - ema) * 2.0 / (config.emaPeriod() + 1);
            emaSamples++;
            emaHistory.addLast(ema);
            while (emaHistory.size() > config.emaLookbackMin() + 1) {
                emaHistory.removeFirst();
            }
        }
        boolean emaReady = emaSamples >= config.emaPeriod() && emaHistory.size() == config.emaLookbackMin() + 1;
        double emaNow = emaReady ? emaHistory.peekLast() : Double.NaN;
        double emaAgo = emaReady ? emaHistory.peekFirst() : Double.NaN;

        // a new day extreme starts a new leg (and allows a new entry)
        if (Double.isFinite(st.dayHigh()) && (Double.isNaN(dayHigh) || st.dayHigh() > dayHigh)) {
            dayHigh = st.dayHigh();
            dayHighAt = now;
            pullbackLow = spot;
            longTaken = false;
        } else if (Double.isFinite(spot)) {
            pullbackLow = Double.isNaN(pullbackLow) ? spot : Math.min(pullbackLow, spot);
        }
        if (Double.isFinite(st.dayLow()) && (Double.isNaN(dayLow) || st.dayLow() < dayLow)) {
            dayLow = st.dayLow();
            dayLowAt = now;
            pullbackHigh = spot;
            shortTaken = false;
        } else if (Double.isFinite(spot)) {
            pullbackHigh = Double.isNaN(pullbackHigh) ? spot : Math.max(pullbackHigh, spot);
        }

        double atr = st.atr3m();
        double vwap = st.vwapSpotProxy();
        boolean expiry = s.regime().dteTradingDays() == 0;
        boolean window = continuous && !time.isBefore(config.entryFrom()) && time.isBefore(config.lastNewEntry());
        Map<String, Boolean> up = conditions(true, spot, atr, vwap, emaNow, emaAgo, now, window);
        Map<String, Boolean> down = conditions(false, spot, atr, vwap, emaNow, emaAgo, now, window);

        List<OrderIntent> orders = new ArrayList<>();
        if (position.open()) {
            String exit = exitReason(position, spot, now, time);
            if (exit != null && !exitSent) {
                orders.add(OrderIntent.exit(position.side(), Stage.CONFIRMED, exit));
                exitSent = true;
            }
        } else {
            exitSent = false;
            OptionSide side = up.get("trigger") && !longTaken ? OptionSide.CE
                    : down.get("trigger") && !shortTaken ? OptionSide.PE : null;
            if (side != null) {
                double stopPct = expiry ? config.premiumStopPctExpiry() : config.premiumStopPct();
                orders.add(new OrderIntent(OrderIntent.Action.ENTER, side, 1, Stage.CONFIRMED,
                        side == OptionSide.CE ? "PULLBACK_TURN_UP" : "PULLBACK_TURN_DOWN", stopPct, config.strikeOffset()));
                if (side == OptionSide.CE) {
                    longTaken = true;
                    stopLevel = pullbackLow;
                } else {
                    shortTaken = true;
                    stopLevel = pullbackHigh;
                }
                enteredAt = now;
                targetPct = expiry ? config.targetPctExpiry() : config.targetPct();
            }
        }
        close2 = close1;
        close1 = spot;

        SideView ce = new SideView(OptionSide.CE, stage(OptionSide.CE, position, up), Double.NaN, Double.NaN, Double.NaN, up);
        SideView pe = new SideView(OptionSide.PE, stage(OptionSide.PE, position, down), Double.NaN, Double.NaN, Double.NaN, down);
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                up.get("trend") ? "TREND_UP" : down.get("trend") ? "TREND_DOWN" : "NO_TREND", null);
        return new Decision(now, s.underlying(), state, ce, pe, List.copyOf(orders));
    }

    private Map<String, Boolean> conditions(boolean upside, double spot, double atr, double vwap, double emaNow,
                                            double emaAgo, Instant now, boolean window) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        boolean ready = Double.isFinite(spot) && atr > 0 && Double.isFinite(vwap) && Double.isFinite(emaNow);
        Instant extremeAt = upside ? dayHighAt : dayLowAt;
        double extreme = upside ? dayHigh : dayLow;
        double pullback = upside ? pullbackLow : pullbackHigh;
        double sign = upside ? 1 : -1;
        c.put("window", window);
        c.put("vwap_side", ready && sign * (spot - vwap) > 0);
        c.put("ema_slope", ready && sign * (emaNow - emaAgo) > 0);
        c.put("recent_extreme", extremeAt != null
                && Duration.between(extremeAt, now).toMinutes() <= config.extremeWithinMin());
        boolean trend = c.get("vwap_side") && c.get("ema_slope") && c.get("recent_extreme");
        c.put("trend", trend);
        double depth = ready ? sign * (extreme - pullback) / atr : Double.NaN;
        c.put("pullback_depth", depth >= config.pullbackMinAtr() && depth <= config.pullbackMaxAtr());
        c.put("pullback_beyond_vwap", ready && sign * (pullback - vwap) > 0);
        c.put("turn", ready && Double.isFinite(close1) && Double.isFinite(close2)
                && sign * (spot - close1) > 0 && sign * (spot - close2) > 0
                && sign * (spot - pullback) >= config.turnAtr() * atr
                && sign * (extreme - spot) > 0);
        c.put("trigger", window && trend && c.get("pullback_depth") && c.get("pullback_beyond_vwap") && c.get("turn"));
        return Collections.unmodifiableMap(c);
    }

    private String exitReason(PositionView position, double spot, Instant now, LocalTime time) {
        if (!time.isBefore(config.flatBy())) {
            return "FLAT_BY";
        }
        if (Double.isFinite(position.bid()) && position.averagePremium() > 0
                && position.bid() >= position.averagePremium() * (1 + targetPct / 100)) {
            return "TARGET_" + Math.round(targetPct);
        }
        boolean call = position.side() == OptionSide.CE;
        if (Double.isFinite(stopLevel) && Double.isFinite(spot) && (call ? spot < stopLevel : spot > stopLevel)) {
            return call ? "BELOW_PULLBACK_LOW" : "ABOVE_PULLBACK_HIGH";
        }
        if (enteredAt != null && Duration.between(enteredAt, now).toMinutes() >= config.timeStopMin()) {
            return "TIME_" + config.timeStopMin();
        }
        return null;
    }

    private static Stage stage(OptionSide side, PositionView position, Map<String, Boolean> c) {
        if (position.open() && position.side() == side) {
            return Stage.CONFIRMED;
        }
        return c.get("trend") && c.get("pullback_depth") ? Stage.ARMED : c.get("trend") ? Stage.WATCH : Stage.IDLE;
    }
}
