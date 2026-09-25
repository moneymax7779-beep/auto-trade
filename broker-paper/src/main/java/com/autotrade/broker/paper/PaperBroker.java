package com.autotrade.broker.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerGateway;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;

/**
 * A broker that never leaves the process. Orders, modifications and cancels take effect
 * {@code latency} after they are sent (by the market clock), limit orders fill against the visible
 * depth at their limit or better (partially if the depth is thin), stop-limits trigger on the last
 * traded price as NSE/BSE stop orders do, and fills are moved {@code adverseBps} against the
 * trader without crossing the limit. Feed it every market event, in order.
 */
public final class PaperBroker implements BrokerGateway, Consumer<MarketEvent> {

    private static final class Order {
        final OrderRequest request;
        final String brokerOrderId;
        final Instant activeAt;
        OrderStatus status = OrderStatus.PENDING;
        long filled;
        double notional;
        double limit;
        double trigger;
        long quantity;
        boolean triggered;
        Instant cancelAt;
        Instant modifyAt;
        long pendingQuantity;
        double pendingLimit;
        double pendingTrigger;

        Order(OrderRequest request, String brokerOrderId, Instant activeAt) {
            this.request = request;
            this.brokerOrderId = brokerOrderId;
            this.activeAt = activeAt;
            this.limit = request.limitPrice();
            this.trigger = request.triggerPrice();
            this.quantity = request.quantity();
        }

        long remaining() {
            return quantity - filled;
        }

        double average() {
            return filled == 0 ? 0 : notional / filled;
        }
    }

    private static final class Holding {
        long quantity;
        double averagePrice;
        String symbol;
    }

    private final String name;
    private final Duration latency;
    private final double adverseBps;
    private final Supplier<Instant> clock;
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Map<Long, Holding> holdings = new HashMap<>();
    private final List<Consumer<OrderUpdate>> listeners = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * @param clock the market clock: the latest event time in replay, wall-clock time live
     */
    public PaperBroker(String name, Duration latency, double adverseBps, Supplier<Instant> clock) {
        this.name = name;
        this.latency = latency;
        this.adverseBps = adverseBps;
        this.clock = clock;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public BrokerCapabilities capabilities() {
        return new BrokerCapabilities(true, true, 10, 40, true);
    }

    @Override
    public void onUpdate(Consumer<OrderUpdate> listener) {
        listeners.add(listener);
    }

    @Override
    public synchronized void place(OrderRequest request) {
        if (orders.containsKey(request.clientOrderId())) {
            return; // idempotent
        }
        Order order = new Order(request, name + "-" + sequence.incrementAndGet(), clock.get().plus(latency));
        orders.put(request.clientOrderId(), order);
        emit(order, 0, 0, "received");
    }

    @Override
    public synchronized void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        Order order = orders.get(clientOrderId);
        if (order == null || order.status.isTerminal()) {
            return;
        }
        order.modifyAt = clock.get().plus(latency);
        order.pendingQuantity = quantity;
        order.pendingLimit = limitPrice;
        order.pendingTrigger = triggerPrice;
    }

    @Override
    public synchronized void cancel(String clientOrderId) {
        Order order = orders.get(clientOrderId);
        if (order != null && !order.status.isTerminal() && order.cancelAt == null) {
            order.cancelAt = clock.get().plus(latency);
        }
    }

    @Override
    public synchronized List<BrokerPosition> positions() {
        List<BrokerPosition> positions = new ArrayList<>();
        holdings.forEach((token, holding) -> positions.add(
                new BrokerPosition(token, holding.symbol, holding.quantity, holding.averagePrice)));
        return positions;
    }

    /**
     * Advances every order's time-based state (acceptance, cancels, modifications) on any event,
     * because a real broker acknowledges those whether or not the contract trades; fills and stop
     * triggers need the contract's own quotes.
     */
    @Override
    public synchronized void accept(MarketEvent event) {
        Instant now = event.receivedAt();
        OptionTick tick = event instanceof OptionTick optionTick ? optionTick : null;
        for (Order order : List.copyOf(orders.values())) {
            if (order.status.isTerminal() || now.isBefore(order.activeAt)) {
                continue;
            }
            if (order.status == OrderStatus.PENDING) {
                order.status = order.request.type() == OrderType.STOP_LIMIT ? OrderStatus.TRIGGER_PENDING
                        : OrderStatus.OPEN;
                emit(order, 0, 0, "accepted");
            }
            if (order.cancelAt != null && !now.isBefore(order.cancelAt)) {
                order.status = OrderStatus.CANCELLED;
                emit(order, 0, 0, "cancelled");
                continue;
            }
            if (order.modifyAt != null && !now.isBefore(order.modifyAt)) {
                if (order.pendingQuantity > 0) {
                    order.quantity = Math.max(order.filled, order.pendingQuantity);
                }
                order.limit = order.pendingLimit;
                order.trigger = order.pendingTrigger;
                order.modifyAt = null;
                emit(order, 0, 0, "modified");
            }
            if (tick == null || order.request.instrumentToken() != tick.instrumentToken()) {
                continue;
            }
            if (order.request.type() == OrderType.STOP_LIMIT && !order.triggered) {
                boolean hit = order.request.side() == OrderSide.SELL ? tick.lastPrice() <= order.trigger
                        : tick.lastPrice() >= order.trigger;
                if (!hit) {
                    continue;
                }
                order.triggered = true;
                order.status = OrderStatus.OPEN;
                emit(order, 0, 0, "triggered at LTP " + tick.lastPrice());
            }
            match(order, tick);
        }
    }

    /** Fills against the side of the book the order takes from, at the limit or better. */
    private void match(Order order, OptionTick tick) {
        boolean buy = order.request.side() == OrderSide.BUY;
        DepthLevels book = buy ? tick.asks() : tick.bids();
        long fillQuantity = 0;
        double fillNotional = 0;
        for (int i = 0; i < book.size() && fillQuantity < order.remaining(); i++) {
            double price = book.price(i);
            if (price <= 0 || book.quantity(i) <= 0 || (buy ? price > order.limit : price < order.limit)) {
                break;
            }
            long take = Math.min(order.remaining() - fillQuantity, book.quantity(i));
            fillQuantity += take;
            fillNotional += take * price;
        }
        if (fillQuantity == 0) {
            return;
        }
        double average = fillNotional / fillQuantity;
        double slipped = buy ? Math.min(order.limit, average * (1 + adverseBps / 10_000))
                : Math.max(order.limit, average * (1 - adverseBps / 10_000));
        order.filled += fillQuantity;
        order.notional += fillQuantity * slipped;
        order.status = order.remaining() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        book(order, fillQuantity, slipped);
        emit(order, fillQuantity, slipped, "fill");
    }

    private void book(Order order, long quantity, double price) {
        Holding holding = holdings.computeIfAbsent(order.request.instrumentToken(), t -> new Holding());
        holding.symbol = order.request.symbol();
        if (order.request.side() == OrderSide.BUY) {
            holding.averagePrice = (holding.averagePrice * holding.quantity + price * quantity)
                    / (holding.quantity + quantity);
            holding.quantity += quantity;
        } else {
            holding.quantity -= quantity;
            if (holding.quantity == 0) {
                holding.averagePrice = 0;
            }
        }
    }

    private void emit(Order order, long lastQuantity, double lastPrice, String message) {
        OrderUpdate update = new OrderUpdate(order.request.clientOrderId(), order.brokerOrderId, order.status,
                order.filled, order.average(), lastQuantity, lastPrice, clock.get(), message);
        for (Consumer<OrderUpdate> listener : listeners) {
            listener.accept(update);
        }
    }
}
