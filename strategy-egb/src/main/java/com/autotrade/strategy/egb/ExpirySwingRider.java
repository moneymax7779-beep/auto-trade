package com.autotrade.strategy.egb;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.FeatureSnapshot;
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
 * Expiry swing rider, v1 (ledger A-050): on the day the index expires, always hold the ATM option in the direction
 * of the last swing reversal of {@code swing.reversal_pct} on one-minute closes; on the opposite reversal exit and
 * enter the other side; flat at {@code flat_by}. No filters: in the combination search every filter made expiry days
 * worse (docs/studies/2026-10-07-signal-audit-and-two-candidates.md). The resting premium stop is an emergency stop
 * only; after it the strategy waits for the next reversal. v3 (A-052): no new entry for the rest of the day after
 * {@code risk.stop_after_consecutive_losses} losing trades in a row (a trade is a loss when the held option's bid at
 * our exit was not above its average premium, or when the premium stop closed it).
 */
final class ExpirySwingRider implements Strategy {

    static final String ID = "expiry-swing-rider";

    private final ExpirySwingRiderConfig config;
    private long lastMinute = -1;
    private double lastClose = Double.NaN;
    private int trend;                       // +1 up swing (calls), -1 down swing (puts), 0 none yet
    private double extreme = Double.NaN;     // the current swing's extreme close
    private double dayMax = Double.NaN;
    private double dayMin = Double.NaN;
    private OptionSide wanted;               // the side to enter after a reversal
    private Instant wantedAt;
    private boolean exitSent;
    private boolean wasOpen;                 // v3: a position was open at the previous decision
    private double exitBidSeen = Double.NaN; // v3: the held option's bid when our exit was sent (NaN: closed by the stop)
    private double openAverage = Double.NaN;
    private int consecutiveLosses;
    private boolean doneForDay;

    ExpirySwingRider(ExpirySwingRiderConfig config) {
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
        boolean expiryDay = snapshot.regime().dteTradingDays() == config.dte();
        boolean continuous = SessionPhase.valueOf(snapshot.phase()).isContinuous();
        boolean reversal = false;
        long minute = now.truncatedTo(ChronoUnit.MINUTES).getEpochSecond() / 60;
        double close = snapshot.structure().lastBarClose();
        // one decision per completed one-minute bar: snapshots come on the minute, and the one at :00 carries the
        // close of the bar that just ended
        if (expiryDay && continuous && minute != lastMinute && Double.isFinite(close)) {
            lastMinute = minute;
            lastClose = close;
            reversal = onClose(close);
        }
        double x = config.reversalPct() / 100.0;
        List<OrderIntent> orders = new ArrayList<>();
        if (reversal) {
            wanted = trend > 0 ? OptionSide.CE : OptionSide.PE;
            wantedAt = now;
        }
        // v3: count losing trades in a row; a position that closed without our exit was closed by the premium stop
        if (wasOpen && !position.open()) {
            boolean loss = Double.isNaN(exitBidSeen) || !(exitBidSeen > openAverage);
            consecutiveLosses = loss ? consecutiveLosses + 1 : 0;
            if (config.stopAfterConsecutiveLosses() > 0 && consecutiveLosses >= config.stopAfterConsecutiveLosses()) {
                doneForDay = true;
            }
            exitBidSeen = Double.NaN;
        }
        wasOpen = position.open();
        if (position.open()) {
            openAverage = position.averagePremium();
        }
        boolean entryWindow = expiryDay && continuous && !doneForDay && !time.isBefore(config.entryFrom())
                && time.isBefore(config.lastNewEntry());
        if (position.open()) {
            String exit = null;
            if (!time.isBefore(config.flatBy())) {
                exit = "FLAT_BY";
            } else if (wanted != null && wanted != position.side()) {
                exit = "SWING_REVERSAL_" + config.reversalPct();
            }
            if (exit != null && !exitSent) {
                orders.add(OrderIntent.exit(position.side(), Stage.RUNNER, exit));
                exitSent = true;
                exitBidSeen = position.bid();
            }
            if (wanted == position.side()) {
                wanted = null;       // already holding the trend side
            }
        } else {
            exitSent = false;
            boolean fresh = wanted != null && wantedAt != null
                    && java.time.Duration.between(wantedAt, now).toMinutes() < config.entryGraceMinutes();
            if (fresh && entryWindow) {
                orders.add(new OrderIntent(OrderIntent.Action.ENTER, wanted, 1, Stage.CONFIRMED,
                        wanted == OptionSide.CE ? "SWING_UP_" + config.reversalPct() : "SWING_DOWN_" + config.reversalPct(),
                        config.premiumStopPct(), config.strikeOffset()));
                wanted = null;
            } else if (!fresh) {
                wanted = null;
            }
        }
        Map<String, Boolean> ceC = conditions(OptionSide.CE, expiryDay, entryWindow);
        Map<String, Boolean> peC = conditions(OptionSide.PE, expiryDay, entryWindow);
        SideView ce = new SideView(OptionSide.CE, stage(OptionSide.CE, position, expiryDay), Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, ceC);
        SideView pe = new SideView(OptionSide.PE, stage(OptionSide.PE, position, expiryDay), Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, peC);
        double reverseAt = trend > 0 ? extreme * (1 - x) : trend < 0 ? extreme * (1 + x) : Double.NaN;
        String label = trend == 0 ? null : String.format("swing extreme %.2f, reverses at %.2f", extreme, reverseAt);
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                expiryDay ? (trend > 0 ? "SWING_UP" : trend < 0 ? "SWING_DOWN" : "SWING_NONE") : "NOT_EXPIRY", label);
        return new Decision(now, snapshot.underlying(), state, ce, pe, List.copyOf(orders));
    }

    /** The zigzag on closes; true when this close reverses the swing (or starts the first one). */
    boolean onClose(double c) {
        double x = config.reversalPct() / 100.0;
        if (trend == 0) {
            dayMax = Double.isNaN(dayMax) ? c : Math.max(dayMax, c);
            dayMin = Double.isNaN(dayMin) ? c : Math.min(dayMin, c);
            if (c >= dayMin * (1 + x)) {
                trend = 1;
                extreme = c;
                return true;
            }
            if (c <= dayMax * (1 - x)) {
                trend = -1;
                extreme = c;
                return true;
            }
            return false;
        }
        if (trend > 0) {
            if (c > extreme) {
                extreme = c;
            } else if (c <= extreme * (1 - x)) {
                trend = -1;
                extreme = c;
                return true;
            }
            return false;
        }
        if (c < extreme) {
            extreme = c;
        } else if (c >= extreme * (1 + x)) {
            trend = 1;
            extreme = c;
            return true;
        }
        return false;
    }

    int trend() {
        return trend;
    }

    private Map<String, Boolean> conditions(OptionSide side, boolean expiryDay, boolean window) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("expiry_day", expiryDay);
        c.put("window", window);
        c.put("swing_agrees", side == OptionSide.CE ? trend > 0 : trend < 0);
        c.put("not_stopped_for_day", !doneForDay);
        return c;
    }

    private Stage stage(OptionSide side, PositionView position, boolean expiryDay) {
        if (position.open() && position.side() == side) {
            return Stage.CONFIRMED;
        }
        boolean agrees = side == OptionSide.CE ? trend > 0 : trend < 0;
        return expiryDay ? (agrees ? Stage.ARMED : Stage.WATCH) : Stage.IDLE;
    }
}
