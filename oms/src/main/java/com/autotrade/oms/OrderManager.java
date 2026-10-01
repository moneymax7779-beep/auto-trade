package com.autotrade.oms;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.autotrade.broker.BrokerGateway;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.risk.RiskCheck;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.RiskEngine;
import com.autotrade.risk.RiskLimits;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.Side;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;

/**
 * Runs one account's positions (one per strategy and underlying, or a call and a put for a
 * straddle) on a broker.
 *
 * <ul>
 *   <li>Entries and adds are risk-checked, priced as marketable limits (ask + buffer ticks), sliced
 *       to the freeze quantity, and cancelled if unfilled after the entry timeout.</li>
 *   <li>Every filled quantity is covered by a resting stop-limit sell at the premium stop below
 *       the average cost; it is resized after each add.</li>
 *   <li>An exit first cancels the stop and any working entries, and sells only after those cancels
 *       are confirmed, so a stop and an exit can never both sell. Unfilled exits are re-priced at
 *       the new bid and finally priced well through it.</li>
 *   <li>{@link #reconcile()} compares positions with the broker; a mismatch engages the global
 *       kill switch.</li>
 *   <li>A straddle's two legs carry no resting stops; on every quote their combined bid is compared
 *       with the combined premium paid, and both legs are closed at the target or the stop. A leg
 *       whose entry does not fill is re-priced at the new ask every exit-chase interval (up to the
 *       exit-chase limit); a leg that never fills closes the other leg, so no naked leg is kept.</li>
 * </ul>
 */
public final class OrderManager implements Consumer<OrderUpdate> {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");
    private static final double FINAL_EXIT_DISCOUNT = 0.95;
    private static final Map<String, String> ROLE_CODES = Map.of("ENTRY", "B", "STOP", "S", "EXIT", "X");

    private record Role(ManagedPosition position, String role) {
    }

    /** A straddle's combined exit: fractions above / below the combined premium paid. */
    private record Bracket(double target, double stop) {
    }

    private final String account;
    private final String orderPrefix;
    private final String strategy;
    private final String dayCode;
    private final LocalDate session;
    private final BrokerGateway broker;
    private final RiskEngine risk;
    private final RiskLimits limits;
    private final CostModel costs;
    private final double stopFraction;
    private final Supplier<Instant> clock;
    private final OmsListener listener;
    private final Map<String, ManagedPosition> live = new LinkedHashMap<>();
    private final List<ManagedPosition> closed = new ArrayList<>();
    private final Map<String, Role> roles = new HashMap<>();
    private final Deque<Instant> recentOrders = new ArrayDeque<>();
    private final Set<String> terminal = new HashSet<>();
    private final Map<Long, double[]> lastQuotes = new HashMap<>();
    private final Map<Long, Double> lastDeltas = new HashMap<>();
    private final Map<String, Bracket> brackets = new HashMap<>();
    private int sequence;

    /**
     * @param orderPrefix unique per trading session (e.g. account and session id), so client order
     *                    ids never repeat across runs; the broker treats a repeated id as a duplicate
     */
    public OrderManager(String account, String orderPrefix, String strategy, LocalDate session, BrokerGateway broker,
                        RiskEngine risk, CostModel costs, double stopPct, Supplier<Instant> clock, OmsListener listener) {
        this.account = account;
        this.orderPrefix = orderPrefix;
        this.strategy = strategy;
        this.session = session;
        this.dayCode = session.format(DAY);
        this.broker = broker;
        this.risk = risk;
        this.limits = risk.limits();
        this.costs = costs;
        this.stopFraction = stopPct / 100.0;
        this.clock = clock;
        this.listener = listener;
        broker.onUpdate(this);
    }

    // ---------------------------------------------------------------- strategy-facing

    /** The default strategy's position (single-strategy callers). */
    public synchronized PositionView view(String underlying) {
        return view(strategy, underlying);
    }

    /** What {@code strategyId} holds in {@code underlying} (never another strategy's position). */
    public synchronized PositionView view(String strategyId, String underlying) {
        List<ManagedPosition> held = positionsOf(strategyId, underlying);
        if (held.isEmpty()) {
            return PositionView.FLAT;
        }
        ManagedPosition position = held.getFirst();
        double[] quote = lastQuotes.get(position.contract.token());
        double bid = quote == null || !(quote[0] > 0) ? Double.NaN : quote[0];
        return new PositionView(true, position.side, position.lots(), position.averageCost, position.openedAt, bid);
    }

