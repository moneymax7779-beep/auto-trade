package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
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
 * Expiry-day breakout straddle: on expiry day, inside the entry window, when the index closes through
 * one of its trigger levels (1-2 three-minute closes, either side) within the compression memory,
 * buy the ATM call and put sized to the premium budget. The executor closes both legs at the combined
 * target or stop; this strategy closes them at {@code flat_by}. One straddle per expiry day; an entry
 * refused before any position existed may try again after {@code refused_retry_min}.
 */
final class ExpiryBreakoutStraddle implements Strategy {

    static final String ID = "expiry-breakout-straddle";

    private final StraddleConfig config;
    private Instant compressionUntil;
    private boolean asked;
    private boolean held;
    private boolean done;
    private int trades;                 // straddles closed today
    private boolean rearm;              // v2: after a straddle, the trigger must switch off once before the next
    private Instant retryAfter;

    ExpiryBreakoutStraddle(StraddleConfig config) {
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
        boolean compressedNow = snapshot.compression().rangeVsSession() <= config.rangeVsSessionMax()
                && snapshot.compression().emaGapAtr() <= config.emaGapAtrMax()
                && snapshot.compression().volumeRateRatio() <= config.volumeRateRatioMax();
        if (compressedNow) {
            compressionUntil = now.plus(Duration.ofMinutes(config.compressionMemoryMin()));
        }
        boolean compressedRecently = compressionUntil != null && !now.isAfter(compressionUntil);
        EgbSide ce = new EgbSide(OptionSide.CE, snapshot);
        EgbSide pe = new EgbSide(OptionSide.PE, snapshot);
        boolean brokeUp = broke(ce);
        boolean brokeDown = broke(pe);
        boolean window = !time.isBefore(config.entryFrom()) && time.isBefore(config.entryUntil());
        boolean trigger = compressedRecently && (brokeUp || brokeDown);

        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("expiry_day", expiryDay);
        c.put("entry_window", window);
        c.put("compressed", compressedNow);
        c.put("compressed_recently", compressedRecently);
        c.put("broke_up", brokeUp);
        c.put("broke_down", brokeDown);
        c.put("trigger", trigger);

        List<OrderIntent> orders = List.of();
        if (position.open()) {
            held = true;
            if (!time.isBefore(config.flatBy())) {
                orders = List.of(OrderIntent.exit(position.side(), Stage.CONFIRMED, "FLAT_BY"));
                done = true;                    // the day's square-off: no new straddle after it
            }
        } else {
            if (held) {
                // the combined target or stop (or the square-off) closed it; v2 (max_trades 0 = no limit) may trade again
                held = false;
                trades++;
                rearm = true;
                done = done || (config.maxTrades() != 0 && trades >= config.maxTrades());
            } else if (asked) {
                asked = false;                  // refused before any position existed: may try again later
                retryAfter = now.plus(Duration.ofMinutes(config.refusedRetryMin()));
            }
            boolean retryOk = retryAfter == null || !now.isBefore(retryAfter);
            if (rearm && !trigger) {
                rearm = false;
            }
            if (!done && !rearm && expiryDay && continuous && ce.orComplete() && window && trigger && retryOk) {
                orders = List.of(OrderIntent.straddle(config.strikeOffset(), config.premiumBudget(), config.targetPct(),
                        config.stopPct(), Stage.CONFIRMED, brokeUp ? "BREAKOUT_UP" : "BREAKOUT_DOWN"));
                asked = true;
            }
        }
        Stage stage = done ? Stage.EXITED : position.open() || asked ? Stage.CONFIRMED
                : trigger && expiryDay ? Stage.ARMED : compressedRecently && expiryDay ? Stage.COMPRESSION
                : expiryDay ? Stage.WATCH : Stage.IDLE;
        Map<String, Boolean> conditions = java.util.Collections.unmodifiableMap(c);
        SideView view = new SideView(OptionSide.CE, stage, Double.NaN, Double.NaN, Double.NaN, conditions);
        SideView putView = new SideView(OptionSide.PE, stage, Double.NaN, Double.NaN, Double.NaN, conditions);
        String regime = expiryDay ? "EXPIRY/" + snapshot.gamma().gammaRegime() : "NOT_EXPIRY";
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN, regime, null);
        return new Decision(now, snapshot.underlying(), state, view, putView, orders);
    }

    /** A just-made breakout on this side: 1-2 three-minute closes through a trigger level, still beyond it. */
    private boolean broke(EgbSide side) {
        for (String level : config.triggerLevels().get(side.side())) {
            int closes = side.closesBeyond(level);
            if (closes >= 1 && closes <= 2 && side.levelDistance(level) > 0) {
                return true;
            }
        }
        return false;
    }
}
