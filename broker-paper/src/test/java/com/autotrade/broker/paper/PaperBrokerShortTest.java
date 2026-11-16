package com.autotrade.broker.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.OptionTick;

/** A-358: a holding sold to open goes negative, a buy brings it back to zero, and settlement fills the buy of a short. */
class PaperBrokerShortTest {

    private static final Instant T0 = Instant.parse("2026-10-27T06:30:00Z");

    private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
    private final PaperBroker broker = new PaperBroker("paper", Duration.ofMillis(250), 0, clock::get);
    private final List<OrderUpdate> updates = new ArrayList<>();

    PaperBrokerShortTest() {
        broker.onUpdate(updates::add);
    }

    @Test
    void aSaleToOpenThenABuyToCloseRoundTripsToZero() {
        broker.place(limit("s1", OrderSide.SELL, 65, 44.60));
        broker.accept(tick(300, 44.80, 45.00, 44.90, 1000));
        assertThat(last().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(last().lastFillPrice()).isEqualTo(44.80);
        assertThat(broker.positions().getFirst().netQuantity()).isEqualTo(-65);
        assertThat(broker.positions().getFirst().averagePrice()).as("the average sale price of the short").isEqualTo(44.80);
        broker.place(limit("b1", OrderSide.BUY, 65, 46.00));
        broker.accept(tick(600, 45.50, 45.80, 45.60, 1000));
        assertThat(last().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(last().lastFillPrice()).isEqualTo(45.80);
        assertThat(broker.positions().getFirst().netQuantity()).isZero();
    }

    @Test
    void settlementFillsAShortHoldingsWorkingBuyAtTheSettlementPriceAndCancelsTheRest() {
        broker.place(limit("s1", OrderSide.SELL, 65, 44.60));
        broker.accept(tick(300, 44.80, 45.00, 44.90, 1000));
        broker.place(limit("b1", OrderSide.BUY, 65, 1.00));            // would never fill against the 45.00 ask
        broker.place(limit("b2", OrderSide.BUY, 65, 1.00));            // more than is owed: cancelled
        broker.accept(tick(600, 44.80, 45.00, 44.90, 1000));
        broker.settle(7, 50.25);
        OrderUpdate b1 = updates.stream().filter(u -> u.clientOrderId().equals("b1")).reduce((a, b) -> b).orElseThrow();
        OrderUpdate b2 = updates.stream().filter(u -> u.clientOrderId().equals("b2")).reduce((a, b) -> b).orElseThrow();
        assertThat(b1.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(b1.lastFillPrice()).isEqualTo(50.25);
        assertThat(b2.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(broker.positions().getFirst().netQuantity()).isZero();
    }

    @Test
    void settlementOfALongHoldingStillFillsOnlyItsSells() {
        broker.place(limit("b1", OrderSide.BUY, 65, 45.20));
        broker.accept(tick(300, 44.80, 45.00, 44.90, 1000));
        broker.place(limit("s1", OrderSide.SELL, 65, 99.00));
        broker.place(limit("b2", OrderSide.BUY, 65, 1.00));
        broker.accept(tick(600, 44.80, 45.00, 44.90, 1000));
        broker.settle(7, 50.25);
        OrderUpdate s1 = updates.stream().filter(u -> u.clientOrderId().equals("s1")).reduce((a, b) -> b).orElseThrow();
        OrderUpdate b2 = updates.stream().filter(u -> u.clientOrderId().equals("b2")).reduce((a, b) -> b).orElseThrow();
        assertThat(s1.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(b2.status()).as("a long holding's buy is cancelled, not filled").isEqualTo(OrderStatus.CANCELLED);
        assertThat(broker.positions().getFirst().netQuantity()).isZero();
    }

    private OrderUpdate last() {
        return updates.getLast();
    }

    private static OrderRequest limit(String id, OrderSide side, long quantity, double price) {
        return new OrderRequest(id, 7, "NSE_FO|7", "NIFTY 22800 CE", "NSE", side, OrderType.LIMIT, quantity, price, 0, "t");
    }

    private OptionTick tick(long ms, double bid, double ask, double last, long depth) {
        Instant at = T0.plusMillis(ms);
        clock.set(at);
        return new OptionTick(at, at, "NIFTY", 1, 7, "NIFTY 22800 CE", LocalDate.of(2026, 10, 27), 22800,
                OptionTick.OptionType.CE, 65, last, 0L, 0.0, 0.0, 0.0,
                DepthLevels.of(new double[] {bid}, new long[] {depth}, new int[] {1}),
                DepthLevels.of(new double[] {ask}, new long[] {depth}, new int[] {1}),
                0.1, 0.5, 0.001, -10.0, 12.0, 1.0, at, true, true);
    }
}
