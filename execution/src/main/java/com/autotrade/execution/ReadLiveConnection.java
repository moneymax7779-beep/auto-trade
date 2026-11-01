package com.autotrade.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.UnaryOperator;

import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;

/**
 * A DRY_RUN connection that reads the real broker and trades a simulator (multi-broker plan section 3.2, step K1): funds
 * and quotes (with depth) come from the broker's read API (the funds less what the simulator's orders and positions
 * hold: the real balance never falls for a simulated buy, and the broker would count it); every order call, the order and trade books, positions and
 * updates belong to the simulator. Each broker quote is handed to {@code book} first, so the simulator fills our order
 * against the broker's book of that moment; the quote returned is the book as the simulator then holds it (what our own
 * recent fills took is gone from it), so prices follow the book our orders really face.
 * <p>
 * The simulator's prices move only when the broker is quoted, so whenever its order book is read while a protective stop
 * rests there, that contract is quoted again (at most every {@link #STOP_REFRESH} per contract, inside Kite's one quote
 * call a second): a stop the real market reached then triggers and fills against the real bids.
 * <p>
 * The broker side is wrapped by {@link #readOnly}: whatever calls this connection, no place, modify or cancel can reach
 * the real broker.
 */
public final class ReadLiveConnection implements BrokerConnection {

    private final BrokerConnection broker;
    private final BrokerConnection simulator;
    private final UnaryOperator<Quote> book;
    private final DoubleSupplier committed;
    private final Map<String, Instant> refreshed = new ConcurrentHashMap<>();

    /** How often the book of a contract with a resting stop is read from the broker again. */
    static final Duration STOP_REFRESH = Duration.ofSeconds(5);

    /**
     * @param broker    the real broker's connection, used for reads only (wrapped read-only here)
     * @param simulator where orders go
     * @param book      sets the simulator's book of a contract from a broker quote and returns that book as a quote
     * @param committed the premium the simulator's open buy orders and positions hold (taken off the broker's funds)
     */
    public ReadLiveConnection(BrokerConnection broker, BrokerConnection simulator, UnaryOperator<Quote> book,
                              DoubleSupplier committed) {
        this.broker = readOnly(broker);
        this.simulator = simulator;
        this.book = book;
        this.committed = committed;
    }

    @Override
    public String name() {
        return simulator.name();
    }

    @Override
    public BrokerCapabilities capabilities() {
        return simulator.capabilities();
    }

    @Override
    public double availableFunds() {
        return broker.availableFunds() - Math.max(0, committed.getAsDouble());
    }

    @Override
    public Quote quote(String instrumentKey) {
        return book.apply(broker.quote(instrumentKey));
    }

    @Override
    public void onUpdate(Consumer<OrderUpdate> listener) {
        simulator.onUpdate(listener);
    }

    @Override
    public void place(OrderRequest request) {
        simulator.place(request);
    }

    @Override
    public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        simulator.modify(clientOrderId, quantity, limitPrice, triggerPrice);
    }

    @Override
    public void cancel(String clientOrderId) {
        simulator.cancel(clientOrderId);
    }

    @Override
    public List<BrokerPosition> positions() {
        return simulator.positions();
    }

    @Override
    public List<BrokerOrder> orders() {
        List<BrokerOrder> book = simulator.orders();
        boolean moved = false;
        for (String key : book.stream().filter(o -> o.type() == OrderType.STOP_LIMIT && !o.status().isTerminal())
                .map(BrokerOrder::instrumentKey).distinct().toList()) {
            Instant last = refreshed.get(key);
            if (last != null && Instant.now().isBefore(last.plus(STOP_REFRESH))) {
                continue;
            }
            refreshed.put(key, Instant.now());
            try {
                quote(key);
                moved = true;
            } catch (BrokerException e) {
                if (e.kind() == BrokerException.Kind.SESSION) {
                    throw e;
                }                                       // no quote now: the stop waits for the next refresh
            }
        }
        return moved ? simulator.orders() : book;
    }

    @Override
    public List<BrokerTrade> trades() {
        return simulator.trades();
    }

    @Override
    public int poll() {
        return simulator.poll();
    }

    @Override
    public void close() {
        simulator.close();
        broker.close();
    }

    /** {@code c} with every order call refused: reads pass, place / modify / cancel / updates throw. */
    public static BrokerConnection readOnly(BrokerConnection c) {
        return new BrokerConnection() {
            @Override
            public String name() {
                return c.name();
            }

            @Override
            public BrokerCapabilities capabilities() {
                return c.capabilities();
            }

            @Override
            public double availableFunds() {
                return c.availableFunds();
            }

            @Override
            public Quote quote(String instrumentKey) {
                return c.quote(instrumentKey);
            }

            @Override
            public List<BrokerOrder> orders() {
                return c.orders();
            }

            @Override
            public List<BrokerTrade> trades() {
                return c.trades();
            }

            @Override
            public List<BrokerPosition> positions() {
                return c.positions();
            }

            @Override
            public int poll() {
                throw refused("poll");
            }

            @Override
            public void onUpdate(Consumer<OrderUpdate> listener) {
                throw refused("onUpdate");
            }

            @Override
            public void place(OrderRequest request) {
                throw refused("place");
            }

            @Override
            public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
                throw refused("modify");
            }

            @Override
            public void cancel(String clientOrderId) {
                throw refused("cancel");
            }

            @Override
            public void close() {
                c.close();
            }
        };
    }

    private static IllegalStateException refused(String call) {
        return new IllegalStateException(call + " refused: this broker connection is read-only (DRY_RUN never sends an "
                + "order to the broker)");
    }
}
