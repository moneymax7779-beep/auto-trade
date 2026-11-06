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
 * Wall-break rider, v1 (ledger A-057; v2, A-058, replaces the weakening flags with the wall's OI change over 10
 * minutes, which also works with BSE's slower OI updates): on the day the index expires, buy the ATM option when the call wall (put floor)
 * has lost open interest at every lookback for 10 minutes while the index moves toward it (writers covering), on
 * {@code signal.confirm_minutes} consecutive snapshots, on the trend side of the VWAP proxy and with futures OI not
 * opposing. Exits: a close back through the last swing, a peak trail on the held option, a no-progress time stop, the
 * resting premium stop, flat at {@code flat_by}. Study: docs/studies/2026-10-07-wall-break-rider.md.
 * <p>
 * v3 (ledger A-327), each off when its key is absent: no entry after the index already moved more than
 * {@code signal.max_prior_move_atr} ATR(3m) toward the trade over {@code signal.prior_move_min}; after a losing trade
 * (the last bid seen while open below the average premium) no entry on the opposite side for
 * {@code signal.no_flip_after_loss_min}; {@code exits.wall_recross_exit}: once the index crosses the wall strike recorded
 * at entry, a 3-minute close back through it exits (WALL_LOST); {@code exits.time_stop_min: 0} turns the no-progress
 * exit off.
 * <p>
 * A-328 (each off when absent): {@code signal.max_vwap_dist_atr} skips an entry further than that from VWAP on the trade's
 * side; {@code signal.min_wall_dist_atr} skips an entry whose wall is closer than that to the index;
 * {@code signal.max_trades_per_day} caps the filled entries of the day.
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
    private final java.util.ArrayDeque<double[]> spots = new java.util.ArrayDeque<>();   // {epoch second, spot}
    private OptionSide heldSide;
    private double lastBid = Double.NaN;
    private double lastAverage = Double.NaN;
    private OptionSide blockedSide;
    private Instant blockedUntil;
    private double entryWall = Double.NaN;
    private boolean wallCrossed;
    private double barAtCross = Double.NaN;
    private int tradesToday;    // the last 3-minute close when the wall was crossed (bar from before it)

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
        double priorMove = priorMove(snapshot);
        ceStreak = callSignal(snapshot) ? ceStreak + 1 : 0;
        peStreak = putSignal(snapshot) ? peStreak + 1 : 0;
        Map<OptionSide, Map<String, Boolean>> conditions = new java.util.EnumMap<>(OptionSide.class);
        for (OptionSide side : OptionSide.values()) {
            conditions.put(side, conditions(side, snapshot, expiryDay, window, priorMove, now));
        }

        List<OrderIntent> orders = new ArrayList<>();
        if (wasOpen && !position.open()) {
            // a position just closed: a re-entry needs a fresh signal of confirm_minutes
            ceStreak = 0;
            peStreak = 0;
            if (config.noFlipAfterLossMin() > 0 && heldSide != null && lastBid < lastAverage) {   // NaN compares false
                blockedSide = heldSide == OptionSide.CE ? OptionSide.PE : OptionSide.CE;
                blockedUntil = now.plus(Duration.ofMinutes(config.noFlipAfterLossMin()));
                conditions.put(blockedSide, conditions(blockedSide, snapshot, expiryDay, window, priorMove, now));
            }
        }
        if (!wasOpen && position.open()) {
            tradesToday++;                                   // a filled entry (one strategy instance per session)
        }
        wasOpen = position.open();
        if (position.open()) {
            OptionSide side = position.side();
            double bid = position.bid();
            heldSide = side;
            lastAverage = position.averagePremium();
            if (bid > 0) {
                lastBid = bid;
                bestBid = Double.isNaN(bestBid) ? bid : Math.max(bestBid, bid);
                if (Double.isNaN(trailPeak) && bid >= position.averagePremium() * (1 + config.trailActivationPct() / 100.0)) {
                    trailPeak = bid;
                } else if (!Double.isNaN(trailPeak)) {
                    trailPeak = Math.max(trailPeak, bid);
                }
            }
            if (config.wallRecrossExit() && !wallCrossed && Double.isFinite(entryWall)) {
                wallCrossed = side == OptionSide.CE ? snapshot.spot() > entryWall : snapshot.spot() < entryWall;
                barAtCross = st.lastBarClose();
            }
            // only a 3-minute close made after the crossing counts: the bar closed before it is still on the old side
            // (A-327 first run, 6 Nov: without this the exit fired at the crossing itself)
            boolean newBar = wallCrossed && Double.isFinite(st.lastBarClose()) && st.lastBarClose() != barAtCross;
            String exit = null;
            if (!time.isBefore(config.flatBy())) {
                exit = "FLAT_BY";
            } else if (side == OptionSide.PE ? st.lastBarClose() > st.lastSwingHigh() : st.lastBarClose() < st.lastSwingLow()) {
                exit = side == OptionSide.PE ? "SWING_HIGH_RECLAIMED" : "SWING_LOW_LOST";     // NaN compares false
            } else if (newBar && (side == OptionSide.CE ? st.lastBarClose() < entryWall : st.lastBarClose() > entryWall)) {
                exit = "WALL_LOST";
            } else if (!Double.isNaN(trailPeak) && bid > 0 && bid <= trailPeak * (1 - config.trailGivebackPct() / 100.0)) {
                exit = "TRAIL_" + Math.round(config.trailGivebackPct());
            } else if (config.timeStopMin() > 0 && position.openedAt() != null && Duration.between(position.openedAt(), now).toMinutes() >= config.timeStopMin()
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
            heldSide = null;
            lastBid = Double.NaN;
            lastAverage = Double.NaN;
            entryWall = Double.NaN;
            wallCrossed = false;
            barAtCross = Double.NaN;
            for (OptionSide side : List.of(OptionSide.CE, OptionSide.PE)) {
                if (conditions.get(side).get("trigger")) {
                    entryWall = side == OptionSide.CE ? snapshot.options().callBarrierStrike()
                            : snapshot.options().putSupportStrike();
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

    /**
     * v3: the index move over {@code prior_move_min} in ATR(3m), signed up (NaN when off or without that much history).
     * Each snapshot's spot is kept for that long.
     */
    private double priorMove(FeatureSnapshot s) {
        if (Double.isNaN(config.maxPriorMoveAtr())) {
            return Double.NaN;
        }
        long now = s.time().getEpochSecond();
        spots.addLast(new double[] {now, s.spot()});
        long cutoff = now - config.priorMoveMin() * 60L;
        while (spots.size() > 1) {                        // keep the newest snapshot at or before the cutoff
            double[] first = spots.pollFirst();
            if (spots.peekFirst()[0] > cutoff) {
                spots.addFirst(first);
                break;
            }
        }
        double then = spots.peekFirst()[0] <= cutoff ? spots.peekFirst()[1] : Double.NaN;
        double atr = s.structure().atr3m();
        return Double.isFinite(then) && atr > 0 ? (s.spot() - then) / atr : Double.NaN;
    }

    /** v1: the call-wall weakening flag; v2: the wall's OI down at least wall_oi_drop_pct over 10 min, index rising. */
    private boolean callSignal(FeatureSnapshot s) {
        if (Double.isNaN(config.wallOiDropPct())) {
            return s.options().callWallWeakening();
        }
        double change = s.options().callWallOiChangePct();
        return change <= -config.wallOiDropPct() && s.structure().spotChange3m() > 0;     // NaN compares false
    }

    private boolean putSignal(FeatureSnapshot s) {
        if (Double.isNaN(config.wallOiDropPct())) {
            return s.options().putFloorWeakening();
        }
        double change = s.options().putFloorOiChangePct();
        return change <= -config.wallOiDropPct() && s.structure().spotChange3m() < 0;
    }

    private Map<String, Boolean> conditions(OptionSide side, FeatureSnapshot s, boolean expiryDay, boolean window,
                                            double priorMove, Instant now) {
        boolean call = side == OptionSide.CE;
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("expiry_day", expiryDay);
        c.put("window", window);
        c.put("wall_weakening", call ? ceStreak >= config.confirmMinutes() : peStreak >= config.confirmMinutes());
        double vwap = s.structure().vwapSpotProxy();
        c.put("vwap_side", Double.isFinite(vwap) && (call ? s.spot() > vwap : s.spot() < vwap));
        String oi = s.futures().oiState();
        c.put("futures_oi_ok", oi == null || !config.rejectFuturesStates().get(side).contains(oi));
        boolean v3 = true;
        if (!Double.isNaN(config.maxPriorMoveAtr())) {
            // NaN (not enough history) does not block
            boolean ok = !(priorMove * (call ? 1 : -1) > config.maxPriorMoveAtr());
            c.put("not_extended", ok);
            v3 = ok;
        }
        double atr = s.structure().atr3m();
        if (!Double.isNaN(config.maxVwapDistAtr())) {
            boolean ok = !(Double.isFinite(vwap) && atr > 0 && (s.spot() - vwap) * (call ? 1 : -1) / atr > config.maxVwapDistAtr());
            c.put("vwap_not_stretched", ok);
            v3 = v3 && ok;
        }
        if (!Double.isNaN(config.minWallDistAtr())) {
            double wall = call ? s.options().callBarrierStrike() : s.options().putSupportStrike();
            boolean ok = !(Double.isFinite(wall) && atr > 0 && (wall - s.spot()) * (call ? 1 : -1) / atr < config.minWallDistAtr());
            c.put("wall_room", ok);
            v3 = v3 && ok;
        }
        if (config.maxTradesPerDay() > 0) {
            boolean ok = tradesToday < config.maxTradesPerDay();
            c.put("trades_left", ok);
            v3 = v3 && ok;
        }
        if (config.noFlipAfterLossMin() > 0) {
            boolean ok = blockedSide != side || blockedUntil == null || !now.isBefore(blockedUntil);
            c.put("no_flip_after_loss", ok);
            v3 = v3 && ok;
        }
        c.put("trigger", window && c.get("wall_weakening") && c.get("vwap_side") && c.get("futures_oi_ok") && v3);
        return java.util.Collections.unmodifiableMap(c);
    }

    private SideView view(OptionSide side, Map<String, Boolean> c, PositionView position, boolean expiryDay) {
        Stage stage = position.open() && position.side() == side ? Stage.CONFIRMED
                : c.get("wall_weakening") && c.get("window") ? Stage.ARMED : expiryDay ? Stage.WATCH : Stage.IDLE;
        return new SideView(side, stage, Double.NaN, Double.NaN, Double.NaN, Double.NaN, c);
    }
}
