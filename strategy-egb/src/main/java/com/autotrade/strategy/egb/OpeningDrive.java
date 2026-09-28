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
 * Opening drive through prior-day levels: in the first minutes of the session, when the index closes
 * through the previous day's high / low or its opening-range high / low (a level the day opened on the
 * untouched side of), buy the ATM option in the direction of the break, sized to the premium budget.
 * Exits: the executor's resting premium stop; the index back at or beyond the broken level; a trailing
 * stop once the premium has run; the time exit. One trade per underlying and session.
 * Study: docs/studies/2026-09-28-opening-drive.md (this v1 drops its volume filter).
 */
final class OpeningDrive implements Strategy {

    static final String ID = "opening-drive";

    private record Level(String name, double price, OptionSide side) {
    }

    private final OpeningDriveConfig config;
    private List<Level> levels;              // fixed once the day's open is known
    private Level traded;
    private int attempts;
    private boolean asked;
    private boolean held;
    private boolean done;
    private double bestBid = Double.NaN;

    OpeningDrive(OpeningDriveConfig config) {
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
        boolean continuous = SessionPhase.valueOf(snapshot.phase()).isContinuous();
        boolean previousComplete = st.prevSessionMinutes() >= config.minPreviousSessionMinutes();
        if (levels == null && Double.isFinite(st.dayOpen())) {
            levels = previousComplete ? levels(st) : List.of();
        }
        double spot = snapshot.spot();
        boolean window = !time.isBefore(config.triggerFrom()) && time.isBefore(config.triggerUntil());

        List<OrderIntent> orders = new ArrayList<>();
        String exitReason = null;
        if (position.open()) {
            held = true;
            asked = false;
            double bid = position.bid();
            double cost = position.averagePremium();
            if (!time.isBefore(config.timeExit())) {
                exitReason = "TIME_" + config.timeExit();
            } else if (config.structureStop() && traded != null && backThrough(traded, spot)) {
                exitReason = "STRUCTURE_" + traded.name();
            } else if (Double.isFinite(bid) && Double.isFinite(bestBid)
                    && bestBid >= cost * (1 + config.trailActivatePct() / 100.0)
                    && bid <= bestBid * (1 - config.trailGivebackPct() / 100.0)) {
                exitReason = "TRAIL";
            }
            if (Double.isFinite(bid)) {
                bestBid = Double.isFinite(bestBid) ? Math.max(bestBid, bid) : bid;
            }
            if (exitReason != null && !done) {       // sent once; the executor chases an unfilled exit
                orders.add(OrderIntent.exit(position.side(), Stage.CONFIRMED, exitReason));
                done = true;
            }
        } else {
            if (held) {
                done = true;                     // the premium stop, an exit or the square-off closed it
            }
            asked = false;                       // an entry sent last snapshot was refused or not filled
            List<Level> hits = hits(spot);
            if (!done && continuous && window && !hits.isEmpty() && attempts < config.maxEntryAttempts()) {
                // several levels broken at once: the stop uses the one nearest the price (the tighter stop)
                Level level = hits.stream().min((a, b) -> Double.compare(Math.abs(a.price() - spot),
                        Math.abs(b.price() - spot))).orElseThrow();
                traded = level;
                attempts++;
                asked = true;
                orders.add(new OrderIntent(OrderIntent.Action.ENTER, level.side(), 1, Stage.CONFIRMED,
                        "BREAK_" + level.name(), config.premiumStopPct(), config.strikeOffset()));
            }
        }

        SideView ce = view(OptionSide.CE, snapshot, position, window, previousComplete, time);
        SideView pe = view(OptionSide.PE, snapshot, position, window, previousComplete, time);
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                traded == null ? "OPENING_DRIVE" : "OPENING_DRIVE/" + traded.name(), null);
        return new Decision(now, snapshot.underlying(), state, ce, pe, List.copyOf(orders));
    }

    private List<Level> levels(StructureFeatures st) {
        List<Level> result = new ArrayList<>();
        for (OptionSide side : OptionSide.values()) {
            for (String name : config.levels().getOrDefault(side, List.of())) {
                double price = switch (name) {
                    case "PDH" -> st.pdh();
                    case "PDL" -> st.pdl();
                    case "PRIOR_ORH" -> st.prevOrHigh();
                    case "PRIOR_ORL" -> st.prevOrLow();
                    default -> throw new IllegalArgumentException("unknown opening-drive level " + name);
                };
                // counted only if the day opened on its untouched side (a gap through it is not a break)
                boolean untouched = side == OptionSide.CE ? st.dayOpen() < price : st.dayOpen() > price;
                if (Double.isFinite(price) && untouched) {
                    result.add(new Level(name, price, side));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<Level> hits(double spot) {
        if (levels == null || !Double.isFinite(spot)) {
            return List.of();
        }
        return levels.stream().filter(l -> l.side() == OptionSide.CE ? spot > l.price() : spot < l.price()).toList();
    }

    private static boolean backThrough(Level level, double spot) {
        return level.side() == OptionSide.CE ? spot <= level.price() : spot >= level.price();
    }

    private SideView view(OptionSide side, FeatureSnapshot snapshot, PositionView position, boolean window,
                          boolean previousComplete, LocalTime time) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("previous_session_complete", previousComplete);
        c.put("trigger_window", window);
        for (String name : config.levels().getOrDefault(side, List.of())) {
            boolean usable = levels != null && levels.stream().anyMatch(l -> l.side() == side && l.name().equals(name));
            c.put(name.toLowerCase() + "_usable", usable);
            c.put(name.toLowerCase() + "_broken", usable && hits(snapshot.spot()).stream().anyMatch(l -> l.name().equals(name)));
        }
        boolean mine = position.open() && position.side() == side || traded != null && traded.side() == side && (held || asked);
        Stage stage = done && traded != null && traded.side() == side ? Stage.EXITED
                : mine ? Stage.CONFIRMED
                : window && levels != null && levels.stream().anyMatch(l -> l.side() == side) && !done ? Stage.WATCH
                : Stage.IDLE;
        return new SideView(side, stage, Double.NaN, Double.NaN, Double.NaN, Collections.unmodifiableMap(c));
    }
}
