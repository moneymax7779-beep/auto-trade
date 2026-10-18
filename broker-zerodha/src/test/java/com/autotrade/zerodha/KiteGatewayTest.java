package com.autotrade.zerodha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerException.Kind;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.zerodha.KiteSimulator.Fault;

/** The Kite adapter against the local simulator: login, orders, the book, and every broker fault of plan section 4. */
class KiteGatewayTest {

    private static final String KEY = "NSE_FO|47168";
    private static final String SENSEX = "BSE_FO|1125704";

    private KiteSimulator sim;
    private KiteHttp http;
    private KiteGateway gateway;
    private final List<OrderUpdate> updates = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        sim = new KiteSimulator(0, "sim-api-key", "sim-secret", 2_500);
        sim.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26O2023100PE", "NIFTY", "2026-10-20", 23100, "PE", 65, 0.05));
        sim.list(new KiteSimulator.Listing(1125704, "BFO", "SENSEX26O2274600PE", "SENSEX", "2026-10-22", 74600, "PE", 20,
                0.05));
        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        KiteConfig config = KiteConfig.simulator(sim.baseUri());
        http = new KiteHttp(config);
        KiteInstruments instruments = KiteInstruments.load(http);
        assertThat(instruments.size()).isEqualTo(2);
        http.accessToken("any");
        gateway = new KiteGateway(config, http, instruments);
        gateway.onUpdate(updates::add);
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private static OrderRequest buy(String tag, long quantity, double limit) {
        return new OrderRequest(tag, 3000047168L, KEY, "NIFTY 23100 PE 20 OCT 26", "NSE_FO", OrderSide.BUY, OrderType.LIMIT,
                quantity, limit, 0, tag);
    }

