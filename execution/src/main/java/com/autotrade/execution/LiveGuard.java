package com.autotrade.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;

/**
 * The last check in front of a LIVE broker connection (user, 2 Nov 2026: the Kite pilot from 4 Nov): whatever the
 * venue, the risk engine or a bug upstream asks, an order that breaks one of these rules never reaches the broker and is
 * refused as {@link BrokerException.Kind#TERMINAL} (refused, never shrunk):
 * <ul>
 *   <li>a buy is a limit order of at most {@code maxLotsPerOrder} lots of its contract;</li>
 *   <li>a buy in a new contract while {@code maxOpenPositions} contracts are already held or being bought is refused;</li>
 *   <li>a sell never sells more than is held: the day's filled buys less filled sells in that contract, less what other
 *       working sells there could still sell (no short option is ever created, by a stop and an exit both working or
 *       anything else); a modify that would raise a quantity past these is refused the same way.</li>
 * </ul>
 * What is held is read from the broker's own order book (the truth the venue also polls), not from its positions, which
 * can lag a fill by a moment. Reads, cancels, polls and updates pass through unchanged: cancelling only ever reduces risk.
 */
public final class LiveGuard implements BrokerConnection {

    private final BrokerConnection broker;
    private final FollowerRisk.LiveLimits limits;
    private final ToIntFunction<String> lotSize;

    /** @param lotSize the contract's lot size at the broker by our instrument key (0 when it is not listed) */
    public LiveGuard(BrokerConnection broker, FollowerRisk.LiveLimits limits, ToIntFunction<String> lotSize) {
        this.broker = broker;
        this.limits = limits;
        this.lotSize = lotSize;
    }

    @Override
    public void place(OrderRequest r) {
        List<BrokerOrder> book = broker.orders();
        if (r.side() == OrderSide.BUY) {
            if (r.type() != OrderType.LIMIT) {
                throw refused("a buy is a limit order only, got " + r.type());
            }
            checkBuyQuantity(r.instrumentKey(), r.quantity());
            Set<String> held = new HashSet<>();
            for (BrokerOrder o : book) {                       // derivatives only: a share bought by hand is no contract
                if (derivative(o.instrumentKey()) && (held(book, o.instrumentKey()) > 0
                        || (o.side() == OrderSide.BUY && !o.status().isTerminal()))) {
                    held.add(o.instrumentKey());
                }
            }
            if (!held.contains(r.instrumentKey()) && held.size() >= limits.maxOpenPositions()) {
                throw refused("max " + limits.maxOpenPositions() + " contracts held or being bought: " + held);
            }
        } else {
            long free = held(book, r.instrumentKey()) - workingSells(book, r.instrumentKey(), r.clientOrderId());
            if (r.quantity() > free) {
                throw refused("sell " + r.quantity() + " of " + r.symbol() + " but only " + Math.max(0, free)
                        + " is held and not already being sold: never short");
            }
        }
        broker.place(r);
    }

    @Override
    public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        List<BrokerOrder> book = broker.orders();
        BrokerOrder o = book.stream().filter(b -> clientOrderId.equals(b.clientTag())).findFirst().orElse(null);
        if (o != null && quantity > o.quantity()) {                     // a lower quantity only reduces risk
            if (o.side() == OrderSide.BUY) {
                checkBuyQuantity(o.instrumentKey(), quantity);
            } else {
                long free = held(book, o.instrumentKey()) - workingSells(book, o.instrumentKey(), clientOrderId)
                        + o.filledQuantity();
                if (quantity > free) {
                    throw refused("modify " + clientOrderId + " to sell " + quantity + " but only " + Math.max(0, free)
                            + " is held: never short");
                }
            }
        }
        broker.modify(clientOrderId, quantity, limitPrice, triggerPrice);
    }

    private void checkBuyQuantity(String instrumentKey, long quantity) {
        int lot = lotSize.applyAsInt(instrumentKey);
        if (lot <= 0) {
            throw refused(instrumentKey + " has no lot size at the broker");
        }
        if (quantity > (long) limits.maxLotsPerOrder() * lot) {
            throw refused("buy " + quantity + " is over " + limits.maxLotsPerOrder() + " lot(s) of " + lot);
        }
    }

    /** Filled buys less filled sells of a contract in today's book. */
    static long held(List<BrokerOrder> book, String instrumentKey) {
        long held = 0;
        for (BrokerOrder o : book) {
            if (instrumentKey.equals(o.instrumentKey())) {
                held += o.side() == OrderSide.BUY ? o.filledQuantity() : -o.filledQuantity();
            }
        }
        return held;
    }

    /** What working sells of a contract (other than {@code except}) could still sell. */
    static long workingSells(List<BrokerOrder> book, String instrumentKey, String except) {
        long working = 0;
        for (BrokerOrder o : book) {
            if (instrumentKey.equals(o.instrumentKey()) && o.side() == OrderSide.SELL && !o.status().isTerminal()
                    && !Objects.equals(o.clientTag(), except)) {               // a hand-placed order has no tag
                working += o.quantity() - o.filledQuantity();
            }
        }
        return working;
    }

    private static boolean derivative(String instrumentKey) {
        return instrumentKey != null && (instrumentKey.startsWith("NSE_FO|") || instrumentKey.startsWith("BSE_FO|"));
    }

    private static BrokerException refused(String why) {
        return new BrokerException(BrokerException.Kind.TERMINAL, "LIVE guard: " + why);
    }

    @Override
    public void cancel(String clientOrderId) {
        broker.cancel(clientOrderId);
    }

    @Override
    public String name() {
        return broker.name();
    }

    @Override
    public BrokerCapabilities capabilities() {
        return broker.capabilities();
    }

    @Override
    public double availableFunds() {
        return broker.availableFunds();
    }

    @Override
    public Quote quote(String instrumentKey) {
        return broker.quote(instrumentKey);
    }

    @Override
    public List<BrokerOrder> orders() {
        return broker.orders();
    }

    @Override
    public List<BrokerTrade> trades() {
        return broker.trades();
    }

    @Override
    public List<BrokerPosition> positions() {
        return broker.positions();
    }

    @Override
    public int poll() {
        return broker.poll();
    }

    @Override
    public void onUpdate(Consumer<OrderUpdate> listener) {
        broker.onUpdate(listener);
    }

    @Override
    public void close() {
        broker.close();
    }
}
