package com.autotrade.oms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.strategy.OptionSide;

/** One campaign in one underlying, as the OMS runs it. Mutable; owned by {@link OrderManager}. */
public final class ManagedPosition {

    public enum State { OPENING, OPEN, CANCELLING_FOR_EXIT, EXITING, CLOSED }

    /**
     * A-358: LONG bought to open and sells to close (every position before v19); SHORT sold to open and buys to close.
     * For a SHORT, {@link #averageCost()} is the average sale price (the credit), {@link #boughtQuantity()} the quantity
     * sold to open, and the position is valued and bought back at the ask.
     */
    public enum Direction { LONG, SHORT }

    final String underlying;
    /** The strategy that owns this position (several strategies may hold the same underlying). */
    final String strategy;
    final OptionSide side;
    final Contract contract;
    final Instant openedAt;
    final List<String> stages = new ArrayList<>();
    final Set<String> entryOrders = new LinkedHashSet<>();
    final Set<String> stopOrders = new LinkedHashSet<>();
    final Set<String> exitOrders = new LinkedHashSet<>();
    final Map<String, Instant> orderSentAt = new HashMap<>();
    final Map<String, Long> orderFilled = new HashMap<>();
    final Map<String, Long> orderQuantity = new HashMap<>();

    State state = State.OPENING;
    /** A-358: LONG unless the OMS opened it with a sale; final once set at entry. */
    Direction direction = Direction.LONG;
    /** A-358, SHORT only: the margin this leg ties up (rupees), counted as capital in use instead of premium. */
    double margin;
    long quantity;
    double averageCost;
    long boughtQuantity;
    double realised;
    double costs;
    double lastBid = Double.NaN;
    double lastAsk = Double.NaN;
    double lastPrice = Double.NaN;
    String exitReason;
    /** This position's resting premium stop as a fraction below average cost; 0 = none (a straddle leg). */
    double stopFraction;
    /**
     * Rupees the position may lose at its stop once it has been added to (pyramid protection): fixed at the
     * first add to what the held position risked then; 0 = no cap (the stop stays a fraction of average cost).
     */
    double riskCap;
    /** Premium asked for by entries so far (ask × quantity at sending), for the capital check. */
    double committedPremium;
    Instant closedAt;
    Instant exitPricedAt;
    int exitChases;
    /** Times a straddle leg's (or, risk v11, a single-leg position's) unfilled entries were re-priced at the new ask. */
    int entryChases;
    /** risk v11: the limit of the latest entry orders and when it was set (a re-price only ever raises it). */
    double entryLimit;
    Instant entryPricedAt;
    /** risk v12: quantity still to buy in slices, the first slice's ask and when slicing began. */
    long pendingEntryQuantity;
    /** Whether {@link OmsListener#entryComplete} has been told (once per position). */
    boolean entryCompleteSignalled;
    double sliceStartAsk;
    Instant sliceStartedAt;

    /** "" for a single-leg position; the leg's side for one leg of a straddle. */
    final String leg;

    ManagedPosition(String strategy, String underlying, OptionSide side, Contract contract, Instant openedAt) {
        this(strategy, underlying, side, contract, openedAt, "");
    }

    ManagedPosition(String strategy, String underlying, OptionSide side, Contract contract, Instant openedAt,
                    String leg) {
        this.leg = leg;
        this.strategy = strategy;
        this.underlying = underlying;
        this.side = side;
        this.contract = contract;
        this.openedAt = openedAt;
    }

    /** The OMS key: strategy, underlying and leg. */
    String key() {
        return strategy + "|" + underlying + "|" + leg;
    }

    /** Capital this position ties up: what it paid, or what its entries asked for while they work; a short's margin. */
    double premiumInUse() {
        return isShort() ? margin : Math.max(committedPremium, averageCost * quantity);
    }

    boolean isShort() {
        return direction == Direction.SHORT;
    }

    public Direction direction() {
        return direction;
    }

    /** A-358, SHORT only: the margin this leg ties up (0 for a long). */
    public double margin() {
        return margin;
    }

    public String leg() {
        return leg;
    }

    public String underlying() {
        return underlying;
    }

    public String strategy() {
        return strategy;
    }

    public OptionSide side() {
        return side;
    }

    public Contract contract() {
        return contract;
    }

    public State state() {
        return state;
    }

    public long quantity() {
        return quantity;
    }

    /** Everything bought over the position's life (quantity is what is still held). */
    public long boughtQuantity() {
        return boughtQuantity;
    }

    public int lots() {
        return (int) (quantity / contract.lotSize());
    }

    public double averageCost() {
        return averageCost;
    }

    public double realised() {
        return realised;
    }

    public double costs() {
        return costs;
    }

    /** Held quantity valued at the bid (what could be sold now). */
    /** The last bid seen for this contract (NaN before any quote). */
    public double lastBid() {
        return lastBid;
    }

    /** The last ask seen for this contract (NaN before any quote); what a short would pay to buy back. */
    public double lastAsk() {
        return lastAsk;
    }

    public double lastPrice() {
        return lastPrice;
    }

    /** Held quantity marked to the bid (a long: what it could sell for) or, for a short, to the ask (what buying back costs). */
    public double unrealised() {
        if (quantity <= 0) {
            return 0;
        }
        if (isShort()) {
            return !Double.isNaN(lastAsk) ? (averageCost - lastAsk) * quantity : 0;
        }
        return !Double.isNaN(lastBid) ? (lastBid - averageCost) * quantity : 0;
    }

    public double net() {
        return realised + unrealised() - costs;
    }

    public String exitReason() {
        return exitReason;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public List<String> stages() {
        return List.copyOf(stages);
    }

    public boolean isLive() {
        return state != State.CLOSED;
    }
}