    /** Buys {@code lots} to open a position with the default premium stop; returns the risk decision. */
    public synchronized RiskDecision enter(String underlying, OptionSide side, Contract contract, int lots, String stage,
                                           MarketContext market) {
        return enter(strategy, underlying, side, contract, lots, stage, market, Double.NaN);
    }

    public synchronized RiskDecision enter(String underlying, OptionSide side, Contract contract, int lots, String stage,
                                           MarketContext market, double stopPct) {
        return enter(strategy, underlying, side, contract, lots, stage, market, stopPct);
    }

    /**
     * Buys {@code lots} for {@code strategyId} to open a position; {@code stopPct} is this position's
     * resting premium stop (percent below average cost), NaN for the default. Risk limits (open
     * positions, lots per underlying, order rate, daily loss) count every strategy's positions.
     */
    public synchronized RiskDecision enter(String strategyId, String underlying, OptionSide side, Contract contract,
                                           int lots, String stage, MarketContext market, double stopPct) {
        if (!positionsOf(strategyId, underlying).isEmpty()) {
            return reject(strategyId, underlying, "ENTER", strategyId + " already holds a position in " + underlying);
        }
        RiskDecision decision = riskCheck(strategyId, underlying, false, lots, lotsHeld(underlying), contract, market, null,
                lots * contract.lotSize() * askOf(contract), live.size());
        if (!decision.approved()) {
            return reject(strategyId, underlying, "ENTER " + side, decision.reason());
        }
        ManagedPosition position = new ManagedPosition(strategyId, underlying, side, contract, clock.get());
        position.stopFraction = Double.isNaN(stopPct) ? stopFraction : stopPct / 100.0;
        live.put(position.key(), position);
        buy(position, lots, stage);
        return decision;
    }

    /**
     * Buys a call and a put together for {@code strategyId}: as many lots of each as {@code premiumBudget}
     * pays for at the current asks, capped by the capital not already in use. Neither leg gets a
     * resting stop; the pair is closed when its combined bid reaches {@code targetPct} above or
     * {@code stopPct} below the combined premium paid (checked on every quote).
     */
    public synchronized RiskDecision enterStraddle(String strategyId, String underlying, Contract call, Contract put,
                                                   double premiumBudget, String stage, MarketContext market,
                                                   double targetPct, double stopPct) {
        if (!positionsOf(strategyId, underlying).isEmpty()) {
            return reject(strategyId, underlying, "ENTER STRADDLE", strategyId + " already holds a position in "
                    + underlying);
        }
        double callAsk = askOf(call);
        double putAsk = askOf(put);
        if (!(callAsk > 0) || !(putAsk > 0)) {
            return reject(strategyId, underlying, "ENTER STRADDLE", "no ask for " + call.symbol() + " / " + put.symbol());
        }
        double perLot = (callAsk + putAsk) * call.lotSize();
        double free = limits.capital() - premiumInUse();
        int lots = (int) Math.floor(Math.min(premiumBudget, free) / perLot);
        if (lots < 1) {
            return reject(strategyId, underlying, "ENTER STRADDLE", String.format(
                    "capital: %.0f free, one lot of each leg costs %.0f", free, perLot));
        }
        int held = lotsHeld(underlying);
        RiskDecision decision = riskCheck(strategyId, underlying, false, lots, held, call, market, null,
                lots * perLot, live.size());
        if (decision.approved()) {
            decision = riskCheck(strategyId, underlying, false, lots, held + lots, put, market, null, 0, live.size() + 1);
        }
        if (!decision.approved()) {
            return reject(strategyId, underlying, "ENTER STRADDLE", decision.reason());
        }
        for (Contract contract : List.of(call, put)) {
            OptionSide side = contract == call ? OptionSide.CE : OptionSide.PE;
            ManagedPosition leg = new ManagedPosition(strategyId, underlying, side, contract, clock.get(), side.name());
            leg.stopFraction = 0;
            live.put(leg.key(), leg);
            buy(leg, lots, stage);
        }
        brackets.put(key(strategyId, underlying), new Bracket(targetPct / 100.0, stopPct / 100.0));
        return decision;
    }

