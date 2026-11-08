package com.autotrade.upstox.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import com.autotrade.broker.Quote;
import com.autotrade.upstox.orders.UpstoxHttp.Call;
import com.autotrade.upstox.orders.UpstoxSimulator.Fault;

/**
 * The Upstox adapter against the local Upstox simulator: the instrument file, orders with our tag, the books, the
 * session, and every broker fault of the Kite suite plus Upstox's own (static IP not registered, invalid token, the
 * funds service's night window).
 */
class UpstoxGatewayTest {

    private static final String KEY = "NSE_FO|47168";
    private static final String SENSEX = "BSE_FO|1125704";
    private static final String NIFTY_SYMBOL = "NIFTY 23100 PE 11 NOV 26";
    private static final LocalDate NIFTY_EXPIRY = LocalDate.of(2026, 11, 11);
    private static final LocalDate SENSEX_EXPIRY = LocalDate.of(2026, 11, 13);
    /** The session the fixtures' contracts are listed for (both expire after it). */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    private UpstoxSimulator sim;
    private UpstoxHttp http;
    private UpstoxGateway gateway;
    private UpstoxInstruments instruments;
    private final List<OrderUpdate> updates = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        sim = new UpstoxSimulator(0, 2_500);
        sim.list(new UpstoxSimulator.Listing(KEY, NIFTY_SYMBOL, "NIFTY", NIFTY_EXPIRY, 23100, "PE", 65, 0.05));
        sim.list(new UpstoxSimulator.Listing(SENSEX, "SENSEX 74600 PE 13 NOV 26", "SENSEX", SENSEX_EXPIRY, 74600, "PE", 20,
                0.05));
        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        UpstoxConfig config = UpstoxConfig.simulator(sim.baseUri());
        http = new UpstoxHttp(config);
        instruments = UpstoxInstruments.load(http, DAY);
        assertThat(instruments.size()).isEqualTo(2);
        http.session("any-token");
        gateway = new UpstoxGateway(config, http, instruments, sim::book);
        gateway.onUpdate(updates::add);
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private static OrderRequest buy(String tag, long quantity, double limit) {
        return new OrderRequest(tag, 3000047168L, KEY, NIFTY_SYMBOL, "NSE_FO", OrderSide.BUY, OrderType.LIMIT, quantity,
                limit, 0, tag);
    }

    private static OrderRequest stop(String tag, long quantity, double limit, double trigger) {
        return new OrderRequest(tag, 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.STOP_LIMIT, quantity, limit,
                trigger, tag);
    }

    private static Kind kind(Throwable e) {
        return ((BrokerException) e).kind();
    }

