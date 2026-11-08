package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;

/** LiveGuard against an order book that also holds the owner's own hand-placed orders (code review 3 Nov 2026). */
class LiveGuardTest {

    private static final String CE = "NSE_FO|47168", PE = "NSE_FO|47169";
    private static final int LOT = 75;

    private final List<BrokerOrder> book = new ArrayList<>();
    private final List<OrderRequest> sent = new ArrayList<>();
    private BrokerException nextRead;                          // the next order-book read fails with this
    private final LiveGuard guard = new LiveGuard(new Book(), new FollowerRisk.LiveLimits(1, 2, Set.of("ma-cross")),
            key -> LOT);

    @Test
    void aHandPlacedSellWithoutATagIsCountedNotACrash() {
        book.add(order("F1", CE, OrderSide.BUY, OrderStatus.FILLED, LOT, LOT));
        book.add(order(null, CE, OrderSide.SELL, OrderStatus.OPEN, LOT, 0));          // the owner's own sell, no tag
        assertThatThrownBy(() -> guard.place(sell("F1S1", CE, LOT)))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.TERMINAL))
                .hasMessageContaining("never short");
        assertThat(sent).isEmpty();
    }

    @Test
    void sharesBoughtByHandAreNotContractsHeld() {
        book.add(order(null, "NSE|2885", OrderSide.BUY, OrderStatus.FILLED, 10, 10));
        book.add(order(null, "NSE|1594", OrderSide.BUY, OrderStatus.FILLED, 5, 5));
        guard.place(buy("F2", CE, LOT));
        assertThat(sent).hasSize(1);
    }

    @Test
    void aThirdContractIsRefused() {
        book.add(order("F1", CE, OrderSide.BUY, OrderStatus.FILLED, LOT, LOT));
        book.add(order("F2", PE, OrderSide.BUY, OrderStatus.OPEN, LOT, 0));
        assertThatThrownBy(() -> guard.place(buy("F3", "NSE_FO|47170", LOT))).hasMessageContaining("max 2 contracts");
        assertThatThrownBy(() -> guard.place(buy("F4", CE, 2 * LOT))).hasMessageContaining("over 1 lot");
        assertThat(sent).isEmpty();
    }

    @Test
    void aTagAlreadyAtTheBrokerIsNeverSentAgain() {
        book.add(order("F1", CE, OrderSide.BUY, OrderStatus.FILLED, LOT, LOT));
        book.add(order("F1X", CE, OrderSide.SELL, OrderStatus.OPEN, LOT, 0));   // an exit that timed out, then showed up
        guard.place(sell("F1X", CE, LOT));                                        // the retry with the same tag
        guard.place(buy("F1", CE, LOT));
        assertThat(sent).isEmpty();
    }

    @Test
    void aBookReadThatFailsBeforeAPlaceIsRetryableNotSentNeverUnknown() {
        // review 8 Nov 2026: a read timeout here was UNKNOWN, so the venue recorded ORDER_UNKNOWN for an order never sent
        book.add(order("F1", CE, OrderSide.BUY, OrderStatus.FILLED, LOT, LOT));
        nextRead = new BrokerException(BrokerException.Kind.UNKNOWN, "GET /orders timed out");
        assertThatThrownBy(() -> guard.place(sell("F1X1", CE, LOT)))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.RETRYABLE))
                .hasMessageContaining("not sent");
        nextRead = new BrokerException(BrokerException.Kind.RETRYABLE, "HTTP 500");
        assertThatThrownBy(() -> guard.modify("F1S1", LOT, 70, 75))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.RETRYABLE));
        nextRead = new BrokerException(BrokerException.Kind.SESSION, "token expired");
        assertThatThrownBy(() -> guard.place(sell("F1X1", CE, LOT)))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.SESSION));
        assertThat(sent).isEmpty();
        guard.place(sell("F1X1", CE, LOT));                                       // the book is back: sent
        assertThat(sent).hasSize(1);
    }

    @Test
    void aGuardRefusalIsToldApartFromTheBrokers() {
        assertThatThrownBy(() -> guard.place(sell("F9S1", CE, LOT))).isInstanceOf(LiveGuard.Refusal.class);
    }

    private static BrokerOrder order(String tag, String key, OrderSide side, OrderStatus status, long quantity, long filled) {
        return new BrokerOrder(tag, "B" + key + side + quantity, key, "SYM", side, OrderType.LIMIT, quantity, filled, 100,
                100, 0, status, status.name(), null, Instant.now());
    }

    private static OrderRequest buy(String tag, String key, long quantity) {
        return new OrderRequest(tag, 1, key, "SYM", "NFO", OrderSide.BUY, OrderType.LIMIT, quantity, 100, 0, tag);
    }

    private static OrderRequest sell(String tag, String key, long quantity) {
        return new OrderRequest(tag, 1, key, "SYM", "NFO", OrderSide.SELL, OrderType.STOP_LIMIT, quantity, 70, 75, tag);
    }

    private final class Book implements BrokerConnection {

        @Override
        public List<BrokerOrder> orders() {
            BrokerException e = nextRead;
            nextRead = null;
            if (e != null) {
                throw e;
            }
            return List.copyOf(book);
        }

        @Override
        public void place(OrderRequest request) {
            sent.add(request);
        }

        @Override
        public String name() {
            return "TEST";
        }

        @Override
        public BrokerCapabilities capabilities() {
            return null;
        }

        @Override
        public void onUpdate(Consumer<OrderUpdate> listener) {
        }

        @Override
        public void modify(String clientOrderId, long quantity, double limitPrice, double triggerPrice) {
        }

        @Override
        public void cancel(String clientOrderId) {
        }

        @Override
        public List<BrokerPosition> positions() {
            return List.of();
        }

        @Override
        public double availableFunds() {
            return 0;
        }

        @Override
        public Quote quote(String instrumentKey) {
            return null;
        }

        @Override
        public List<BrokerTrade> trades() {
            return List.of();
        }

        @Override
        public int poll() {
            return 0;
        }
    }
}