    public synchronized RiskDecision add(String underlying, int lots, String stage, MarketContext market) {
        return add(strategy, underlying, lots, stage, market);
    }

    public synchronized RiskDecision add(String strategyId, String underlying, int lots, String stage,
                                         MarketContext market) {
        ManagedPosition position = live.get(key(strategyId, underlying) + "|");
        if (position == null || (position.state != ManagedPosition.State.OPEN
                && position.state != ManagedPosition.State.OPENING)) {
            return reject(strategyId, underlying, "ADD", "no open position to add to");
        }
        RiskDecision decision = riskCheck(strategyId, underlying, true, lots, lotsHeld(underlying), position.contract, market,
                position, lots * position.contract.lotSize() * askOf(position.contract), live.size());
        if (!decision.approved()) {
            return reject(strategyId, underlying, "ADD", decision.reason());
        }
        buy(position, lots, stage);
        return decision;
    }

    /** Closes the default strategy's position; always allowed. */
    public synchronized void exit(String underlying, String reason) {
        exit(strategy, underlying, reason);
    }

    /** Closes {@code strategyId}'s position in {@code underlying} (both legs of a straddle); always allowed. */
    public synchronized void exit(String strategyId, String underlying, String reason) {
        for (ManagedPosition position : positionsOf(strategyId, underlying)) {
            exit(position, reason);
        }
    }

    private void exit(ManagedPosition position, String reason) {
        if (position.state == ManagedPosition.State.CANCELLING_FOR_EXIT
                || position.state == ManagedPosition.State.EXITING || position.state == ManagedPosition.State.CLOSED) {
            return;
        }
        position.exitReason = reason;
        position.state = ManagedPosition.State.CANCELLING_FOR_EXIT;
        for (String id : working(position.stopOrders)) {
            broker.cancel(id);
        }
        for (String id : working(position.entryOrders)) {
            broker.cancel(id);
        }
        maybeSellAfterCancels(position);
    }

    public synchronized void exitAll(String reason) {
        for (ManagedPosition position : List.copyOf(live.values())) {
            exit(position.strategy, position.underlying, reason);
        }
    }

    /** The strategy-and-underlying part of a position key (a straddle's legs share it). */
    private static String key(String strategyId, String underlying) {
        return strategyId + "|" + underlying;
    }

    /** Every live position of {@code strategyId} in {@code underlying}: one, or a straddle's two legs. */
    private List<ManagedPosition> positionsOf(String strategyId, String underlying) {
        List<ManagedPosition> held = new ArrayList<>();
        for (ManagedPosition position : live.values()) {
            if (position.strategy.equals(strategyId) && position.underlying.equals(underlying)) {
                held.add(position);
            }
        }
        return held;
    }

    /** Capital tied up by every live position (the capital check counts all strategies). */
    private double premiumInUse() {
        return premiumInUse(null);
    }

    /** Capital tied up by {@code strategyId}'s live positions across underlyings (null = every strategy). */
    public synchronized double premiumInUse(String strategyId) {
        double total = 0;
        for (ManagedPosition position : live.values()) {
            if (strategyId != null && !strategyId.equals(position.strategy)) {
                continue;
            }
            // while entries work, what they asked for; afterwards what was actually paid
            total += working(position.entryOrders).isEmpty() ? position.averageCost * position.quantity
                    : position.premiumInUse();
        }
        return total;
    }

    /**
     * A live straddle's combined exit as percentages {target, stop} of the premium paid; null for a
     * single-leg position or once the bracket has fired.
     */
    public synchronized double[] bracket(String strategyId, String underlying) {
        Bracket bracket = brackets.get(key(strategyId, underlying));
        return bracket == null ? null : new double[] {bracket.target() * 100, bracket.stop() * 100};
    }

    /** The contract's last ask seen (NaN before any quote). */
    public synchronized double ask(Contract contract) {
        return askOf(contract);
    }

    /** The contract's last non-zero feed delta (NaN before any). */
    public synchronized double delta(Contract contract) {
        Double delta = lastDeltas.get(contract.token());
        return delta != null ? delta : Double.NaN;
    }

