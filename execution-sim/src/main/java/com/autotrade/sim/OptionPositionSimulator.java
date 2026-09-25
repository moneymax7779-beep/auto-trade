package com.autotrade.sim;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;

/**
 * A long position in one option contract built from several buy orders (tranches) and closed by
 * one sell of everything. Orders reach the market after the fill model's latency and fill on the
 * first quote from then on. A resting stop sits {@code stopPct} below the average cost, checked on
 * every quote's bid (as a broker-side stop would be), so it acts between strategy decisions.
 */
public final class OptionPositionSimulator implements Consumer<MarketEvent> {

    /** One executed order leg. */
    public record Leg(Side side, String tag, Instant decidedAt, FillModel.Fill fill, CostBreakdown costs) {
    }

    private record Pending(Side side, long quantity, Instant decidedAt, String tag) {
    }

    private final long token;
    private final String symbol;
    private final String exchange;
    private final LocalDate tradeDate;
    private final FillModel fills;
    private final CostModel costs;
    private final double stopFraction;
    private final Deque<Pending> pending = new ArrayDeque<>();
    private final List<Leg> legs = new ArrayList<>();

    private long quantity;
    private double averageCost;
    private double realised;
    private double costTotal;
    private boolean closed;
    private boolean stopWorking;
    private String exitReason;
    private double bestBid = Double.NEGATIVE_INFINITY;
    private double worstBid = Double.POSITIVE_INFINITY;
    private double lastBid = Double.NaN;

    public OptionPositionSimulator(long token, String symbol, String exchange, LocalDate tradeDate, FillModel fills,
                                   CostModel costs, double stopPct) {
        this.token = token;
        this.symbol = symbol;
        this.exchange = exchange;
        this.tradeDate = tradeDate;
        this.fills = fills;
        this.costs = costs;
        this.stopFraction = stopPct / 100.0;
    }

    public void buy(Instant decidedAt, long units, String tag) {
        if (!closed && !exitPending()) {
            pending.addLast(new Pending(Side.BUY, units, decidedAt, tag));
        }
    }

    /** Sells everything held (and anything still being bought) once it can. */
    public void exitAll(Instant decidedAt, String reason) {
        if (!closed && !exitPending()) {
            pending.addLast(new Pending(Side.SELL, 0, decidedAt, reason));
        }
    }

    @Override
    public void accept(MarketEvent event) {
        if (closed || !(event instanceof OptionTick tick) || tick.instrumentToken() != token) {
            return;
        }
        if (!tick.bids().isEmpty() && quantity > 0) {
            lastBid = tick.bids().price(0);
            bestBid = Math.max(bestBid, lastBid);
            worstBid = Math.min(worstBid, lastBid);
            if (!stopWorking && !exitPending() && lastBid <= averageCost * (1 - stopFraction)) {
                stopWorking = true;
                pending.addLast(new Pending(Side.SELL, 0, tick.receivedAt(), "PREMIUM_STOP"));
            }
        }
        while (!pending.isEmpty() && !tick.receivedAt().isBefore(pending.peekFirst().decidedAt().plus(fills.latency()))) {
            Pending order = pending.peekFirst();
            long units = order.side() == Side.BUY ? order.quantity() : quantity;
            if (units == 0) {
                pending.removeFirst();
                if (order.side() == Side.SELL) {
                    closed = true;
                    exitReason = order.tag();
                }
                continue;
            }
            FillModel.Fill fill = fills.fill(order.side(), units, tick);
            if (fill == null) {
                return; // that side of the book is empty; try the next quote
            }
            pending.removeFirst();
            CostBreakdown legCosts = costs.costs(tradeDate, exchange, order.side(), fill.price(), units);
            costTotal += legCosts.total();
            legs.add(new Leg(order.side(), order.tag(), order.decidedAt(), fill, legCosts));
            if (order.side() == Side.BUY) {
                averageCost = (averageCost * quantity + fill.price() * units) / (quantity + units);
                quantity += units;
            } else {
                realised += (fill.price() - averageCost) * quantity;
                quantity = 0;
                closed = true;
                exitReason = order.tag();
                pending.clear();
            }
        }
    }

    private boolean exitPending() {
        return pending.stream().anyMatch(order -> order.side() == Side.SELL);
    }

    public boolean isOpen() {
        return !closed && (quantity > 0 || !pending.isEmpty());
    }

    public long quantity() {
        return quantity;
    }

    public double averageCost() {
        return averageCost;
    }

    public String symbol() {
        return symbol;
    }

    public long token() {
        return token;
    }

    public List<Leg> legs() {
        return List.copyOf(legs);
    }

    public String exitReason() {
        return exitReason;
    }

    public boolean closed() {
        return closed;
    }

    public double realised() {
        return realised;
    }

    public double costs() {
        return costTotal;
    }

    /** Mark-to-bid value of what is still held (0 once closed). */
    public double unrealised() {
        return quantity > 0 && !Double.isNaN(lastBid) ? (lastBid - averageCost) * quantity : 0;
    }

    public double stopFraction() {
        return stopFraction;
    }

    public double maxFavourablePerUnit() {
        return Double.isFinite(bestBid) && averageCost > 0 ? bestBid - averageCost : Double.NaN;
    }

    public double maxAdversePerUnit() {
        return Double.isFinite(worstBid) && averageCost > 0 ? averageCost - worstBid : Double.NaN;
    }

    public Duration held() {
        if (legs.isEmpty()) {
            return Duration.ZERO;
        }
        Instant end = closed && legs.getLast().side() == Side.SELL ? legs.getLast().fill().time()
                : legs.getLast().fill().time();
        return Duration.between(legs.getFirst().fill().time(), end);
    }
}
