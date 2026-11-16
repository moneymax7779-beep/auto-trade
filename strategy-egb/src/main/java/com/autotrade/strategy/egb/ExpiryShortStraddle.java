package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;
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
 * Expiry short straddle (A-292 rule, A-358 forward shadow; PAPER only): on the configured index's own expiry day, at the
 * first snapshot inside the entry window (12:00), sell the ATM call and put, {@code lots} of each. The executor buys
 * both back when their combined ask reaches {@code stop_pct} above the combined credit; this strategy buys both back
 * when a one-minute index close (the snapshot's spot) is beyond strike ± credit (the break-even); otherwise the legs are
 * held and cash-settled at intrinsic value at the close. One straddle per expiry day; an entry refused before any
 * position existed may try again after {@code refused_retry_min}, inside the window only.
 */
final class ExpiryShortStraddle implements Strategy {

    static final String ID = "expiry-short-straddle";

    private final ExpiryShortStraddleConfig config;
    private boolean asked;
    private boolean held;
    private boolean done;
    private boolean exiting;
    private Instant retryAfter;

    ExpiryShortStraddle(ExpiryShortStraddleConfig config) {
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
        boolean expiryDay = snapshot.regime().dteTradingDays() == config.dte()
                && config.underlyings().contains(snapshot.underlying());
        boolean continuous = SessionPhase.valueOf(snapshot.phase()).isContinuous();
        boolean window = !time.isBefore(config.entryFrom()) && time.isBefore(config.entryUntil());
        double spot = snapshot.spot();
        double credit = Double.NaN;
        double strike = Double.NaN;
        boolean breakEvenUp = false;
        boolean breakEvenDown = false;
        List<OrderIntent> orders = List.of();
        if (position.open()) {
            held = true;
            // both legs filled (the executor lists a leg as soon as it is sent, with quantity 0 until it fills): only
            // then are the credit and the strike known; a half-filled pair is never judged against the break-even
            boolean filled = position.legs().size() == 2
                    && position.legs().stream().allMatch(leg -> leg.quantity() > 0 && leg.averageCost() > 0);
            if (filled) {
                double sum = 0;
                for (PositionView.Leg leg : position.legs()) {
                    sum += leg.averageCost();
                    if (Double.isFinite(leg.strike())) {
                        strike = leg.strike();
                    }
                }
                credit = sum;
            }
            boolean priced = credit > 0 && Double.isFinite(strike) && Double.isFinite(spot);
            breakEvenUp = priced && spot > strike + credit;
            breakEvenDown = priced && spot < strike - credit;
            if (config.breakEvenExit() && (breakEvenUp || breakEvenDown) && !exiting) {
                orders = List.of(OrderIntent.exit(position.side(), Stage.CONFIRMED,
                        breakEvenUp ? "BREAK_EVEN_UP" : "BREAK_EVEN_DOWN"));
                exiting = true;                 // asked once; the executor closes both legs (its exit is idempotent anyway)
            }
        } else {
            exiting = false;
            if (held || position.closedToday() > 0) {
                // stopped, bought back at the break-even, settled, or a leg filled and the pair was unwound before this
                // snapshot (closedToday counts legs that traded, so a refusal that never filled is not one): one a day
                held = false;
                done = true;
            } else if (asked) {
                asked = false;                  // refused before any position existed: may try again inside the window
                retryAfter = now.plus(Duration.ofMinutes(config.refusedRetryMin()));
            }
            boolean retryOk = retryAfter == null || !now.isBefore(retryAfter);
            if (!done && expiryDay && continuous && window && retryOk && Double.isFinite(spot)) {
                orders = List.of(OrderIntent.shortStraddle(config.strikeOffset(), config.lots(), config.stopPct(),
                        Stage.CONFIRMED, "SELL_" + config.entryFrom().toString().replace(":", "")));
                asked = true;
            } else if (!done && expiryDay && !time.isBefore(config.entryUntil())) {
                done = true;                    // the entry window passed without a position
            }
        }
        Map<String, Boolean> c = new LinkedHashMap<>();
        c.put("expiry_day", expiryDay);
        c.put("entry_window", window);
        c.put("holding", position.open());
        c.put("break_even_up", breakEvenUp);
        c.put("break_even_down", breakEvenDown);
        c.put("done", done);
        Stage stage = done ? Stage.EXITED : position.open() || asked ? Stage.CONFIRMED
                : expiryDay && window ? Stage.ARMED : expiryDay ? Stage.WATCH : Stage.IDLE;
        Map<String, Boolean> conditions = Collections.unmodifiableMap(c);
        SideView ce = new SideView(OptionSide.CE, stage, Double.NaN, Double.NaN, Double.NaN, conditions);
        SideView pe = new SideView(OptionSide.PE, stage, Double.NaN, Double.NaN, Double.NaN, conditions);
        MarketState state = new MarketState(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                expiryDay ? "EXPIRY" : "NOT_EXPIRY", null);
        return new Decision(now, snapshot.underlying(), state, ce, pe, orders);
    }
}