    private double askOf(Contract contract) {
        double[] quote = lastQuotes.get(contract.token());
        return quote != null ? quote[1] : Double.NaN;
    }

    /** Lots every strategy holds in {@code underlying} (the per-underlying risk limit is shared). */
    private int lotsHeld(String underlying) {
        int lots = 0;
        for (ManagedPosition position : live.values()) {
            if (position.underlying.equals(underlying)) {
                lots += position.lots();
            }
        }
        return lots;
    }

    // ---------------------------------------------------------------- market and time

    /** Latest quotes for held contracts, entry timeouts and exit chasing. */
    public synchronized void onQuote(OptionTick tick) {
        for (ManagedPosition position : live.values()) {
            if (position.contract.token() == tick.instrumentToken()) {
                if (!tick.bids().isEmpty()) {
                    position.lastBid = tick.bids().price(0);
                }
                if (!tick.asks().isEmpty()) {
                    position.lastAsk = tick.asks().price(0);
                }
                position.lastPrice = tick.lastPrice();
            }
        }
        checkBrackets();
        onTimer(tick.receivedAt());
    }

    /**
     * Closes a straddle once its legs' combined bid reaches the target or the stop against the
     * combined premium paid. Checked only when no entry order is still working (the size is final).
     */
    private void checkBrackets() {
        for (Map.Entry<String, Bracket> entry : List.copyOf(brackets.entrySet())) {
            String[] parts = entry.getKey().split("\\|", 2);
            List<ManagedPosition> legs = positionsOf(parts[0], parts[1]);
            if (legs.isEmpty()) {
                brackets.remove(entry.getKey());
                continue;
            }
            double paid = 0;
            double value = 0;
            for (ManagedPosition leg : legs) {
                if (leg.state != ManagedPosition.State.OPEN || !working(leg.entryOrders).isEmpty()) {
                    paid = Double.NaN;
                    break;
                }
                double[] quote = lastQuotes.get(leg.contract.token());
                double bid = quote != null ? quote[0] : leg.lastBid;
                paid += leg.averageCost * leg.quantity;
                value += bid * leg.quantity;
            }
            if (!(paid > 0) || Double.isNaN(value)) {
                continue;
            }
            Bracket bracket = entry.getValue();
            String reason = value >= paid * (1 + bracket.target()) ? "TARGET_" + Math.round(bracket.target() * 100)
                    : value <= paid * (1 - bracket.stop()) ? "STOP_" + Math.round(bracket.stop() * 100) : null;
            if (reason != null) {
                brackets.remove(entry.getKey());
                for (ManagedPosition leg : legs) {
                    exit(leg, reason);
                }
            }
        }
    }

    public synchronized void onTimer(Instant now) {
        for (ManagedPosition position : List.copyOf(live.values())) {
            if (!position.leg.isEmpty()) {
                chaseLegEntry(position, now);
            } else {
                for (String id : working(position.entryOrders)) {
                    Instant sent = position.orderSentAt.get(id);
                    if (sent != null && Duration.between(sent, now).toSeconds() >= limits.entryTimeoutSec()) {
                        broker.cancel(id);
                        position.orderSentAt.remove(id);
                    }
                }
            }
            if (position.state == ManagedPosition.State.EXITING && position.exitPricedAt != null
                    && Duration.between(position.exitPricedAt, now).toSeconds() >= limits.exitChaseSec()) {
                chaseExit(position);
            }
        }
    }

    /**
     * A straddle leg must fill or the pair is not a straddle: its unfilled entries are re-priced at
     * the current ask (the last attempt 2 % through it) every exit-chase interval, and cancelled after
     * the exit-chase limit; a leg left with nothing bought then closes its partner (see close()).
     */
    private void chaseLegEntry(ManagedPosition position, Instant now) {
        if (position.state != ManagedPosition.State.OPENING && position.state != ManagedPosition.State.OPEN) {
            return;
        }
        List<String> entries = working(position.entryOrders);
        if (entries.isEmpty()) {
            return;
        }
        Instant sent = null;
        for (String id : entries) {
            Instant at = position.orderSentAt.get(id);
            sent = sent == null || (at != null && at.isAfter(sent)) ? at : sent;
        }
        if (sent == null || Duration.between(sent, now).toSeconds() < limits.exitChaseSec()) {
            return;
        }
        if (position.entryChases >= limits.exitChaseMax()) {
            for (String id : entries) {
                broker.cancel(id);
                position.orderSentAt.remove(id);
            }
            return;
        }
        double ask = askOf(position.contract);
        if (!(ask > 0)) {
            return;
        }
        position.entryChases++;
        double limit = position.entryChases == limits.exitChaseMax() ? ask * 1.02
                : ask + limits.entryBufferTicks() * position.contract.tickSize();
        limit = position.contract.roundUp(limit);
        for (String id : entries) {
            broker.modify(id, position.orderQuantity.getOrDefault(id, 0L), limit, 0);
            position.orderSentAt.put(id, now);
        }
    }

