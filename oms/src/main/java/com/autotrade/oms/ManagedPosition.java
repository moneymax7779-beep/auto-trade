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

    final String underlying;
    final OptionSide side;
    final Contract contract;
    final Instant openedAt;
    final List<String> stages = new ArrayList<>();
    final Set<String> entryOrders = new LinkedHashSet<>();
    final Set<String> stopOrders = new LinkedHashSet<>();
    final Set<String> exitOrders = new LinkedHashSet<>();
    final Map<String, Instant> orderSentAt = new HashMap<>();
    final Map<String, Long> orderFilled = new HashMap<>();

    State state = State.OPENING;
    long quantity;
    double averageCost;
    long boughtQuantity;
    double realised;
    double costs;
    double lastBid = Double.NaN;
    double lastAsk = Double.NaN;
    double lastPrice = Double.NaN;
    String exitReason;
    /** This position's resting premium stop as a fraction below average cost. */
    double stopFraction;
    Instant closedAt;
    Instant exitPricedAt;
    int exitChases;

    ManagedPosition(String underlying, OptionSide side, Contract contract, Instant openedAt) {
        this.underlying = underlying;
        this.side = side;
        this.contract = contract;
        this.openedAt = openedAt;
    }

    public String underlying() {
        return underlying;
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
    public double unrealised() {
        return quantity > 0 && !Double.isNaN(lastBid) ? (lastBid - averageCost) * quantity : 0;
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
