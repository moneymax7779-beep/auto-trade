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
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.OptionTick;

class PaperBrokerTest {

    private static final Instant T0 = Instant.parse("2026-09-28T05:00:00Z");

    private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
    private final PaperBroker broker = new PaperBroker("paper", Duration.ofMillis(250), 0, clock::get);
    private final List<OrderUpdate> updates = new ArrayList<>();

    PaperBrokerTest() {
        broker.onUpdate(updates::add);
    }

    @Test
    void limitFillsOnlyAtTheLimitOrBetterAndPartiallyOnThinDepth() {
        broker.place(limit("b1", OrderSide.BUY, 130, 100.0));
        broker.accept(tick(300, 99.5, 100.5, 100, 65)); // ask above the limit
        assertThat(last().status()).isEqualTo(OrderStatus.OPEN);
        broker.accept(tick(600, 99.5, 100.0, 100, 65)); // only 65 at the limit
        assertThat(last().status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(last().filledQuantity()).isEqualTo(65);
        broker.accept(tick(900, 99.5, 99.9, 99.9, 1000));
        assertThat(last().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(last().averagePrice()).isEqualTo((65 * 100.0 + 65 * 99.9) / 130);
        assertThat(broker.positions().getFirst().netQuantity()).isEqualTo(130);
    }

    @Test
    void cancelIsAcknowledgedOnAnyEventAfterLatency() {
        broker.place(limit("b1", OrderSide.BUY, 65, 90.0));
        broker.accept(tick(300, 99.5, 100.5, 100, 65));
        broker.cancel("b1");
        broker.accept(new IndexTick(T0.plusMillis(600), null, "NIFTY", 1, 23100, null, null)); // no option quote needed
        assertThat(last().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void stopLimitTriggersOnLastPriceThenFillsAtBid() {
        broker.place(new OrderRequest("s1", 7, "k", "NIFTY 23100 CE", "NSE", OrderSide.SELL, OrderType.STOP_LIMIT, 65,
                71.0, 75.0, "t"));
        broker.accept(tick(300, 80, 80.5, 80.2, 1000));
        assertThat(last().status()).isEqualTo(OrderStatus.TRIGGER_PENDING);
        broker.accept(tick(600, 74.5, 75.0, 74.8, 1000));
        assertThat(last().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(last().lastFillPrice()).isEqualTo(74.5);
    }

    @Test
    void duplicateClientOrderIdsAreIgnored() {
        broker.place(limit("b1", OrderSide.BUY, 65, 100.0));
        broker.place(limit("b1", OrderSide.BUY, 65, 100.0));
        broker.accept(tick(300, 99.5, 100, 100, 1000));
        assertThat(broker.positions().getFirst().netQuantity()).isEqualTo(65);
    }

    @Test
    void ordersOnOneTickShareTheBidDepthAndTheRestFillsOnTheNextTick() {
        broker.place(limit("s1", OrderSide.SELL, 400, 99.0)); // e.g. two freeze-quantity slices of one exit
        broker.place(limit("s2", OrderSide.SELL, 400, 99.0));
        broker.accept(book(300, new double[] {100.0, 99.5}, new long[] {300, 200})); // 500 bid, 800 asked
        assertThat(status("s1")).isEqualTo(OrderStatus.FILLED);
        assertThat(lastOf("s1").averagePrice()).isEqualTo((300 * 100.0 + 100 * 99.5) / 400);
        assertThat(status("s2")).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(lastOf("s2").filledQuantity()).isEqualTo(100);
        assertThat(lastOf("s2").lastFillPrice()).isEqualTo(99.5);
        assertThat(broker.positions().getFirst().netQuantity()).isEqualTo(-500);

        broker.accept(book(600, new double[] {100.0, 99.5}, new long[] {300, 200})); // a fresh book
        assertThat(status("s2")).isEqualTo(OrderStatus.FILLED);
        assertThat(lastOf("s2").lastFillQuantity()).isEqualTo(300);
        assertThat(lastOf("s2").lastFillPrice()).isEqualTo(100.0);
        assertThat(broker.positions().getFirst().netQuantity()).isEqualTo(-800);
    }

    @Test
    void aLaterOrderDoesNotGoPastItsLimitForWhatAnEarlierOrderLeft() {
        broker.place(limit("b1", OrderSide.BUY, 300, 101.0)); // entry
        broker.place(limit("b2", OrderSide.BUY, 300, 100.0)); // add, tighter limit
        broker.accept(book(300, new double[] {100.0, 100.5}, new long[] {400, 1000}, true));
        assertThat(status("b1")).isEqualTo(OrderStatus.FILLED);
        assertThat(lastOf("b1").averagePrice()).isEqualTo(100.0);
        assertThat(status("b2")).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(lastOf("b2").filledQuantity()).isEqualTo(100); // 100.5 is above its limit
        assertThat(broker.positions().getFirst().netQuantity()).isEqualTo(400);
    }

    @Test
    void buysAndSellsOnOneTickTakeFromDifferentSides() {
        broker.place(new OrderRequest("b1", 7, "k", "NIFTY 23100 CE", "NSE", OrderSide.BUY, OrderType.LIMIT, 65,
                100.5, 0, "t"));
        broker.place(limit("s1", OrderSide.SELL, 65, 99.5));
        broker.accept(tick(300, 99.5, 100.5, 100, 65));
        assertThat(status("b1")).isEqualTo(OrderStatus.FILLED);
        assertThat(status("s1")).isEqualTo(OrderStatus.FILLED);
    }

    private OrderStatus status(String clientOrderId) {
        return lastOf(clientOrderId).status();
    }

    private OrderUpdate lastOf(String clientOrderId) {
        return updates.reversed().stream().filter(u -> u.clientOrderId().equals(clientOrderId)).findFirst()
                .orElseThrow();
    }

    private OrderUpdate last() {
        return updates.getLast();
    }

    private static OrderRequest limit(String id, OrderSide side, long quantity, double price) {
        return new OrderRequest(id, 7, "k", "NIFTY 23100 CE", "NSE", side, OrderType.LIMIT, quantity, price, 0, "t");
    }

    private OptionTick tick(long millis, double bid, double ask, double ltp, long askQuantity) {
        return tick(millis, ltp, DepthLevels.of(new double[] {bid}, new long[] {1000}, new int[] {1}),
                DepthLevels.of(new double[] {ask}, new long[] {askQuantity}, new int[] {1}));
    }

    /** A tick whose bid side has these levels (and a deep ask above them). */
    private OptionTick book(long millis, double[] prices, long[] quantities) {
        return book(millis, prices, quantities, false);
    }

    /** A tick with these levels on the ask side ({@code asks}) or the bid side, the other side one deep level. */
    private OptionTick book(long millis, double[] prices, long[] quantities, boolean asks) {
        DepthLevels levels = DepthLevels.of(prices, quantities, new int[prices.length]);
        return asks ? tick(millis, prices[0], DepthLevels.of(new double[] {prices[0] - 1}, new long[] {1000},
                new int[] {1}), levels)
                : tick(millis, prices[0], levels, DepthLevels.of(new double[] {prices[0] + 1}, new long[] {1000},
                        new int[] {1}));
    }

    private OptionTick tick(long millis, double ltp, DepthLevels bids, DepthLevels asks) {
        Instant at = T0.plusMillis(millis);
        clock.set(at);
        return new OptionTick(at, at, "NIFTY", 1, 7, "NIFTY 23100 CE", LocalDate.of(2026, 9, 29), 23100,
                OptionTick.OptionType.CE, 65, ltp, 0L, 0.0, 0.0, 0.0, bids, asks,
                0.1, 0.5, 0.001, -10.0, 12.0, 1.0, at, true, true);
    }
}