    // ---------------------------------------------------------------- broker updates

    @Override
    public synchronized void accept(OrderUpdate update) {
        Role role = roles.get(update.clientOrderId());
        if (role == null) {
            return;
        }
        ManagedPosition position = role.position();
        if (update.status().isTerminal()) {
            terminal.add(update.clientOrderId());
        }
        listener.orderUpdated(position, role.role(), update);
        long previous = position.orderFilled.getOrDefault(update.clientOrderId(), 0L);
        long delta = update.filledQuantity() - previous;
        if (delta > 0) {
            position.orderFilled.put(update.clientOrderId(), update.filledQuantity());
            double price = update.lastFillPrice();
            if (role.role().equals("ENTRY")) {
                position.averageCost = (position.averageCost * position.quantity + price * delta)
                        / (position.quantity + delta);
                position.quantity += delta;
                position.boughtQuantity += delta;
                position.costs += costs.costs(session, position.contract.exchange(), Side.BUY, price, delta).total();
                if (position.state == ManagedPosition.State.OPENING) {
                    position.state = ManagedPosition.State.OPEN;
                }
                if (position.state == ManagedPosition.State.OPEN) {
                    protect(position);
                }
            } else {
                position.realised += (price - position.averageCost) * delta;
                position.quantity -= delta;
                position.costs += costs.costs(session, position.contract.exchange(), Side.SELL, price, delta).total();
                if (role.role().equals("STOP")) {
                    if (position.exitReason == null) {
                        position.exitReason = "PREMIUM_STOP";
                    }
                    if (position.quantity == 0) {
                        for (String id : working(position.entryOrders)) {
                            broker.cancel(id); // stopped out: do not let a late entry reopen it
                        }
                    }
                }
            }
        }
        if (position.quantity == 0 && position.boughtQuantity > 0 && !anyWorking(position)
                && position.state != ManagedPosition.State.OPENING) {
            close(position);
            return;
        }
        if (position.state == ManagedPosition.State.CANCELLING_FOR_EXIT) {
            maybeSellAfterCancels(position);
        }
        if (position.boughtQuantity == 0 && !anyWorking(position)) {
            position.exitReason = position.exitReason == null ? "ENTRY_NOT_FILLED" : position.exitReason;
            close(position);
        }
    }

    // ---------------------------------------------------------------- reconciliation and P&L

    /** Compares held quantities with the broker's positions; engages the global kill switch on a mismatch. */
    public synchronized boolean reconcile() {
        Map<Long, Long> expected = new HashMap<>();
        for (ManagedPosition position : live.values()) {
            expected.merge(position.contract.token(), position.quantity, Long::sum);
        }
        Map<Long, Long> actual = new HashMap<>();
        for (BrokerPosition position : broker.positions()) {
            if (position.netQuantity() != 0) {
                actual.put(position.instrumentToken(), position.netQuantity());
            }
        }
        expected.values().removeIf(quantity -> quantity == 0);
        if (!expected.equals(actual)) {
            String message = "reconciliation mismatch: OMS " + expected + " broker " + actual;
            listener.alert(message);
            risk.killSwitch().engage("GLOBAL", message, clock.get(), "oms");
            return false;
        }
        return true;
    }

    /** Realised + marked-to-bid − costs, over every position today. */
    public synchronized double dayPnl() {
        double total = 0;
        for (ManagedPosition position : live.values()) {
            total += position.net();
        }
        for (ManagedPosition position : closed) {
            total += position.net();
        }
        return total;
    }

