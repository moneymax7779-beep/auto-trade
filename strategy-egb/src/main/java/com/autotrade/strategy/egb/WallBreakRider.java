package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
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
 * Wall-break rider, v1 (ledger A-057): on the day the index expires, buy the ATM option when the call wall (put floor)
 * has lost open interest at every lookback for 10 minutes while the index moves toward it (writers covering), on
 * {@code signal.confirm_minutes} consecutive snapshots, on the trend side of the VWAP proxy and with futures OI not
 * opposing. Exits: a close back through the last swing, a peak trail on the held option, a no-progress time stop, the
 * resting premium stop, flat at {@code flat_by}. Study: docs/studies/2026-10-07-wall-break-rider.md.
 */
final class WallBreakRider implements Strategy {

    static final String ID = "wall-break-rider";

    private final WallBreakRiderConfig config;
    private int ceStreak;
    private int peStreak;
    private boolean exitSent;
    private double trailPeak = Double.NaN;
    private double bestBid = Double.NaN;
    private boolean wasOpen;

    WallBreakRider(WallBreakRiderConfig config) {
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
        ceStreak = snapshot.options().callWallWeakening() ? ceStreak + 1 : 0;
        peStreak = snapshot.options().putFloorWeakening() ? peStreak + 1 : 0;
        Map<OptionSide, Map<String, Boolean>> conditions = new java.util.EnumMap<>(OptionSide.class);
        for (OptionSide side : OptionSide.values()) {
            conditions.put(side, conditions(side, snapshot, expiryDay, window));
        }

        List<OrderIntent> orders = new ArrayList<>();
        if (wasOpen && !position.open()) {
            // a position just closed: a re-entry needs a fresh signal of confirm_minutes
            ceStreak = 0;
            peStreak = 0;
        }
        wasOpen = position.open();
        if (position.open()) {
            OptionSide side = position.side();
            double bid = position.bid();
            if (bid > 0) {
                bestBid = Double.isNaN(bestBid) ? bid : Math.max(bestBid, bid);
                if (Double.isNaN(trailPeak) && bid >= position.averagePremium() * (1 + config.trailActivationPct() / 100.0)) {
                    trailPeak = bid;
                } else if (!Double.isNaN(trailPeak)) {
                    trailPeak = Math.max(trailPeak, bid);
                }
            }
            String exit = null;
            if (!time.isBefore(config.flatBy())) {
                exit = "FLAT_BY";
            } else if (side == OptionSide.PE ? st.lastBarClose() > st.lastSwingHigh() : st.lastBarClose() < st.lastSwingLow()) {
                exit = side == OptionSide.PE ? "SWING_HIGH_RECLAIMED" : "SWING_LOW_LOST";     // NaN compares false
            } else if (!Double.isNaN(trailPeak) && bid > 0 && bid <= trailPeak * (1 - config.trailGivebackPct() / 100.0)) {
                exit = "TRAIL_" + Math.round(config.trailGivebackPct());
            } else if (position.openedAt() != null && Duration.between(position.openedAt(), now).toMinutes() >= config.timeStopMin()
                    && !(bestBid >= position.averagePremium() * (1 + config.timeStopMinGainPct() / 100.0))) {
                exit = "NO_PROGRESS_" + config.timeStopMin();
            }
            if (exit != null && !exitSent) {
                orders.add(OrderIntent.exit(side, Stage.RUNNER, exit));
                exitSent = true;
            }
        } else {
            exitSent = false;
            trailPeak = Double.NaN;
            bestBid = Double.NaN;
            for (OptionSide side : List.of(OptionSide.CE, OptionSide.PE)) {
                if (conditions.get(side).get("trigger")) {
                    orders.add(new OrderIntent(OrderIntent.Action.ENTER, side, 1, Stage.CONFIRMED,
                            side == OptionSide.CE ? "CALL_WALL_COVERING" : "PUT_FLOOR_COVERING", config.premiumStopPct(),
                            config.strikeOffset()));
                    break;
                }
            }
        }
        SideView ce = view(OptionSide.CE, conditions.get(OptionSide.CE), position, expiryDay);
        SideView pe = view(OptionSide.PE, conditions.get(OptionSide.PE), position, expiryDay);
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                expiryDay ? "EXPIRY_WALLS" : "NOT_EXPIRY", null);
        return new Decision(now, snapshot.underlying(), state, ce, pe, List.copyOf(orders));
    }

    private Map<String, Boolean> conditions(OptionSide side, FeatureSnapshot s, boolean expiryDay, boolean window) {
        boolean call = side == OptionSide.CE;
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("expiry_day", expiryDay);
        c.put("window", window);
        c.put("wall_weakening", call ? ceStreak >= config.confirmMinutes() : peStreak >= config.confirmMinutes());
        double vwap = s.structure().vwapSpotProxy();
        c.put("vwap_side", Double.isFinite(vwap) && (call ? s.spot() > vwap : s.spot() < vwap));
        String oi = s.futures().oiState();
        c.put("futures_oi_ok", oi == null || !config.rejectFuturesStates().get(side).contains(oi));
        c.put("trigger", window && c.get("wall_weakening") && c.get("vwap_side") && c.get("futures_oi_ok"));
        return java.util.Collections.unmodifiableMap(c);
    }

    private SideView view(OptionSide side, Map<String, Boolean> c, PositionView position, boolean expiryDay) {
        Stage stage = position.open() && position.side() == side ? Stage.CONFIRMED
                : c.get("wall_weakening") && c.get("window") ? Stage.ARMED : expiryDay ? Stage.WATCH : Stage.IDLE;
        return new SideView(side, stage, Double.NaN, Double.NaN, Double.NaN, Double.NaN, c);
    }
}