    @Test
    void loginExchangesTheRequestTokenWithTheChecksum() {
        sim.strictTokens();
        KiteConfig config = KiteConfig.simulator(sim.baseUri());
        KiteAuth auth = new KiteAuth(config, new KiteHttp(config));
        assertThat(auth.loginUrl("acct55").toString()).contains("/connect/login?v=3&api_key=sim-api-key")
                .contains("redirect_params=state%3Dacct55");
        assertThat(KiteAuth.checksum("k", "t", "s")).hasSize(64).isEqualTo(KiteAuth.checksum("k", "t", "s"));
        assertThatThrownBy(() -> auth.exchange("req-token", "wrong-secret")).isInstanceOf(BrokerException.class)
                .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(Kind.SESSION));
        KiteAuth.Session session = auth.exchange("req-token", "sim-secret");
        assertThat(session.userId()).isEqualTo("SIM001");
        assertThat(session.toString()).doesNotContain(session.accessToken());
        // the old token is refused, the new one works
        assertThatThrownBy(() -> gateway.availableFunds()).satisfies(e -> assertThat(((BrokerException) e).kind())
                .isEqualTo(Kind.SESSION));
        http.accessToken(session.accessToken());
        assertThat(gateway.availableFunds()).isEqualTo(1e9);
    }

    @Test
    void aMarketableLimitFillsAtTheAskAndPollReportsTheFillOnce() {
        gateway.place(buy("F1E1", 130, 100.20));
        assertThat(gateway.poll()).isEqualTo(1);
        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(u.filledQuantity()).isEqualTo(130);
            assertThat(u.lastFillPrice()).isEqualTo(100.0);
            assertThat(u.clientOrderId()).isEqualTo("F1E1");
        });
        assertThat(gateway.poll()).as("an unchanged book reports nothing").isZero();
        gateway.place(buy("F1E1", 130, 100.20));
        assertThat(sim.orderCount()).as("a repeated tag is not placed twice").isEqualTo(1);
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.instrumentKey()).isEqualTo(KEY);
            assertThat(p.netQuantity()).isEqualTo(130);
        });
        assertThat(gateway.trades()).singleElement().satisfies(t -> assertThat(t.price()).isEqualTo(100.0));
        assertThat(gateway.quote(KEY).ask()).isEqualTo(100.0);
    }

    @Test
    void aPlaceWhoseOutcomeIsUnknownIsLookedUpByTagNeverPlacedTwice() {
        sim.failNext("place", Fault.TIMEOUT_PROCESSED);             // processed, the response never came
        gateway.place(buy("F2E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(1);
        assertThat(gateway.orderByTag("F2E1")).isPresent();

        sim.failNext("place", Fault.HTTP_500_PROCESSED);            // processed, then a 500
        gateway.place(buy("F3E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(2);

        sim.failNext("place", Fault.TIMEOUT);                       // never reached: not found, placed once more
        gateway.place(buy("F4E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(3);

        sim.failNext("place", Fault.HTTP_429);                      // dropped before processing: retried
        gateway.place(buy("F5E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(4);
        assertThat(gateway.orders()).extracting(BrokerOrder::clientTag).containsExactly("F2E1", "F3E1", "F4E1", "F5E1");
    }

    @Test
    void errorsAreClassified() {
        sim.failNext("place", Fault.TOKEN_EXPIRED);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2)))
                .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(Kind.SESSION));
        sim.failNext("place", Fault.INPUT_ERROR);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2)))
                .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(Kind.TERMINAL))
                .hasMessageContaining("circuit");
        assertThatThrownBy(() -> gateway.place(buy("F6-E1", 65, 100.2))).hasMessageContaining("alphanumerics");
        assertThatThrownBy(() -> gateway.place(new OrderRequest("F7E1", 1, "NSE_FO|1", "X", "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, 65, 10, 0, "F7E1"))).hasMessageContaining("not in Kite's instrument list");
        sim.failNext("place", Fault.REJECT_FUNDS);
        gateway.place(buy("F8E1", 65, 100.2));
        gateway.poll();
        assertThat(updates).last().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(u.message()).startsWith("Insufficient funds");
        });
        sim.expireToken();
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(((BrokerException) e).kind())
                .isEqualTo(Kind.SESSION));
    }

    @Test
    void aPartialFillRestsUntilCancelled() {
        sim.partialFills(0.5);
        gateway.place(buy("F9E1", 260, 100.2));
        gateway.poll();
        assertThat(updates).last().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
            assertThat(u.filledQuantity()).isEqualTo(130);
        });
        gateway.cancel("F9E1");
        gateway.poll();
        assertThat(updates).last().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(u.filledQuantity()).isEqualTo(130);
            assertThat(u.lastFillQuantity()).isZero();
        });
    }

    @Test
    void aRestingStopTriggersOnTheLastPriceAndAStopThatFillsAsItsCancelArrivesIsReportedFilled() {
        gateway.place(buy("F10E1", 65, 100.2));
        gateway.place(new OrderRequest("F10S1", 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.STOP_LIMIT, 65,
                71.0, 75.0, "F10S1"));
        gateway.poll();
        assertThat(gateway.orderByTag("F10S1").orElseThrow().status()).isEqualTo(OrderStatus.TRIGGER_PENDING);
        sim.quote(KEY, 74.0, 6500, 74.5, 6500, 74.9);                // last through the trigger: works as a limit at 71
        assertThat(gateway.orderByTag("F10S1").orElseThrow()).satisfies(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(o.averagePrice()).isEqualTo(74.0);
        });

        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        gateway.place(buy("F11E1", 65, 100.2));
        gateway.place(new OrderRequest("F11S1", 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.STOP_LIMIT, 65,
                71.0, 75.0, "F11S1"));
        sim.fillStopOnCancel(true);
        assertThatThrownBy(() -> gateway.cancel("F11S1")).hasMessageContaining("cannot be cancelled");
        assertThat(gateway.orderByTag("F11S1").orElseThrow().status()).as("the exit must not be sent: the stop sold")
                .isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void aStaleOrderBookDoesNotUndoAFill() {
        gateway.place(buy("F12E1", 65, 100.2));
        gateway.poll();
        gateway.place(buy("F13E1", 65, 100.2));
        gateway.poll();
        int before = updates.size();
        sim.staleOrderBookOnce();
        gateway.poll();
        gateway.poll();
        assertThat(updates).hasSize(before);
        assertThat(updates).filteredOn(u -> u.clientOrderId().equals("F13E1")).singleElement()
                .satisfies(u -> assertThat(u.status()).isEqualTo(OrderStatus.FILLED));
    }

    @Test
    void theRateLimiterKeepsOrdersBelowTenASecond() {
        long start = System.nanoTime();
        for (int i = 0; i < 12; i++) {
            gateway.place(buy("R" + i, 65, 100.2));
        }
        assertThat((System.nanoTime() - start) / 1e9).as("12 orders at 8 a second").isGreaterThanOrEqualTo(0.9);
        assertThat(sim.orderCount()).isEqualTo(12);
    }

    @Test
    void pricesGoOnTheTickAndModifyChangesTheRestingOrder() {
        assertThat(KiteGateway.price(100.01, 0.05, OrderSide.BUY)).isEqualTo("100.05");
        assertThat(KiteGateway.price(100.04, 0.05, OrderSide.SELL)).isEqualTo("100.00");
        assertThat(KiteGateway.price(75.02, 0.05, null)).isEqualTo("75.00");
        gateway.place(buy("F14E1", 65, 90));                         // below the ask: rests
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.OPEN);
        gateway.modify("F14E1", 0, 100.5, 0);
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(KiteGateway.status("MODIFY VALIDATION PENDING", 0)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void instrumentsMapOurKeysToKiteContracts() throws Exception {
        KiteInstruments list = KiteInstruments.parse(new StringReader(sim.instrumentsCsv()));
        assertThat(list.byInstrumentKey(SENSEX)).hasValueSatisfying(c -> {
            assertThat(c.exchange()).isEqualTo("BFO");
            assertThat(c.tradingSymbol()).isEqualTo("SENSEX26O2274600PE");
            assertThat(c.lotSize()).isEqualTo(20);
        });
        assertThat(list.byInstrumentKey("NSE_EQ|2885")).isEmpty();
        assertThat(KiteInstruments.instrumentKey("BFO", 1125704)).isEqualTo(SENSEX);
        assertThat(new KiteGateway(KiteConfig.simulator(sim.baseUri()), http, list).capabilities().tagMaxLength())
                .isEqualTo(20);
        assertThat(KiteConfig.simulator(sim.baseUri()).isRealBroker()).isFalse();
        assertThat(new KiteConfig("k", KiteConfig.REAL_API, KiteConfig.REAL_LOGIN, "MIS", null, 8, null).isRealBroker())
                .isTrue();
    }
}