    public synchronized List<ManagedPosition> livePositions() {
        return List.copyOf(live.values());
    }

    public synchronized List<ManagedPosition> closedPositions() {
        return List.copyOf(closed);
    }

    // ---------------------------------------------------------------- internals

    private RiskDecision riskCheck(String strategyId, String underlying, boolean add, int lots, int held,
                                   Contract contract, MarketContext market, ManagedPosition existing, double premium,
                                   int openPositions) {
        Instant now = clock.get();
        while (!recentOrders.isEmpty() && Duration.between(recentOrders.peekFirst(), now).toSeconds() >= 60) {
            recentOrders.removeFirst();
        }
        double spreadPct = Double.NaN;
        double bid = existing == null ? Double.NaN : existing.lastBid;
        double ask = existing == null ? Double.NaN : existing.lastAsk;
        if (existing == null || !(bid > 0 && ask > 0)) {
            double[] quote = lastQuotes.get(contract.token());
            if (quote != null) {
                bid = quote[0];
                ask = quote[1];
            }
        }
        if (bid > 0 && ask > 0) {
            spreadPct = (ask - bid) / ((ask + bid) / 2) * 100;
        }
        return risk.checkEntry(new RiskCheck(account, strategyId, underlying, add, lots, held, openPositions, dayPnl(),
                market.secondsSinceSpot(), market.secondsSinceOption(), spreadPct, market.time(), recentOrders.size(),
                Double.isNaN(premium) ? 0 : premium, premiumInUse()), now);
    }


    /** Remembers the touch of every option seen, so entries can be priced before a position exists. */
    public synchronized void observe(OptionTick tick) {
        if (!tick.bids().isEmpty() && !tick.asks().isEmpty()) {
            lastQuotes.put(tick.instrumentToken(), new double[] {tick.bids().price(0), tick.asks().price(0)});
        }
        if (tick.delta() != null && tick.delta() != 0 && Double.isFinite(tick.delta())) {
            lastDeltas.put(tick.instrumentToken(), tick.delta());
        }
        onQuote(tick);
    }

    private void buy(ManagedPosition position, int lots, String stage) {
        position.stages.add(stage);
        double[] quote = lastQuotes.get(position.contract.token());
        double ask = quote != null ? quote[1] : position.lastAsk;
        if (!(ask > 0)) {
            listener.rejected(position.strategy, position.underlying, "BUY", "no ask for " + position.contract.symbol());
            return;
        }
        double limit = position.contract.roundUp(ask + limits.entryBufferTicks() * position.contract.tickSize());
        long remaining = (long) lots * position.contract.lotSize();
        position.committedPremium += remaining * ask;
        long slice = (long) position.contract.maxLotsPerOrder() * position.contract.lotSize();
        while (remaining > 0) {
            long quantity = Math.min(remaining, slice);
            send(position, "ENTRY", OrderSide.BUY, OrderType.LIMIT, quantity, limit, 0, position.entryOrders);
            remaining -= quantity;
        }
    }

    /**
     * Places or resizes the resting stop so it covers exactly the held quantity. A single stop is
     * modified in place (never two live stops); only above the freeze quantity is it replaced.
     */
    private void protect(ManagedPosition position) {
        if (position.stopFraction <= 0) {
            return; // a straddle leg: the pair's combined bracket protects it
        }
        double trigger = position.contract.roundDown(position.averageCost * (1 - position.stopFraction));
        double limit = position.contract.roundDown(trigger * (1 - limits.stopLimitOffsetPct() / 100));
        long slice = (long) position.contract.maxLotsPerOrder() * position.contract.lotSize();
        List<String> stops = working(position.stopOrders);
        if (stops.size() == 1 && position.quantity <= slice) {
            broker.modify(stops.getFirst(), position.quantity, limit, trigger);
            return;
        }
        for (String id : stops) {
            broker.cancel(id);
        }
        long remaining = position.quantity;
        while (remaining > 0) {
            long quantity = Math.min(remaining, slice);
            send(position, "STOP", OrderSide.SELL, OrderType.STOP_LIMIT, quantity, limit, trigger, position.stopOrders);
            remaining -= quantity;
        }
    }