    @Test
    void onlyTheDaysTokenIsAcceptedAndItIsNeverInAMessage() {
        sim.strictSessions();
        assertThatThrownBy(() -> gateway.availableFunds()).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.SESSION);
            assertThat(e.getMessage()).doesNotContain("any-token").contains("UDAPI100050");
        });
        UpstoxSimulator.Session s = sim.login();
        http.session(s.accessToken());
        assertThat(gateway.availableFunds()).isEqualTo(1e9);
        assertThat(s.toString()).doesNotContain(s.accessToken());
        sim.expireSession();
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.SESSION);
            assertThat(e.getMessage()).doesNotContain(s.accessToken());
        });
        UpstoxSimulator.Session next = sim.login();
        http.session(next.accessToken());
        assertThat(gateway.orders()).as("an empty book is no orders").isEmpty();
        assertThat(sim.tokensSentToAssets()).as("the instrument files are public: no token goes there").isZero();
    }

    @Test
    void aMarketableLimitFillsAtTheAskAndPollReportsTheFillOnce() {
        gateway.place(buy("F1E1", 130, 100.20));
        assertThat(gateway.poll()).isEqualTo(1);
        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(u.filledQuantity()).isEqualTo(130);
            assertThat(u.lastFillPrice()).isEqualTo(100.0);
            assertThat(u.clientOrderId()).as("our tag comes back in the book's tag").isEqualTo("F1E1");
        });
        assertThat(gateway.poll()).as("an unchanged book reports nothing").isZero();
        gateway.place(buy("F1E1", 130, 100.20));
        assertThat(sim.orderCount()).as("a repeated tag is not placed twice").isEqualTo(1);
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.instrumentKey()).isEqualTo(KEY);
            assertThat(p.instrumentToken()).isEqualTo(47168);
            assertThat(p.netQuantity()).isEqualTo(130);
            assertThat(p.averagePrice()).isEqualTo(100.0);
            assertThat(p.dayBuyValue()).isEqualTo(13_000.0);
        });
        assertThat(gateway.trades()).singleElement().satisfies(t -> {
            assertThat(t.price()).isEqualTo(100.0);
            assertThat(t.instrumentKey()).as("Upstox's trade book carries the instrument key").isEqualTo(KEY);
            assertThat(t.side()).isEqualTo(OrderSide.BUY);
            assertThat(t.time()).isNotNull();
        });
        assertThat(gateway.orderByTag("F1E1").orElseThrow()).satisfies(o -> {
            assertThat(o.symbol()).isEqualTo(NIFTY_SYMBOL);
            assertThat(o.rawStatus()).isEqualTo("complete");
            assertThat(o.updatedAt()).isNotNull();
        });
        assertThat(gateway.quote(KEY).ask()).isEqualTo(100.0);
        assertThat(gateway.availableFunds()).isEqualTo(1e9 - 13_000);
    }

    @Test
    void theQuoteComesFromTheSuppliedSourceAndWithoutOneNoOrderIsPriced() {
        sim.depth(KEY, List.of(new Quote.Level(99.5, 650), new Quote.Level(99.0, 1300)),
                List.of(new Quote.Level(100.0, 650), new Quote.Level(100.5, 1300), new Quote.Level(101.0, 6500)), 99.8);
        Quote q = gateway.quote(KEY);
        assertThat(q.asks()).containsExactly(new Quote.Level(100.0, 650), new Quote.Level(100.5, 1300),
                new Quote.Level(101.0, 6500));
        gateway.place(buy("D1E1", 2600, 100.5));                     // 650 at 100.00 + 1,300 at 100.50; 650 rest open
        BrokerOrder first = gateway.orderByTag("D1E1").orElseThrow();
        assertThat(first.filledQuantity()).isEqualTo(1950);
        assertThat(first.averagePrice()).isCloseTo((650 * 100.0 + 1300 * 100.5) / 1950, within(0.01));
        assertThat(first.status()).as("a partial fill: filled_quantity on 'open'").isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(first.rawStatus()).isEqualTo("open");
        assertThat(gateway.quote(KEY).asks()).containsExactly(new Quote.Level(101.0, 6500));

        UpstoxGateway noQuotes = new UpstoxGateway(UpstoxConfig.simulator(sim.baseUri()), http, instruments, null);
        assertThatThrownBy(() -> noQuotes.quote(KEY)).isInstanceOf(BrokerException.class)
                .hasMessageContaining("not implemented").satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL));
        assertThatThrownBy(() -> gateway.quote(SENSEX)).as("no book for it").isInstanceOf(BrokerException.class);
    }

    @Test
    void aPlaceWhoseOutcomeIsUnknownIsLookedUpByTagNeverPlacedTwice() {
        sim.failNext("place", Fault.TIMEOUT_PROCESSED);               // processed, the response never came
        gateway.place(buy("F2E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(1);
        assertThat(gateway.orderByTag("F2E1")).isPresent();

        sim.failNext("place", Fault.HTTP_500_PROCESSED);              // processed, then a 500
        gateway.place(buy("F3E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(2);

        sim.failNext("place", Fault.TIMEOUT);                         // never reached: not found, reported, not re-sent
        assertThatThrownBy(() -> gateway.place(buy("F4E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.UNKNOWN));
        assertThat(sim.orderCount()).isEqualTo(2);

        sim.failNext("place", Fault.HTTP_500);                        // a 500 not processed: unknown, looked up, not re-sent
        assertThatThrownBy(() -> gateway.place(buy("F4E2", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.UNKNOWN));
        assertThat(sim.orderCount()).isEqualTo(2);

        sim.failNext("place", Fault.HTTP_429);                        // dropped before processing: retried
        gateway.place(buy("F5E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(3);
        assertThat(gateway.orders()).extracting(BrokerOrder::clientTag).containsExactly("F2E1", "F3E1", "F5E1");

        // a restarted worker (a new gateway) finds the order by tag (the venue reads the book before a second place)
        UpstoxGateway restarted = new UpstoxGateway(UpstoxConfig.simulator(sim.baseUri()), http, instruments, sim::book);
        assertThat(restarted.orderByTag("F5E1")).isPresent();
        assertThatThrownBy(() -> restarted.cancel("F5E1")).as("it knows the order; filled, so Upstox refuses")
                .hasMessageContaining("cannot be cancelled").hasMessageContaining(UpstoxHttp.ORDER_FINISHED_CANCEL);
    }

    @Test
    void anOrderNotYetInTheBookIsFoundInItsHistoryByTag() {
        sim.staleOrderBookOnce();                                     // the next book is the one before this order
        gateway.place(buy("H1E1", 65, 100.2));
        assertThat(gateway.orderByTag("H1E1")).as("from history: its newest state").hasValueSatisfying(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(o.filledQuantity()).isEqualTo(65);
        });
        assertThat(sim.requests("history")).isEqualTo(1);
        assertThat(gateway.orderByTag("NOSUCH1")).as("history: UDAPI100010 is no order").isEmpty();
        assertThat(sim.requests("history")).isEqualTo(2);

        sim.ackLag(1);                                                // not acknowledged yet: pending, nothing filled
        gateway.place(buy("H2E1", 65, 100.2));
        BrokerOrder pending = gateway.orderByTag("H2E1").orElseThrow();
        assertThat(pending.rawStatus()).isEqualTo("put order req received");
        assertThat(pending.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(gateway.orderByTag("H2E1").orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void errorsAreClassified() {
        sim.failNext("place", Fault.TOKEN_EXPIRED);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION));
        sim.failNext("place", Fault.TOKEN_INVALID);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION)).hasMessageContaining("UDAPI100050");
        sim.failNext("place", Fault.STATIC_IP);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION)).hasMessageContaining("UDAPI1154").hasMessageContaining("static IP")
                .hasMessageContaining("log in again");
        assertThat(sim.orderCount()).as("nothing placed by a refused call").isZero();
        sim.failNext("funds", Fault.FUNDS_DOWN);
        assertThatThrownBy(() -> gateway.availableFunds()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE))
                .hasMessageContaining("00:00-05:30");
        sim.failNext("place", Fault.INPUT_ERROR);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.TERMINAL)).hasMessageContaining("circuit");
        assertThatThrownBy(() -> gateway.place(buy("F6-E1", 65, 100.2))).hasMessageContaining("alphanumerics");
        assertThatThrownBy(() -> gateway.place(new OrderRequest("F7E1", 1, "NSE_FO|1", "X", "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, 65, 10, 0, "F7E1"))).hasMessageContaining("not in Upstox's checked instrument list");
        assertThatThrownBy(() -> gateway.place(buy("F7E2", 64, 100.2))).as("not a whole lot: refused before sending")
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL)).hasMessageContaining("whole number of lots");
        assertThat(sim.requests("place")).as("the lot check sends nothing").isEqualTo(4);
        sim.failNext("place", Fault.REJECT_FUNDS);
        gateway.place(buy("F8E1", 65, 100.2));
        gateway.poll();
        assertThat(updates).last().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(u.message()).startsWith("Insufficient funds");
        });
        sim.failNext("orders", Fault.HTTP_500);                       // a read: retryable, never unknown
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        sim.failNext("orders", Fault.STATIC_IP);                      // reads from another IP: still the account's IP
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
        sim.expireSession();
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
    }

    @Test
    void theErrorTableMapsUpstoxCodes() {
        assertThat(UpstoxHttp.kind(401, "UDAPI100050", Call.READ)).isEqualTo(Kind.SESSION);
        assertThat(UpstoxHttp.kind(403, "UDAPI1154", Call.ORDER)).isEqualTo(Kind.SESSION);
        assertThat(UpstoxHttp.kind(401, "", Call.READ)).isEqualTo(Kind.SESSION);
        assertThat(UpstoxHttp.kind(403, "", Call.ORDER)).isEqualTo(Kind.SESSION);
        assertThat(UpstoxHttp.kind(429, "UDAPI10005", Call.ORDER)).isEqualTo(Kind.RETRYABLE);
        assertThat(UpstoxHttp.kind(423, "UDAPI100072", Call.READ)).isEqualTo(Kind.RETRYABLE);
        assertThat(UpstoxHttp.kind(423, "", Call.READ)).isEqualTo(Kind.RETRYABLE);
        for (String terminal : List.of("UDAPI1158", "UDAPI1159", "UDAPI1160", "UDAPI100011", "UDAPI100049", "UDAPI100040",
                "UDAPI100041", "UDAPI100010", "UDAPI100074", "UDAPI1023")) {
            assertThat(UpstoxHttp.kind(400, terminal, Call.ORDER)).as(terminal).isEqualTo(Kind.TERMINAL);
        }
        assertThat(UpstoxHttp.kind(500, "UDAPI100500", Call.ORDER)).isEqualTo(Kind.UNKNOWN);
        assertThat(UpstoxHttp.kind(503, "", Call.READ)).isEqualTo(Kind.RETRYABLE);
        assertThat(UpstoxHttp.kind(400, "", Call.ORDER)).isEqualTo(Kind.TERMINAL);
        assertThat(UpstoxHttp.kind(400, null, Call.ORDER)).isEqualTo(Kind.TERMINAL);
    }

    @Test
    void anAnswerThatIsNotUpstoxsEnvelopeIsUnknownForAnOrderAndRetryableForARead() throws Exception {
        com.sun.net.httpserver.HttpServer proxy = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", ex -> {
            byte[] page = "<html>gateway</html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, page.length);
            try (var out = ex.getResponseBody()) {
                out.write(page);
            }
        });
        proxy.start();
        try {
            UpstoxHttp h = new UpstoxHttp(UpstoxConfig.simulator(URI.create("http://127.0.0.1:"
                    + proxy.getAddress().getPort())));
            h.session("t");
            assertThatThrownBy(() -> h.send("POST", UpstoxHttp.PLACE, h.object().put("quantity", 65)))
                    .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.UNKNOWN));
            assertThatThrownBy(() -> h.get(UpstoxHttp.ORDERS)).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void theOrderBodyHasUpstoxsFieldsAndTypesAndCallsWithoutTheTokenAreRefused() {
        // the simulator refuses a missing field, a string quantity or price, MARKET / SL-M, AMO, slicing, a price off
        // the tick and a tag over 40: a place it accepts carries Upstox's v3 body exactly
        gateway.place(buy("B1E1", 65, 100.21));                      // rounded up to 100.25 on the 0.05 tick
        assertThat(gateway.orderByTag("B1E1").orElseThrow().limitPrice()).isEqualTo(100.25);
        UpstoxHttp anonymous = new UpstoxHttp(UpstoxConfig.simulator(sim.baseUri()));
        assertThatThrownBy(() -> new UpstoxGateway(UpstoxConfig.simulator(sim.baseUri()), anonymous, instruments, null)
                .orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
        assertThatThrownBy(() -> http.send("POST", UpstoxHttp.PLACE, http.object().put("quantity", 65)
                .put("product", "I").put("validity", "DAY").put("price", 0).put("tag", "M1").put("instrument_token", KEY)
                .put("order_type", "MARKET").put("transaction_type", "BUY").put("disclosed_quantity", 0)
                .put("trigger_price", 0).put("is_amo", false).put("slice", false))).as("we never send MARKET")
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL)).hasMessageContaining("UDAPI1158");
        assertThatThrownBy(() -> http.send("POST", UpstoxHttp.PLACE, http.object().put("quantity", "65")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL));
        assertThat(UpstoxGateway.price(0, 0.05, null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void orderCallsToUpstoxItselfAreRefusedInThisBuild() {
        UpstoxConfig real = UpstoxConfig.real();
        assertThat(real.isRealBroker()).isTrue();
        UpstoxGateway toUpstox = new UpstoxGateway(real, new UpstoxHttp(real), instruments, null);
        assertThatThrownBy(() -> toUpstox.place(buy("Z1E1", 65, 100))).hasMessageContaining("no LIVE Upstox connection");
        assertThatThrownBy(() -> toUpstox.modify("Z1E1", 0, 100, 0)).hasMessageContaining("no LIVE Upstox connection");
        assertThatThrownBy(() -> toUpstox.cancel("Z1E1")).hasMessageContaining("no LIVE Upstox connection");
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
        gateway.place(stop("F10S1", 65, 71.0, 75.0));
        gateway.poll();
        assertThat(gateway.orderByTag("F10S1").orElseThrow().status()).isEqualTo(OrderStatus.TRIGGER_PENDING);
        sim.quote(KEY, 74.0, 6500, 74.5, 6500, 74.9);
        assertThat(gateway.orderByTag("F10S1").orElseThrow()).satisfies(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(o.averagePrice()).isEqualTo(74.0);
            assertThat(o.type()).isEqualTo(OrderType.STOP_LIMIT);
            assertThat(o.triggerPrice()).isEqualTo(75.0);
        });

        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        gateway.place(buy("F11E1", 65, 100.2));
        gateway.place(stop("F11S1", 65, 71.0, 75.0));
        sim.fillStopOnCancel(true);
        assertThatThrownBy(() -> gateway.cancel("F11S1")).hasMessageContaining("cannot be cancelled")
                .satisfies(e -> assertThat(kind(e)).as("UDAPI100040: finished").isEqualTo(Kind.TERMINAL));
        assertThat(gateway.orderByTag("F11S1").orElseThrow().status()).as("the exit must not be sent: the stop sold")
                .isEqualTo(OrderStatus.FILLED);
        assertThatThrownBy(() -> gateway.modify("F11S1", 0, 70, 74)).hasMessageContaining("cannot be modified")
                .satisfies(e -> assertThat(kind(e)).as("UDAPI100041").isEqualTo(Kind.TERMINAL));
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
        assertThat(UpstoxGateway.price(100.01, 0.05, OrderSide.BUY)).isEqualByComparingTo("100.05");
        assertThat(UpstoxGateway.price(100.04, 0.05, OrderSide.SELL)).isEqualByComparingTo("100.00");
        assertThat(UpstoxGateway.price(75.02, 0.05, null)).isEqualByComparingTo("75.00");
        gateway.place(buy("F14E1", 65, 90));                          // below the ask: rests
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.OPEN);
        sim.quote(KEY, 89.0, 6500, 95.0, 6500, 94);
        gateway.modify("F14E1", 0, 91, 0);                            // still below: the book shows "modified"
        assertThat(gateway.orderByTag("F14E1").orElseThrow()).satisfies(o -> {
            assertThat(o.rawStatus()).isEqualTo("modified");
            assertThat(o.status()).isEqualTo(OrderStatus.OPEN);
            assertThat(o.limitPrice()).isEqualTo(91.0);
            assertThat(o.quantity()).as("quantity 0 keeps the order's").isEqualTo(65);
        });
        gateway.modify("F14E1", 130, 91, 0);
        assertThat(gateway.orderByTag("F14E1").orElseThrow().quantity()).isEqualTo(130);
        gateway.modify("F14E1", 0, 95.5, 0);
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void statusesMapOntoOursAndAnUnknownOneIsNeverTerminal() {
        assertThat(UpstoxGateway.status("complete", 65)).isEqualTo(OrderStatus.FILLED);
        assertThat(UpstoxGateway.status("Rejected", 0)).isEqualTo(OrderStatus.REJECTED);
        assertThat(UpstoxGateway.status("cancelled", 65)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(UpstoxGateway.status("cancelled after market order", 0)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(UpstoxGateway.status("open", 0)).isEqualTo(OrderStatus.OPEN);
        assertThat(UpstoxGateway.status("open", 65)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(UpstoxGateway.status("modified", 65)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(UpstoxGateway.status("not cancelled", 0)).as("a failed cancel: still live").isEqualTo(OrderStatus.OPEN);
        assertThat(UpstoxGateway.status("not modified", 0)).isEqualTo(OrderStatus.OPEN);
        assertThat(UpstoxGateway.status("trigger pending", 0)).isEqualTo(OrderStatus.TRIGGER_PENDING);
        for (String pending : List.of("put order req received", "validation pending", "open pending", "modify pending",
                "modify validation pending", "cancel pending", "after market order req received",
                "modify after market order req received")) {
            assertThat(UpstoxGateway.status(pending, 0)).as(pending).isEqualTo(OrderStatus.PENDING);
            assertThat(UpstoxGateway.status(pending, 65)).as(pending).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        }
        assertThat(UpstoxGateway.KNOWN_STATUSES).hasSize(17);
        assertThat(UpstoxGateway.status("something new", 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(UpstoxGateway.status("something new", 130)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(UpstoxGateway.status("something new", 130).isTerminal()).isFalse();
    }

    @Test
    void positionsAreTheConfiguredProductsOnly() {
        sim.holding(KEY, 650, 90, "D");                               // not ours: another product
        gateway.place(buy("P1E1", 65, 100.2));
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.netQuantity()).isEqualTo(65);
            assertThat(p.dayBuyQuantity()).isEqualTo(65);
            assertThat(p.daySellQuantity()).isZero();
        });
        sim.quote(KEY, 110, 6500, 110.5, 6500, 110);
        gateway.place(new OrderRequest("P1X1", 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.LIMIT, 65, 109.0,
                0, "P1X1"));
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.netQuantity()).isZero();
            assertThat(p.daySellValue()).isEqualTo(110.0 * 65);
        });
    }

    @Test
    void theInstrumentFileGivesEachContractsLotTickExpiryAndStrike() throws Exception {
        List<UpstoxInstruments.Contract> nse = UpstoxInstruments.parse(new ByteArrayInputStream(sim.instrumentFile("NSE")));
        List<UpstoxInstruments.Contract> bse = UpstoxInstruments.parse(new ByteArrayInputStream(sim.instrumentFile("BSE")));
        UpstoxInstruments list = new UpstoxInstruments(new ArrayList<>(nse));
        bse.forEach(list::add);
        assertThat(list.byInstrumentKey(KEY)).hasValueSatisfying(c -> {
            assertThat(c.segment()).isEqualTo("NSE_FO");
            assertThat(c.exchangeToken()).isEqualTo(47168);
            assertThat(c.tradingSymbol()).isEqualTo(NIFTY_SYMBOL);
            assertThat(c.expiry()).as("epoch ms, the day's last ms IST").isEqualTo(NIFTY_EXPIRY);
            assertThat(c.strike()).isEqualTo(23100.0);
            assertThat(c.tickSize()).as("paise").isEqualTo(0.05);
            assertThat(c.type()).isEqualTo("PE");
            assertThat(c.lotSize()).isEqualTo(65);
        });
        assertThat(list.byInstrumentKey(SENSEX)).hasValueSatisfying(c -> {
            assertThat(c.segment()).isEqualTo("BSE_FO");
            assertThat(c.expiry()).isEqualTo(SENSEX_EXPIRY);
            assertThat(c.lotSize()).isEqualTo(20);
        });
        // other segments, futures, stock options, malformed rows and a tick out of band (a unit error) are not listed
        String odd = "[{\"segment\":\"NSE_EQ\",\"instrument_type\":\"EQ\",\"instrument_key\":\"NSE_EQ|INE002A01018\"},"
                + "{\"segment\":\"NSE_FO\",\"instrument_type\":\"FUT\",\"instrument_key\":\"NSE_FO|35001\","
                + "\"underlying_type\":\"INDEX\",\"lot_size\":75,\"tick_size\":5.0,\"expiry\":1764000000000},"
                + "{\"segment\":\"NSE_FO\",\"instrument_type\":\"CE\",\"instrument_key\":\"NSE_FO|35002\","
                + "\"underlying_type\":\"EQUITY\",\"lot_size\":500,\"tick_size\":5.0,\"expiry\":1764000000000},"
                + "{\"segment\":\"NSE_FO\",\"instrument_type\":\"CE\",\"instrument_key\":\"NSE_FO|35003\","
                + "\"underlying_type\":\"INDEX\",\"lot_size\":75,\"tick_size\":0.05,\"expiry\":1764000000000},"
                + "{\"segment\":\"NSE_FO\",\"instrument_type\":\"CE\",\"instrument_key\":\"NSE_FO|abc\","
                + "\"underlying_type\":\"INDEX\",\"lot_size\":75,\"tick_size\":5.0,\"expiry\":1764000000000},"
                + "{\"segment\":\"NSE_FO\",\"instrument_type\":\"CE\",\"instrument_key\":\"NSE_FO|35004\","
                + "\"underlying_type\":\"INDEX\",\"lot_size\":75,\"tick_size\":5.0,\"expiry\":1764000000000,"
                + "\"strike_price\":23050.0,\"trading_symbol\":\"NIFTY 23050 CE\"}]";
        assertThat(UpstoxInstruments.parse(new ByteArrayInputStream(odd.getBytes(StandardCharsets.UTF_8))))
                .singleElement().satisfies(c -> {
                    assertThat(c.instrumentKey()).isEqualTo("NSE_FO|35004");
                    assertThat(c.strike()).isEqualTo(23050.0);
                    assertThat(c.tickSize()).isEqualTo(0.05);
                });
        assertThatThrownBy(() -> UpstoxInstruments.parse(new ByteArrayInputStream("{not json".getBytes(
                StandardCharsets.UTF_8)))).hasMessageContaining("not a JSON array");
        assertThat(gateway.capabilities().tagMaxLength()).isEqualTo(20);
        assertThat(gateway.capabilities().staticIpSlots()).isEqualTo(2);
        assertThat(gateway.name()).isEqualTo("UPSTOX");
    }

    @Test
    void theConfigTrustsOnlyUpstoxsDomainOrThisHost() {
        assertThat(UpstoxConfig.simulator(sim.baseUri()).isRealBroker()).isFalse();
        assertThat(UpstoxConfig.real().isRealBroker()).isTrue();
        assertThat(UpstoxConfig.real().orderBase().getHost()).isEqualTo("api-hft.upstox.com");
        assertThat(UpstoxConfig.real().readBase().getHost()).isEqualTo("api.upstox.com");
        assertThatThrownBy(() -> UpstoxConfig.at(URI.create("http://api.upstox.com"))).hasMessageContaining("https");
        assertThatThrownBy(() -> UpstoxConfig.at(URI.create("https://evil.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpstoxConfig.at(URI.create("https://upstox.com.evil.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpstoxConfig(sim.baseUri(), sim.baseUri(), sim.baseUri(), "I", null, 10, null))
                .hasMessageContaining("1-9");
    }

    @Test
    void upstoxTimestampsAndNumbersAreReadLeniently() {
        assertThat(UpstoxHttp.time("2026-11-09 09:15:02")).isEqualTo(java.time.Instant.parse("2026-11-09T03:45:02Z"));
        assertThat(UpstoxHttp.time("09-Nov-2026 09:15:02")).isEqualTo(java.time.Instant.parse("2026-11-09T03:45:02Z"));
        assertThat(UpstoxHttp.time("09-NOV-2026 09:15:02")).isNotNull();
        assertThat(UpstoxHttp.time("null")).isNull();
        assertThat(UpstoxHttp.time("whenever")).isNull();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(UpstoxHttp.number(json.readTree("\" 1,234.50 \""), 0)).isEqualTo(1234.5);
        assertThat(UpstoxHttp.number(json.readTree("65"), 0)).isEqualTo(65);
        assertThat(UpstoxHttp.number(json.readTree("\"\""), -1)).isEqualTo(-1);
    }

    // ------------------------------------------------------------------ review fixes, 8 Nov 2026

    @Test
    void aConnectTimeoutNeverReachedUpstoxButAnAnswerTimeoutMayHave() {
        UpstoxException connect = UpstoxHttp.transportFailure(new java.net.http.HttpConnectTimeoutException("connect"),
                Call.ORDER, "POST /v3/order/place", "api-hft.upstox.com");
        assertThat(connect.kind()).isEqualTo(Kind.RETRYABLE);
        assertThat(connect.mayHaveReached()).as("no connection: nothing was sent").isFalse();
        UpstoxException answer = UpstoxHttp.transportFailure(new java.net.http.HttpTimeoutException("request"),
                Call.ORDER, "POST /v3/order/place", "api-hft.upstox.com");
        assertThat(answer.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(answer.mayHaveReached()).isTrue();
        UpstoxException refused = UpstoxHttp.transportFailure(new java.net.ConnectException("refused"), Call.ORDER,
                "POST /v3/order/place", "h");
        assertThat(refused.kind()).isEqualTo(Kind.RETRYABLE);
        assertThat(refused.mayHaveReached()).isFalse();
        assertThat(UpstoxHttp.transportFailure(new java.io.IOException("reset"), Call.READ, "GET", "h").kind())
                .isEqualTo(Kind.RETRYABLE);
    }

    @Test
    void aNetShortPositionIsAveragedOnItsSells() {
        gateway.place(buy("S1E1", 65, 100.2));                        // 65 bought at 100
        sim.quote(KEY, 110, 6500, 110.5, 6500, 110);
        gateway.place(new OrderRequest("S1X1", 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.LIMIT, 130,
                109.0, 0, "S1X1"));                                     // 130 sold at 110: net short 65
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.netQuantity()).isEqualTo(-65);
            assertThat(p.averagePrice()).as("a short's entry is its sells").isEqualTo(110.0);
        });
        assertThat(gateway.positions().getFirst().averagePrice()).isNotEqualTo(100.0);
    }

    @Test
    void fundsFallBackToV2WhenV3IsMissingOrOfAnotherShape() {
        sim.funds(250_000);
        sim.fundsV3(UpstoxSimulator.FundsV3.MISSING);
        assertThat(gateway.availableFunds()).isEqualTo(250_000);
        sim.fundsV3(UpstoxSimulator.FundsV3.OTHER_SHAPE);
        assertThat(gateway.availableFunds()).isEqualTo(250_000);
        assertThat(sim.requests("funds-v2")).isEqualTo(2);
        sim.fundsV3(UpstoxSimulator.FundsV3.OK);
        assertThat(gateway.availableFunds()).isEqualTo(250_000);
        assertThat(sim.requests("funds-v2")).as("v3 answered: no v2 call").isEqualTo(2);
        sim.failNext("funds", Fault.FUNDS_DOWN);                      // the night window is not a missing v3: no fallback
        assertThatThrownBy(() -> gateway.availableFunds()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        assertThat(sim.requests("funds-v2")).isEqualTo(2);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(UpstoxGateway.availableFrom(json.readTree("{\"equity\":{\"available_margin\":12.5}}")))
                .isEqualTo(12.5);
        assertThat(UpstoxGateway.availableFrom(json.readTree("{\"x\":1}"))).isNaN();
    }

    @Test
    void ordersGoOnlyThroughASimulatorConfigWhateverTheHost() {
        // review 8 Nov 2026: was decided by the host, so a read config pointed at any local address could place
        UpstoxConfig reads = UpstoxConfig.at(sim.baseUri());
        UpstoxGateway readOnly = new UpstoxGateway(reads, http, instruments, sim::book);
        assertThatThrownBy(() -> readOnly.place(buy("R1E1", 65, 100.2))).hasMessageContaining("no LIVE Upstox connection");
        UpstoxConfig built = new UpstoxConfig(sim.baseUri(), sim.baseUri(), sim.baseUri(), "I", null, 8, null);
        UpstoxGateway alsoReadOnly = new UpstoxGateway(built, http, instruments, sim::book);
        assertThatThrownBy(() -> alsoReadOnly.place(buy("R1E2", 65, 100.2))).hasMessageContaining("no LIVE Upstox");
        assertThatThrownBy(() -> alsoReadOnly.modify("R1E2", 0, 100, 0)).hasMessageContaining("no LIVE Upstox");
        assertThatThrownBy(() -> alsoReadOnly.cancel("R1E2")).hasMessageContaining("no LIVE Upstox");
        assertThat(sim.orderCount()).isZero();
        assertThat(readOnly.orders()).as("reads work on any config").isEmpty();
        assertThat(reads.ordersAllowed()).isFalse();
        assertThat(UpstoxConfig.real().ordersAllowed()).isFalse();
        assertThat(UpstoxConfig.simulator(sim.baseUri()).ordersAllowed()).isTrue();
        assertThatThrownBy(() -> UpstoxConfig.simulator(URI.create("https://api.upstox.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStaleInstrumentFileIsRefused() throws Exception {
        assertThat(UpstoxInstruments.load(http, NIFTY_EXPIRY).size()).as("expiring today is today's").isEqualTo(2);
        assertThatThrownBy(() -> UpstoxInstruments.load(http, SENSEX_EXPIRY.plusDays(1)))
                .as("every option already expired").hasMessageContaining("stale");
        sim.instrumentFilesModified(DAY.minusDays(1).atTime(20, 0).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .toInstant());
        assertThatThrownBy(() -> UpstoxInstruments.load(http, DAY)).as("last modified yesterday")
                .hasMessageContaining("stale").hasMessageContaining("last modified");
        sim.instrumentFilesModified(DAY.atTime(6, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
        assertThat(UpstoxInstruments.load(http, DAY).size()).isEqualTo(2);
    }

    @Test
    void aPartlyFilledOrderIsNeverResizedByModify() {
        sim.partialFills(0.5);
        gateway.place(buy("M1E1", 260, 100.2));                      // 130 of 260 filled
        int modifies = sim.requests("modify");
        assertThatThrownBy(() -> gateway.modify("M1E1", 390, 100.2, 0)).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.TERMINAL);
            assertThat(e.getMessage()).contains("resize a partly filled order by cancel and re-place");
        });
        assertThat(sim.requests("modify")).as("refused before any call").isEqualTo(modifies);
        gateway.modify("M1E1", 0, 99.0, 0);                           // price only (below the ask): allowed
        gateway.modify("M1E1", 260, 99.05, 0);                        // the same total: allowed
        assertThat(sim.requests("modify")).isEqualTo(modifies + 2);
    }

    @Test
    void aSessionLostWhileLookingUpAnUncertainPlaceIsUnknownNotSession() {
        sim.failNext("place", Fault.HTTP_500);                        // outcome unknown
        sim.failNext("orders", Fault.TOKEN_INVALID);                  // the lookup finds the session gone
        assertThatThrownBy(() -> gateway.place(buy("L1E1", 65, 100.2))).satisfies(e -> {
            assertThat(kind(e)).as("the place may have reached Upstox").isEqualTo(Kind.UNKNOWN);
            assertThat(e.getMessage()).contains("could not be looked up");
            assertThat(e.getSuppressed()).singleElement().satisfies(s -> assertThat(kind(s)).isEqualTo(Kind.SESSION));
        });
    }

    @Test
    void theSimulatorsThreadsEndWithIt() throws Exception {
        UpstoxSimulator other = new UpstoxSimulator(0, 100);
        assertThat(other.closed()).isFalse();
        other.close();
        assertThat(other.closed()).isTrue();
    }

    @Test
    void theTapSeesEveryAnswerButNeverTheTokenAndCannotBreakACall() {
        List<String> seen = new ArrayList<>();
        http.tap((call, body) -> seen.add(call + " => " + body));
        gateway.place(buy("T1E1", 65, 100.2));
        gateway.orders();
        gateway.positions();
        assertThat(seen).anySatisfy(s -> assertThat(s).startsWith("POST /v3/order/place => {"));
        assertThat(seen).anySatisfy(s -> assertThat(s).startsWith("GET /v2/order/retrieve-all => {").contains("T1E1"));
        assertThat(seen).anySatisfy(s -> assertThat(s).startsWith("GET /v2/portfolio/short-term-positions"));
        assertThat(String.join("\n", seen)).doesNotContain("any-token").doesNotContain("Bearer");
        http.tap((call, body) -> {
            throw new IllegalStateException("tap broken");
        });
        assertThat(gateway.orders()).hasSize(1);
        http.tap(null);
        assertThat(gateway.orders()).hasSize(1);
    }

    @Test
    void aMissingOrUnknownSideIsNullAndTheStatusesAreKnownOrNot() throws Exception {
        com.sun.net.httpserver.HttpServer book = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        book.createContext("/", ex -> {
            String rows = ex.getRequestURI().getPath().contains("trades")
                    ? "[{\"trade_id\":\"1\",\"order_id\":\"9\",\"instrument_token\":\"NSE_FO|47168\",\"quantity\":65,"
                    + "\"average_price\":100,\"exchange_timestamp\":\"2026-11-09 09:16:00\"}]"
                    : "[{\"order_id\":\"9\",\"status\":\"open\",\"tag\":\"Q1E1\",\"instrument_token\":\"NSE_FO|47168\","
                    + "\"quantity\":65,\"filled_quantity\":0,\"transaction_type\":\"\"},{\"order_id\":\"10\","
                    + "\"status\":\"open\",\"transaction_type\":\"SHORT\",\"quantity\":65}]";
            byte[] body = ("{\"status\":\"success\",\"data\":" + rows + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        book.start();
        try {
            UpstoxConfig c = UpstoxConfig.at(URI.create("http://127.0.0.1:" + book.getAddress().getPort()));
            UpstoxHttp h = new UpstoxHttp(c);
            h.session("t");
            UpstoxGateway g = new UpstoxGateway(c, h, instruments, null);
            assertThat(g.orders()).extracting(BrokerOrder::side).containsExactly(null, null);
            assertThat(g.trades()).singleElement().satisfies(t -> assertThat(t.side()).isNull());
            assertThat(g.poll()).as("a null side breaks nothing").isEqualTo(2);
        } finally {
            book.stop(0);
        }
        assertThat(UpstoxGateway.side("BUY")).isEqualTo(OrderSide.BUY);
        assertThat(UpstoxGateway.side("sell")).isEqualTo(OrderSide.SELL);
        assertThat(UpstoxGateway.knownStatus(" Trigger Pending ")).isTrue();
        assertThat(UpstoxGateway.knownStatus("complete")).isTrue();
        assertThat(UpstoxGateway.knownStatus("something new")).isFalse();
        assertThat(UpstoxGateway.knownStatus(null)).isFalse();
    }
}
