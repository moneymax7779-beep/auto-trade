package com.autotrade.sim;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;

/**
 * Simulates one {@link TradePlan} on the replayed quote path of its contract. Feed it every event in
 * replay order; it reacts only to its contract's ticks. Exit triggers are evaluated tick by tick on
 * the bid, so a stop touched before the target is taken first; each order reaches the market after
 * the fill model's latency and fills on the first quote received from then on.
 */
public final class LongOptionSimulator implements Consumer<MarketEvent> {

    private enum State { WAITING_ENTRY, OPEN, EXITING, DONE }

    private final TradePlan plan;
    private final FillModel fills;
    private final CostModel costs;
    private final LocalDate tradeDate;

    private State state = State.WAITING_ENTRY;
    private FillModel.Fill entry;
    private FillModel.Fill exit;
    private Instant exitOrderAt;
    private String exitReason;
    private double bestBid = Double.NEGATIVE_INFINITY;
    private double worstBid = Double.POSITIVE_INFINITY;

    public LongOptionSimulator(TradePlan plan, FillModel fills, CostModel costs) {
        this.plan = plan;
        this.fills = fills;
        this.costs = costs;
        this.tradeDate = MarketTime.sessionDate(plan.decisionTime());
    }

    @Override
    public void accept(MarketEvent event) {
        if (state == State.DONE || !(event instanceof OptionTick tick)
                || tick.instrumentToken() != plan.instrumentToken()) {
            return;
        }
        Instant time = tick.receivedAt();
        switch (state) {
            case WAITING_ENTRY -> {
                if (!time.isBefore(plan.decisionTime().plus(fills.latency()))) {
                    entry = fills.fill(Side.BUY, plan.quantity(), tick);
                    if (entry != null) {
                        state = State.OPEN;
                    }
                }
            }
            case OPEN -> checkExit(tick);
            case EXITING -> {
                trackExcursion(tick); // still holding while the exit order is in flight
                if (!time.isBefore(exitOrderAt.plus(fills.latency()))) {
                    exit = fills.fill(Side.SELL, plan.quantity(), tick);
                    if (exit != null) {
                        state = State.DONE;
                    }
                }
            }
            default -> {
            }
        }
    }

    private void checkExit(OptionTick tick) {
        if (tick.bids().isEmpty()) {
            if (!tick.receivedAt().isBefore(plan.exitBy())) {
                startExit(tick.receivedAt(), "TIME");
            }
            return;
        }
        trackExcursion(tick);
        double bid = tick.bids().price(0);
        if (bid <= plan.stopPremium()) {
            startExit(tick.receivedAt(), "STOP");
        } else if (bid >= plan.targetPremium()) {
            startExit(tick.receivedAt(), "TARGET");
        } else if (!tick.receivedAt().isBefore(plan.exitBy())) {
            startExit(tick.receivedAt(), "TIME");
        }
    }

    private void trackExcursion(OptionTick tick) {
        if (!tick.bids().isEmpty()) {
            bestBid = Math.max(bestBid, tick.bids().price(0));
            worstBid = Math.min(worstBid, tick.bids().price(0));
        }
    }

    private void startExit(Instant at, String reason) {
        exitOrderAt = at;
        exitReason = reason;
        state = State.EXITING;
    }

    public boolean done() {
        return state == State.DONE;
    }

    /** The outcome so far; call after the replay ends. */
    public TradeResult result() {
        if (entry == null) {
            return new TradeResult("NOT_FILLED", null, null, null, 0, CostBreakdown.none(), CostBreakdown.none(), 0,
                    Double.NaN, Double.NaN, 0, fills.name());
        }
        CostBreakdown entryCosts = costs.costs(tradeDate, plan.exchange(), Side.BUY, entry.price(), plan.quantity());
        double favourable = Double.isFinite(bestBid) ? bestBid - entry.price() : Double.NaN;
        double adverse = Double.isFinite(worstBid) ? entry.price() - worstBid : Double.NaN;
        if (exit == null) {
            return new TradeResult("OPEN_AT_END", exitReason, entry, null, 0, entryCosts, CostBreakdown.none(),
                    -entryCosts.total(), favourable, adverse, 0, fills.name());
        }
        CostBreakdown exitCosts = costs.costs(tradeDate, plan.exchange(), Side.SELL, exit.price(), plan.quantity());
        double gross = (exit.price() - entry.price()) * plan.quantity();
        return new TradeResult("FILLED_AND_EXITED", exitReason, entry, exit, gross, entryCosts, exitCosts,
                gross - entryCosts.total() - exitCosts.total(), favourable, adverse,
                Duration.between(entry.time(), exit.time()).toSeconds(), fills.name());
    }
}