    private void maybeSellAfterCancels(ManagedPosition position) {
        if (position.state != ManagedPosition.State.CANCELLING_FOR_EXIT || !working(position.stopOrders).isEmpty()
                || !working(position.entryOrders).isEmpty() || !working(position.exitOrders).isEmpty()) {
            return;
        }
        if (position.quantity == 0) {
            close(position);
            return;
        }
        position.state = ManagedPosition.State.EXITING;
        sellAll(position, sellPrice(position));
    }

    private void chaseExit(ManagedPosition position) {
        for (String id : working(position.exitOrders)) {
            broker.cancel(id);
        }
        position.exitChases++;
        position.state = ManagedPosition.State.CANCELLING_FOR_EXIT;
        position.exitPricedAt = null;
    }

    private double sellPrice(ManagedPosition position) {
        double bid = position.lastBid;
        double[] quote = lastQuotes.get(position.contract.token());
        if (quote != null) {
            bid = quote[0];
        }
        if (!(bid > 0)) {
            bid = position.lastPrice > 0 ? position.lastPrice : position.averageCost;
        }
        double buffer = Math.max(limits.exitBufferTicks() * position.contract.tickSize(),
                bid * limits.exitBufferPct() / 100);
        double price = position.exitChases >= limits.exitChaseMax() ? bid * FINAL_EXIT_DISCOUNT : bid - buffer;
        return position.contract.roundDown(price);
    }

    private void sellAll(ManagedPosition position, double limit) {
        long remaining = position.quantity;
        long slice = (long) position.contract.maxLotsPerOrder() * position.contract.lotSize();
        while (remaining > 0) {
            long quantity = Math.min(remaining, slice);
            send(position, "EXIT", OrderSide.SELL, OrderType.LIMIT, quantity, limit, 0, position.exitOrders);
            remaining -= quantity;
        }
        position.exitPricedAt = clock.get();
    }

    private void send(ManagedPosition position, String role, OrderSide side, OrderType type, long quantity,
                      double limit, double trigger, Set<String> bucket) {
        String id = orderPrefix + "-" + dayCode + "-" + position.underlying + "-" + position.side + "-" + (++sequence)
                + ROLE_CODES.get(role);
        OrderRequest request = new OrderRequest(id, position.contract.token(), position.contract.instrumentKey(),
                position.contract.symbol(), position.contract.exchange(), side, type, quantity, limit, trigger,
                position.strategy);
        roles.put(id, new Role(position, role));
        bucket.add(id);
        position.orderQuantity.put(id, quantity);
        position.orderSentAt.put(id, clock.get());
        recentOrders.addLast(clock.get());
        listener.orderSent(position, role, request);
        broker.place(request);
    }

    /** Orders in {@code ids} that are not yet terminal (per the last update seen). */
    private List<String> working(Set<String> ids) {
        List<String> working = new ArrayList<>();
        for (String id : ids) {
            if (!terminal.contains(id)) {
                working.add(id);
            }
        }
        return working;
    }

    private boolean anyWorking(ManagedPosition position) {
        return !working(position.entryOrders).isEmpty() || !working(position.stopOrders).isEmpty()
                || !working(position.exitOrders).isEmpty();
    }

    private void close(ManagedPosition position) {
        if (position.state == ManagedPosition.State.CLOSED) {
            return;
        }
        for (String id : working(position.stopOrders)) {
            broker.cancel(id);
        }
        position.state = ManagedPosition.State.CLOSED;
        position.closedAt = clock.get();
        live.remove(position.key());
        closed.add(position);
        listener.closed(position);
        if (!position.leg.isEmpty() && "ENTRY_NOT_FILLED".equals(position.exitReason)) {
            // one leg of a straddle never filled: never keep the other as a naked position
            brackets.remove(key(position.strategy, position.underlying));
            for (ManagedPosition partner : positionsOf(position.strategy, position.underlying)) {
                exit(partner, "LEG_NOT_FILLED");
            }
        }
    }

    private RiskDecision reject(String strategyId, String underlying, String intent, String reason) {
        listener.rejected(strategyId, underlying, intent, reason);
        return RiskDecision.rejected(reason);
    }

    /** Session date and time helpers for callers. */
    public LocalDate session() {
        return session;
    }

    public LocalTime marketTime() {
        return clock.get().atZone(MarketTime.IST).toLocalTime();
    }
}
