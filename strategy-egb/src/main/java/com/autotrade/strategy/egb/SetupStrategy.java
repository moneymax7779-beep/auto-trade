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

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.MarketState;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.SideView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategy;

/**
 * Shared frame of the five setup families (docs/studies/2026-10-06-five-setups.md, ledger A-036 to A-040): minute
 * closes, the entry window, never the index on its expiry day (when configured), one ATM option per trade, and exits on
 * index levels: the structure stop, the target level, a time stop, {@code flat_by}; a wide resting premium stop is the
 * emergency stop. A family supplies only {@link #evaluate}: its state update each minute and, when allowed, an entry.
 */
abstract class SetupStrategy implements Strategy {

    /** What to buy and the plan: stop and target are index levels (NaN = none). */
    record Entry(OptionSide side, double stop, double target, String reason) {
    }

    protected final ThresholdConfig config;
    private final LocalTime entryFrom;
    private final LocalTime lastNewEntry;
    private final LocalTime flatBy;
    private final int timeStopMin;
    private final boolean skipExpiringIndex;
    private final double premiumStopPct;
    private final int strikeOffset;

    /** Minute closes, newest last (the snapshot spot each minute). */
    protected final ArrayDeque<Double> closes = new ArrayDeque<>();

    private Entry plan;
    private Instant enteredAt;
    private boolean exitSent;
    private Map<String, Boolean> lastConditions = Map.of();

    protected SetupStrategy(ThresholdConfig config) {
        this.config = config;
        this.entryFrom = config.getTime("scope.entry_from");
        this.lastNewEntry = config.getTime("scope.last_new_entry");
        this.flatBy = config.getTime("scope.flat_by");
        this.timeStopMin = config.getInt("scope.time_stop_min");
        this.skipExpiringIndex = config.getBoolean("scope.skip_expiring_index");
        this.premiumStopPct = config.getDouble("exits.premium_stop_pct");
        this.strikeOffset = config.getInt("position.strike_offset");
    }

    @Override
    public String id() {
        return config.strategy();
    }

    @Override
    public String configHash() {
        return config.contentHash();
    }

    /**
     * The family's update for this minute ({@code closes} already holds this minute's close as the last element).
     * Records its conditions in {@code c}; returns an entry only when {@code canEnter}.
     */
    protected abstract Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c);

    @Override
    public Decision decide(FeatureSnapshot s, PositionView position) {
        Instant now = s.time();
        LocalTime time = now.atZone(MarketTime.IST).toLocalTime();
        double spot = s.spot();
        if (Double.isFinite(spot)) {
            closes.addLast(spot);
            while (closes.size() > 240) {
                closes.removeFirst();
            }
        }
        boolean expiringIndex = skipExpiringIndex && s.regime().dteTradingDays() == 0;
        boolean window = !time.isBefore(entryFrom) && time.isBefore(lastNewEntry);
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("not_expiring_index", !expiringIndex);
        c.put("window", window);
        Entry entry = evaluate(s, time, window && !expiringIndex && !position.open() && Double.isFinite(spot), c);

        List<OrderIntent> orders = new ArrayList<>();
        if (position.open()) {
            String exit = exitReason(position.side(), spot, now, time);
            if (exit != null && !exitSent) {
                orders.add(OrderIntent.exit(position.side(), Stage.CONFIRMED, exit));
                exitSent = true;
            }
        } else {
            exitSent = false;
            if (entry != null) {
                orders.add(new OrderIntent(OrderIntent.Action.ENTER, entry.side(), 1, Stage.CONFIRMED, entry.reason(),
                        premiumStopPct, strikeOffset));
                plan = entry;
                enteredAt = now;
            }
        }
        lastConditions = Collections.unmodifiableMap(c);
        Stage ce = stage(OptionSide.CE, position), pe = stage(OptionSide.PE, position);
        return new Decision(now, s.underlying(), new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                expiringIndex ? "EXPIRING_INDEX" : "SETUP", null),
                new SideView(OptionSide.CE, ce, Double.NaN, Double.NaN, Double.NaN, lastConditions),
                new SideView(OptionSide.PE, pe, Double.NaN, Double.NaN, Double.NaN, lastConditions), List.copyOf(orders));
    }

    private String exitReason(OptionSide side, double spot, Instant now, LocalTime time) {
        if (!time.isBefore(flatBy)) {
            return "FLAT_BY";
        }
        if (plan == null || !Double.isFinite(spot)) {
            return null;
        }
        double sign = side == OptionSide.CE ? 1 : -1;
        if (Double.isFinite(plan.target()) && sign * (spot - plan.target()) >= 0) {
            return "TARGET";
        }
        if (Double.isFinite(plan.stop()) && sign * (spot - plan.stop()) < 0) {
            return "STOP";
        }
        if (timeStopMin > 0 && enteredAt != null && Duration.between(enteredAt, now).toMinutes() >= timeStopMin) {
            return "TIME_" + timeStopMin;
        }
        return null;
    }

    private static Stage stage(OptionSide side, PositionView position) {
        return position.open() && position.side() == side ? Stage.CONFIRMED : Stage.WATCH;
    }

    // ---------------------------------------------------------------- helpers for the families

    /** The close {@code back} minutes ago (0 = this minute); NaN when there is none. */
    protected double close(int back) {
        if (back >= closes.size()) {
            return Double.NaN;
        }
        var it = closes.descendingIterator();
        double v = Double.NaN;
        for (int i = 0; i <= back; i++) {
            v = it.next();
        }
        return v;
    }

    /** The nearest level at least {@code minGap} beyond {@code from} in the trade's direction; NaN when none. */
    protected static double nextLevel(double from, double minGap, boolean up, double... levels) {
        double best = Double.NaN;
        for (double l : levels) {
            if (!Double.isFinite(l)) {
                continue;
            }
            double gap = up ? l - from : from - l;
            if (gap >= minGap && (Double.isNaN(best) || (up ? l < best : l > best))) {
                best = l;
            }
        }
        return best;
    }

    /** The target: the next level, else {@code fallbackAtr} ATR away. */
    protected static double target(double entry, double atr, boolean up, double minGapAtr, double fallbackAtr, double... levels) {
        double next = nextLevel(entry, minGapAtr * atr, up, levels);
        return Double.isFinite(next) ? next : entry + (up ? 1 : -1) * fallbackAtr * atr;
    }
}
