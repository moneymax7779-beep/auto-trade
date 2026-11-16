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
 * can lag a fill by a moment; across all products it also counts what the account carried into today
 * ({@link BrokerConnection#carriedIn}, review 16 Nov 2026: an NRML lot carried overnight and sold by hand today netted
 * out our MIS buy of the same contract, and our exit and stop were refused with nothing said). An order whose tag is already in that book is not sent again. Reads, cancels, polls and
 * updates pass through unchanged: cancelling only ever reduces risk.
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
        List<BrokerOrder> book = book(r.clientOrderId());
        String product = broker.product();
        if (book.stream().anyMatch(o -> r.clientOrderId().equals(o.clientTag()))) {
            return;                 // code review 3 Nov 2026: this tag is already at the broker (a place that timed out
        }                           // and showed up late, a restart): never a second order with the same tag
        if (r.side() == OrderSide.BUY) {
            if (r.type() != OrderType.LIMIT) {
                throw refused("a buy is a limit order only, got " + r.type());
            }
            checkBuyQuantity(r.instrumentKey(), r.quantity());
            Set<String> held = new HashSet<>();
            for (BrokerOrder o : book) {                       // derivatives only: a share bought by hand is no contract
                if (!o.ofProduct(product)) {
                    continue;                                  // 16 Nov 2026: a hand NRML trade takes no MIS slot
                }
                if (derivative(o.instrumentKey()) && (held(book, o.instrumentKey(), product) > 0
                        || (o.side() == OrderSide.BUY && !o.status().isTerminal()))) {
                    held.add(o.instrumentKey());
                }
            }
            if (!held.contains(r.instrumentKey()) && held.size() >= limits.maxOpenPositions()) {
                throw refused("max " + limits.maxOpenPositions() + " contracts held or being bought: " + held);
            }
        } else {
            long free = free(book, r.instrumentKey(), r.clientOrderId(), product, carried());
            if (r.quantity() > free) {
                throw refused("sell " + r.quantity() + " of " + r.symbol() + " but only " + Math.max(0, free)
                        + " is held and not already being sold (in our product and across products): never short");
            }
        }
        broker.place(r);
    }

    @Override
    public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        List<BrokerOrder> book = book(clientOrderId);
        String product = broker.product();
        BrokerOrder o = book.stream().filter(b -> clientOrderId.equals(b.clientTag())).findFirst().orElse(null);
        if (o != null && quantity > o.quantity()) {                     // a lower quantity only reduces risk
            if (o.side() == OrderSide.BUY) {
                checkBuyQuantity(o.instrumentKey(), quantity);
            } else {
                long free = free(book, o.instrumentKey(), clientOrderId, product, carried()) + o.filledQuantity();
                if (quantity > free) {
                    throw refused("modify " + clientOrderId + " to sell " + quantity + " but only " + Math.max(0, free)
                            + " is held: never short");
                }
            }
        }
        broker.modify(clientOrderId, quantity, limitPrice, triggerPrice);
    }

    /**
     * The order book the checks read. Review 8 Nov 2026: a read that fails (a timeout is UNKNOWN at the HTTP layer) means
     * nothing was sent, so it is rethrown as RETRYABLE "not sent", never as an order of unknown outcome (which kills the
     * account and abandons an exit). A lost session stays SESSION.
     */
    private List<BrokerOrder> book(String clientOrderId) {
        try {
            return broker.orders();
        } catch (BrokerException e) {
            if (e.kind() == BrokerException.Kind.SESSION) {
                throw e;
            }
            throw new BrokerException(BrokerException.Kind.RETRYABLE, "LIVE guard: the order book could not be read, "
                    + clientOrderId + " not sent: " + e.getMessage(), e);
        }
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

    /** What the account carried into today (none when it cannot be read now: today's book alone, as before). */
    private java.util.Map<String, Long> carried() {
        try {
            return broker.carriedIn();
        } catch (BrokerException e) {
            if (e.kind() == BrokerException.Kind.SESSION) {
                throw e;
            }
            return java.util.Map.of();
        }
    }

    /**
     * Filled buys less filled sells of a contract in today's book, counting only orders of {@code product} (null: all).
     * 16 Nov 2026: the broker keeps MIS and NRML as separate positions, so an NRML buy by hand does not make an MIS sell
     * safe (it would be an MIS short), and does not count as a contract we hold.
     */
    static long held(List<BrokerOrder> book, String instrumentKey, String product) {
        long held = 0;
        for (BrokerOrder o : book) {
            if (instrumentKey.equals(o.instrumentKey()) && o.ofProduct(product)) {
                held += o.side() == OrderSide.BUY ? o.filledQuantity() : -o.filledQuantity();
            }
        }
        return held;
    }

    /**
     * What a sell of a contract may still sell: what is held less what other working sells (than {@code except}) could
     * sell, in our product (an NRML long does not cover an MIS sell) and across all products (16 Nov 2026: a hand NRML
     * sell of our MIS position leaves the account net flat; our MIS stop then selling too would leave an NRML short
     * nobody manages), whichever is less.
     */
    static long free(List<BrokerOrder> book, String instrumentKey, String except, String product,
                     java.util.Map<String, Long> carried) {
        long ours = held(book, instrumentKey, product) - workingSells(book, instrumentKey, except, product);
        long all = heldAll(book, instrumentKey, carried) - workingSells(book, instrumentKey, except, null);
        return Math.min(ours, all);
    }

    /** What is held of a contract in our product and across all products, whichever is less. */
    static long holds(List<BrokerOrder> book, String instrumentKey, String product, java.util.Map<String, Long> carried) {
        return Math.min(held(book, instrumentKey, product), heldAll(book, instrumentKey, carried));
    }

    /**
     * What the account holds of a contract across all products: what it carried into today plus today's filled buys less
     * filled sells of every product (a carried short is negative).
     */
    static long heldAll(List<BrokerOrder> book, String instrumentKey, java.util.Map<String, Long> carried) {
        return held(book, instrumentKey, null) + carried.getOrDefault(instrumentKey, 0L);
    }

    /** What working sells of a contract (other than {@code except}, of {@code product}; null: all) could still sell. */
    static long workingSells(List<BrokerOrder> book, String instrumentKey, String except, String product) {
        long working = 0;
        for (BrokerOrder o : book) {
            if (instrumentKey.equals(o.instrumentKey()) && o.ofProduct(product) && o.side() == OrderSide.SELL
                    && !o.status().isTerminal()
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
        return new Refusal("LIVE guard: " + why);
    }

    /**
     * An order the guard refused (TERMINAL, nothing sent). Review 8 Nov 2026: told apart from a broker's own refusal, so
     * a protective stop the guard refuses (the broker holds less than our book) is not answered with an emergency sell,
     * which the guard would refuse too.
     */
    public static final class Refusal extends BrokerException {
        Refusal(String message) {
            super(BrokerException.Kind.TERMINAL, message);
        }
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
    public String product() {
        return broker.product();
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
    public java.util.Map<String, Long> carriedIn() {
        return broker.carriedIn();
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
