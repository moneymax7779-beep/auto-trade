package com.autotrade.groww;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
import com.autotrade.groww.GrowwHttp.Call;
import com.autotrade.groww.GrowwSimulator.Fault;

/**
 * The Groww adapter against the local Groww simulator: the instrument CSV, orders with our tag as the reference id
 * (padded to Groww's 8 characters and back), the books (paged order list, trades built per order), the session, the
 * 25-modification limit, and every broker fault of the Kite suite plus Groww's own (HTTP 403 static IP, an invalid
 * token, a duplicate reference id).
 */
class GrowwGatewayTest {

    private static final String KEY = "NSE_FO|47168";
    private static final String SENSEX = "BSE_FO|1125704";
    private static final String NIFTY_SYMBOL = "NIFTY2611123100PE";
    private static final LocalDate NIFTY_EXPIRY = LocalDate.of(2026, 11, 11);
    private static final LocalDate SENSEX_EXPIRY = LocalDate.of(2026, 11, 13);

    private GrowwSimulator sim;
    private GrowwHttp http;
    private GrowwGateway gateway;
    private GrowwInstruments instruments;
    private final List<OrderUpdate> updates = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        sim = new GrowwSimulator(0, 2_500);
        sim.list(new GrowwSimulator.Listing(KEY, NIFTY_SYMBOL, "NIFTY", NIFTY_EXPIRY, 23100, "PE", 65, 0.05));
        sim.list(new GrowwSimulator.Listing(SENSEX, "SENSEX2611374600PE", "SENSEX", SENSEX_EXPIRY, 74600, "PE", 20,
                0.05));
        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        GrowwConfig config = GrowwConfig.simulator(sim.baseUri());
        http = new GrowwHttp(config);
        instruments = GrowwInstruments.load(http);
        assertThat(instruments.size()).isEqualTo(2);
        http.session("any-token");
        gateway = new GrowwGateway(config, http, instruments, sim::book);
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
    void ourTagsBecomeGrowwReferenceIdsAndComeBackExactly() {
        assertThat(GrowwGateway.referenceId("F12E1")).isEqualTo("F12E1-00");
        assertThat(GrowwGateway.referenceId("F")).isEqualTo("F-000000");
        assertThat(GrowwGateway.referenceId("F12E1N1")).as("7 characters: one zero still").isEqualTo("F12E1N1-0");
        assertThat(GrowwGateway.referenceId("F123E1N1")).as("8 or more: as it is").isEqualTo("F123E1N1");
        assertThat(GrowwGateway.referenceId("F1234567890123E1N1S2")).hasSize(20);
        assertThat(GrowwGateway.tagOf("F12E1-00")).isEqualTo("F12E1");
        assertThat(GrowwGateway.tagOf("F123E1N1")).isEqualTo("F123E1N1");
        assertThat(GrowwGateway.tagOf("F100E100")).as("zeros of the tag itself are kept").isEqualTo("F100E100");
        assertThat(GrowwGateway.tagOf(null)).isNull();
        assertThat(GrowwGateway.tagOf("null")).isNull();
        assertThatThrownBy(() -> GrowwGateway.referenceId("F12-E1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrowwGateway.referenceId("F".repeat(21))).isInstanceOf(IllegalArgumentException.class);

        // every length 1-20, tags ending in zeros, and random tags: Groww's rule holds, the round trip is exact and no
        // two tags share a reference id
        Random random = new Random(8);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Set<String> tags = new HashSet<>(List.of("F0", "F00", "F0000000", "F1E10", "F1E1000", "0", "00000000", "1-0"
                .replace("-", "")));
        for (int i = 0; i < 20_000; i++) {
            int length = 1 + random.nextInt(20);
            StringBuilder t = new StringBuilder();
            for (int j = 0; j < length; j++) {
                t.append(random.nextInt(4) == 0 ? '0' : alphabet.charAt(random.nextInt(alphabet.length())));
            }
            tags.add(t.toString());
        }
        Set<String> references = new HashSet<>();
        for (String tag : tags) {
            String reference = GrowwGateway.referenceId(tag);
            assertThat(GrowwGateway.validReference(reference)).as(reference).isTrue();
            assertThat(GrowwGateway.unpadded(reference)).as(tag).isEqualTo(tag);
            assertThat(GrowwGateway.tagOf(reference)).as("only the OMS's tags read back: " + tag)
                    .isEqualTo(GrowwGateway.ours(tag) ? tag : null);
            assertThat(references.add(reference)).as("no collision: " + reference).isTrue();
        }
        assertThat(GrowwGateway.validReference("F12E1")).as("under 8").isFalse();
        assertThat(GrowwGateway.validReference("A-B-C-D1234")).as("three hyphens").isFalse();
        assertThat(GrowwGateway.validReference("ABCD_1234")).isFalse();
    }

    @Test
    void onlyTheDaysTokenIsAcceptedAndItIsNeverInAMessage() {
        sim.strictSessions();
        assertThatThrownBy(() -> gateway.availableFunds()).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.SESSION);
            assertThat(e.getMessage()).doesNotContain("any-token").contains("HTTP 401");
        });
        GrowwSimulator.Session s = sim.login();
        http.session(s.accessToken());
        assertThat(gateway.availableFunds()).isEqualTo(1e9);
        assertThat(s.toString()).doesNotContain(s.accessToken());
        sim.expireSession();
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.SESSION);
            assertThat(e.getMessage()).doesNotContain(s.accessToken());
        });
        GrowwSimulator.Session next = sim.login();
        http.session(next.accessToken());
        assertThat(gateway.orders()).as("an empty list is no orders").isEmpty();
        assertThat(sim.tokensSentToAssets()).as("the instrument file is public: no token goes there").isZero();
    }

    @Test
    void aMarketableLimitFillsAtTheAskAndPollReportsTheFillOnce() {
        gateway.place(buy("F1E1", 130, 100.20));
        assertThat(gateway.poll()).isEqualTo(1);
        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(u.filledQuantity()).isEqualTo(130);
            assertThat(u.lastFillPrice()).isEqualTo(100.0);
            assertThat(u.clientOrderId()).as("our tag comes back from the padded reference id").isEqualTo("F1E1");
        });
        assertThat(gateway.poll()).as("an unchanged list reports nothing").isZero();
        gateway.place(buy("F1E1", 130, 100.20));
        assertThat(sim.orderCount()).as("a repeated tag is not placed twice").isEqualTo(1);
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.instrumentKey()).as("exchange + trading symbol mapped back to our key").isEqualTo(KEY);
            assertThat(p.instrumentToken()).isEqualTo(47168);
            assertThat(p.netQuantity()).isEqualTo(130);
            assertThat(p.averagePrice()).isEqualTo(100.0);
            assertThat(p.dayBuyValue()).isEqualTo(13_000.0);
        });
        assertThat(gateway.trades()).singleElement().satisfies(t -> {
            assertThat(t.price()).isEqualTo(100.0);
            assertThat(t.instrumentKey()).as("the trade carries its order's key").isEqualTo(KEY);
            assertThat(t.side()).isEqualTo(OrderSide.BUY);
            assertThat(t.time()).isNotNull();
            assertThat(t.tradeId()).startsWith("GTR");
        });
        assertThat(gateway.orderByTag("F1E1").orElseThrow()).satisfies(o -> {
            assertThat(o.symbol()).isEqualTo(NIFTY_SYMBOL);
            assertThat(o.rawStatus()).isEqualTo("EXECUTED");
            assertThat(o.updatedAt()).isNotNull();
        });
        assertThat(gateway.quote(KEY).ask()).isEqualTo(100.0);
        assertThat(gateway.availableFunds()).isEqualTo(1e9 - 13_000);
    }

    @Test
    void tradesAreBuiltFromTheDaysFilledOrdersAndAFinishedOrdersTradesAreReadOnce() {
        sim.depth(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65),
                new Quote.Level(100.05, 6500)), 99.8);
        gateway.place(buy("F71E1", 130, 100.10));                      // two fills: 65 at 100.00 and 65 at 100.05
        gateway.place(buy("F72E1", 65, 90));                           // rests: no fill, no trades read
        assertThat(gateway.trades()).hasSize(2).extracting(t -> t.brokerOrderId()).containsOnly(
                gateway.orderByTag("F71E1").orElseThrow().brokerOrderId());
        assertThat(sim.requests("trades")).as("only the filled order's trades").isEqualTo(1);
        assertThat(gateway.trades()).hasSize(2);
        assertThat(sim.requests("trades")).as("a finished order's trades are kept").isEqualTo(1);

        sim.partialFills(0.5);
        gateway.place(buy("F73E1", 130, 100.10));                      // partially filled: read every time
        gateway.trades();
        gateway.trades();
        assertThat(sim.requests("trades")).isEqualTo(3);
    }

    @Test
    void theOrderListIsReadPageByPage() throws Exception {
        GrowwConfig paged = GrowwConfig.simulator(sim.baseUri()).withPageSize(2);
        GrowwGateway small = new GrowwGateway(paged, http, instruments, sim::book);
        for (int i = 1; i <= 5; i++) {
            small.place(buy("F8" + i + "E1", 65, 90));
        }
        int before = sim.requests("orders");
        assertThat(small.orders()).extracting(BrokerOrder::clientTag).containsExactly("F81E1", "F82E1", "F83E1", "F84E1",
                "F85E1");
        assertThat(sim.requests("orders") - before).as("pages of 2, 2 and 1, then the empty page that ends the read")
                .isEqualTo(4);
        small.place(buy("F86E1", 65, 90));
        before = sim.requests("orders");
        assertThat(small.orders()).hasSize(6);
        assertThat(sim.requests("orders") - before).as("a full last page needs one more, empty read").isEqualTo(4);
        assertThatThrownBy(() -> GrowwConfig.simulator(sim.baseUri()).withPageSize(101)).hasMessageContaining("1-100");
    }

    @Test
    void theQuoteComesFromTheSuppliedSourceAndWithoutOneNoOrderIsPriced() {
        sim.depth(KEY, List.of(new Quote.Level(99.5, 650), new Quote.Level(99.0, 1300)),
                List.of(new Quote.Level(100.0, 650), new Quote.Level(100.5, 1300), new Quote.Level(101.0, 6500)), 99.8);
        Quote q = gateway.quote(KEY);
        assertThat(q.asks()).containsExactly(new Quote.Level(100.0, 650), new Quote.Level(100.5, 1300),
                new Quote.Level(101.0, 6500));
        gateway.place(buy("F91E1", 2600, 100.5));                     // 650 at 100.00 + 1,300 at 100.50; 650 rest open
        BrokerOrder first = gateway.orderByTag("F91E1").orElseThrow();
        assertThat(first.filledQuantity()).isEqualTo(1950);
        assertThat(first.averagePrice()).isCloseTo((650 * 100.0 + 1300 * 100.5) / 1950, within(0.01));
        assertThat(first.status()).as("a partial fill: filled_quantity on OPEN").isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(first.rawStatus()).isEqualTo("OPEN");
        assertThat(gateway.quote(KEY).asks()).containsExactly(new Quote.Level(101.0, 6500));

        GrowwGateway noQuotes = new GrowwGateway(GrowwConfig.simulator(sim.baseUri()), http, instruments, null);
        assertThatThrownBy(() -> noQuotes.quote(KEY)).isInstanceOf(BrokerException.class)
                .hasMessageContaining("not implemented").satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL));
        assertThatThrownBy(() -> gateway.quote(SENSEX)).as("no book for it").isInstanceOf(BrokerException.class);
    }

    @Test
    void aPlaceWhoseOutcomeIsUnknownIsLookedUpByReferenceNeverPlacedTwice() {
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

        sim.failNext("place", Fault.HTTP_500);                        // GA000 not processed: unknown, looked up, not re-sent
        assertThatThrownBy(() -> gateway.place(buy("F4E2", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.UNKNOWN));
        assertThat(sim.orderCount()).isEqualTo(2);

        sim.failNext("place", Fault.HTTP_429);                        // dropped before processing: retried
        gateway.place(buy("F5E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(3);
        assertThat(gateway.orders()).extracting(BrokerOrder::clientTag).containsExactly("F2E1", "F3E1", "F5E1");

        // a restarted worker (a new gateway) finds the order by tag (the venue reads the book before a second place)
        GrowwGateway restarted = new GrowwGateway(GrowwConfig.simulator(sim.baseUri()), http, instruments, sim::book);
        assertThat(restarted.orderByTag("F5E1")).isPresent();
        assertThatThrownBy(() -> restarted.cancel("F5E1")).as("it knows the order; filled, so Groww refuses")
                .hasMessageContaining("cannot be cancelled").hasMessageContaining(GrowwHttp.CANNOT_PROCESS);
    }

    @Test
    void aDuplicateReferenceIsAdoptedWhenItIsOursAndUnknownWhenItIsNot() {
        gateway.place(buy("F61E1", 65, 100.2));
        GrowwGateway restarted = new GrowwGateway(GrowwConfig.simulator(sim.baseUri()), http, instruments, sim::book);
        restarted.place(buy("F61E1", 65, 100.2));                      // Groww: GA007; found by reference: adopted
        assertThat(sim.orderCount()).as("never a second order").isEqualTo(1);
        assertThat(sim.requests("place")).isEqualTo(2);

        sim.failNext("place", Fault.DUPLICATE_REF);                   // GA007 for a reference no order of ours has
        assertThatThrownBy(() -> gateway.place(buy("F62E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.UNKNOWN)).hasMessageContaining("GA007");
        assertThat(sim.orderCount()).isEqualTo(1);
    }

    @Test
    void anOrderNotYetInTheListIsFoundByItsReferenceId() {
        sim.staleOrderBookOnce();                                     // the next list is the one before this order
        gateway.place(buy("F51E1", 65, 100.2));
        assertThat(gateway.orderByTag("F51E1")).as("status by reference, then the detail").hasValueSatisfying(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(o.filledQuantity()).isEqualTo(65);
            assertThat(o.clientTag()).isEqualTo("F51E1");
            assertThat(o.instrumentKey()).isEqualTo(KEY);
        });
        assertThat(sim.requests("status")).isEqualTo(1);
        assertThat(sim.requests("detail")).isEqualTo(1);
        assertThat(gateway.orderByTag("F99E9")).as("GA004 is no order").isEmpty();
        assertThat(sim.requests("status")).isEqualTo(2);

        sim.ackLag(2);                          // not acknowledged yet (status and detail reads): pending, nothing filled
        gateway.place(buy("F52E1", 65, 100.2));
        BrokerOrder pending = gateway.orderByTag("F52E1").orElseThrow();
        assertThat(pending.rawStatus()).isEqualTo("NEW");
        assertThat(pending.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(gateway.orderByTag("F52E1").orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void errorsAreClassified() {
        sim.failNext("place", Fault.TOKEN_EXPIRED);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION));
        sim.failNext("place", Fault.TOKEN_INVALID);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION)).hasMessageContaining("log in again");
        sim.failNext("place", Fault.STATIC_IP);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION)).hasMessageContaining("HTTP 403").hasMessageContaining("static IP")
                .hasMessageContaining("logging in again may not help");
        assertThat(sim.orderCount()).as("nothing placed by a refused call").isZero();
        sim.failNext("place", Fault.INPUT_ERROR);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.TERMINAL)).hasMessageContaining("circuit").hasMessageContaining("GA001");
        assertThatThrownBy(() -> gateway.place(buy("F6-E1", 65, 100.2))).hasMessageContaining("alphanumerics");
        assertThatThrownBy(() -> gateway.place(new OrderRequest("F7E1", 1, "NSE_FO|1", "X", "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, 65, 10, 0, "F7E1"))).hasMessageContaining("not in Groww's checked instrument list");
        assertThatThrownBy(() -> gateway.place(buy("F7E2", 64, 100.2))).as("not a whole lot: refused before sending")
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL)).hasMessageContaining("whole number of lots");
        assertThat(sim.requests("place")).as("the lot check sends nothing").isEqualTo(4);
        sim.failNext("place", Fault.REJECT_FUNDS);                    // SUCCESS with order_status REJECTED
        assertThatThrownBy(() -> gateway.place(buy("F8E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.TERMINAL)).hasMessageContaining("rejected").hasMessageContaining("Insufficient balance");
        gateway.poll();
        assertThat(updates).last().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(u.message()).startsWith("Insufficient balance");
        });
        gateway.place(buy("F8E1", 65, 100.2));
        assertThat(sim.requests("place")).as("a rejected tag is not sent again").isEqualTo(5);
        sim.failNext("orders", Fault.HTTP_500);                       // a read: retryable, never unknown
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        sim.failNext("orders", Fault.STATIC_IP);                      // reads from another IP: still the account's IP
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
        sim.expireSession();
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
    }

    @Test
    void theErrorTableMapsGrowwCodes() {
        assertThat(GrowwHttp.kind(401, "", Call.READ)).isEqualTo(Kind.SESSION);
        assertThat(GrowwHttp.kind(403, "", Call.ORDER)).isEqualTo(Kind.SESSION);
        assertThat(GrowwHttp.kind(400, "GA005", Call.ORDER)).isEqualTo(Kind.SESSION);
        assertThat(GrowwHttp.kind(429, "", Call.ORDER)).isEqualTo(Kind.RETRYABLE);
        assertThat(GrowwHttp.kind(500, "GA000", Call.ORDER)).isEqualTo(Kind.UNKNOWN);
        assertThat(GrowwHttp.kind(400, "GA000", Call.ORDER)).isEqualTo(Kind.UNKNOWN);
        assertThat(GrowwHttp.kind(503, "GA003", Call.ORDER)).isEqualTo(Kind.UNKNOWN);
        assertThat(GrowwHttp.kind(504, "", Call.ORDER)).isEqualTo(Kind.UNKNOWN);
        assertThat(GrowwHttp.kind(500, "GA000", Call.READ)).isEqualTo(Kind.RETRYABLE);
        assertThat(GrowwHttp.kind(503, "", Call.READ)).isEqualTo(Kind.RETRYABLE);
        for (String terminal : List.of("GA001", "GA004", "GA006", "GA007", "GA999")) {
            assertThat(GrowwHttp.kind(400, terminal, Call.ORDER)).as(terminal).isEqualTo(Kind.TERMINAL);
        }
        assertThat(GrowwHttp.kind(404, "GA004", Call.READ)).isEqualTo(Kind.TERMINAL);
        assertThat(GrowwHttp.kind(400, "", Call.ORDER)).isEqualTo(Kind.TERMINAL);
        assertThat(GrowwHttp.kind(400, null, Call.ORDER)).isEqualTo(Kind.TERMINAL);
    }

    @Test
    void anAnswerThatIsNotGrowwsEnvelopeIsUnknownForAnOrderAndRetryableForARead() throws Exception {
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
            GrowwHttp h = new GrowwHttp(GrowwConfig.simulator(URI.create("http://127.0.0.1:"
                    + proxy.getAddress().getPort())));
            h.session("t");
            assertThatThrownBy(() -> h.post(GrowwHttp.PLACE, h.object().put("quantity", 65)))
                    .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.UNKNOWN));
            assertThatThrownBy(() -> h.get(GrowwHttp.LIST)).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void theOrderBodyHasGrowwsFieldsAndTypesAndCallsWithoutTheTokenAreRefused() {
        // the simulator refuses a missing field, a string quantity or price, MARKET / SL_M, a reference id outside
        // Groww's rule, a symbol not in the CSV and a price off the tick: a place it accepts carries Groww's body exactly
        gateway.place(buy("F31E1", 65, 100.21));                      // rounded up to 100.25 on the 0.05 tick
        assertThat(gateway.orderByTag("F31E1").orElseThrow().limitPrice()).isEqualTo(100.25);
        GrowwHttp anonymous = new GrowwHttp(GrowwConfig.simulator(sim.baseUri()));
        assertThatThrownBy(() -> new GrowwGateway(GrowwConfig.simulator(sim.baseUri()), anonymous, instruments, null)
                .orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
        assertThatThrownBy(() -> http.post(GrowwHttp.PLACE, http.object().put("trading_symbol", NIFTY_SYMBOL)
                .put("quantity", 65).put("price", 0).put("validity", "DAY").put("exchange", "NSE").put("segment", "FNO")
                .put("product", "MIS").put("order_type", "MARKET").put("transaction_type", "BUY")
                .put("order_reference_id", "M1-000000"))).as("we never send MARKET")
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL));
        assertThatThrownBy(() -> http.post(GrowwHttp.PLACE, http.object().put("trading_symbol", NIFTY_SYMBOL)
                .put("quantity", 65).put("price", 100).put("validity", "DAY").put("exchange", "NSE").put("segment", "FNO")
                .put("product", "MIS").put("order_type", "LIMIT").put("transaction_type", "BUY")
                .put("order_reference_id", "M1"))).as("a reference id under 8 characters")
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL)).hasMessageContaining("8-20");
        assertThatThrownBy(() -> http.post(GrowwHttp.PLACE, http.object().put("quantity", "65")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL));
        assertThat(GrowwGateway.price(0, 0.05, null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void orderCallsToGrowwItselfAreRefusedInThisBuild() {
        GrowwConfig real = GrowwConfig.real();
        assertThat(real.isRealBroker()).isTrue();
        GrowwGateway toGroww = new GrowwGateway(real, new GrowwHttp(real), instruments, null);
        assertThatThrownBy(() -> toGroww.place(buy("Z1E1", 65, 100))).hasMessageContaining("no LIVE Groww connection");
        assertThatThrownBy(() -> toGroww.modify("Z1E1", 0, 100, 0)).hasMessageContaining("no LIVE Groww connection");
        assertThatThrownBy(() -> toGroww.cancel("Z1E1")).hasMessageContaining("no LIVE Groww connection");
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
                .satisfies(e -> assertThat(kind(e)).as("GA006: finished").isEqualTo(Kind.TERMINAL));
        assertThat(gateway.orderByTag("F11S1").orElseThrow().status()).as("the exit must not be sent: the stop sold")
                .isEqualTo(OrderStatus.FILLED);
        assertThatThrownBy(() -> gateway.modify("F11S1", 0, 70, 74)).hasMessageContaining("cannot be modified")
                .satisfies(e -> assertThat(kind(e)).as("GA006").isEqualTo(Kind.TERMINAL));
    }

    @Test
    void aStaleOrderListDoesNotUndoAFill() {
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
    void theRateLimiterKeepsOrdersBelowTenASecondAndUnderTheMinuteCap() {
        long start = System.nanoTime();
        for (int i = 0; i < 12; i++) {
            gateway.place(buy("F6" + i + "E1", 65, 100.2));
        }
        assertThat((System.nanoTime() - start) / 1e9).as("12 orders at 8 a second").isGreaterThanOrEqualTo(0.9);
        assertThat(sim.orderCount()).isEqualTo(12);

        // the minute cap, scaled down: 3 in any 400 ms, however fast the second allows
        GrowwHttp.Limiter limiter = new GrowwHttp.Limiter(9, 3, Duration.ofMillis(400));
        start = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            limiter.acquire();
        }
        assertThat((System.nanoTime() - start) / 1e6).as("the 4th waits for the window").isGreaterThanOrEqualTo(390);
        assertThatThrownBy(() -> new GrowwConfig(sim.baseUri(), sim.baseUri(), "MIS", null, 8, 250, null, 100))
                .hasMessageContaining("1-249");
    }

    @Test
    void pricesGoOnTheTickAndModifyChangesTheRestingOrder() {
        assertThat(GrowwGateway.price(100.01, 0.05, OrderSide.BUY)).isEqualByComparingTo("100.05");
        assertThat(GrowwGateway.price(100.04, 0.05, OrderSide.SELL)).isEqualByComparingTo("100.00");
        assertThat(GrowwGateway.price(75.02, 0.05, null)).isEqualByComparingTo("75.00");
        gateway.place(buy("F14E1", 65, 90));                          // below the ask: rests
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.OPEN);
        sim.quote(KEY, 89.0, 6500, 95.0, 6500, 94);
        gateway.modify("F14E1", 0, 91, 0);
        assertThat(gateway.orderByTag("F14E1").orElseThrow()).satisfies(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.OPEN);
            assertThat(o.limitPrice()).isEqualTo(91.0);
            assertThat(o.quantity()).as("quantity 0 keeps the order's").isEqualTo(65);
        });
        gateway.modify("F14E1", 130, 91, 0);
        assertThat(gateway.orderByTag("F14E1").orElseThrow().quantity()).isEqualTo(130);
        gateway.modify("F14E1", 0, 95.5, 0);
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(gateway.modifications("F14E1")).isEqualTo(3);
    }

    @Test
    void theTwentySixthModificationOfAnOrderIsRefusedWithoutBeingSent() {
        sim.quote(KEY, 80.0, 6500, 95.0, 6500, 90);
        gateway.place(buy("F41E1", 65, 85));
        for (int i = 0; i < GrowwGateway.MAX_MODIFICATIONS; i++) {
            gateway.modify("F41E1", 0, i % 2 == 0 ? 86 : 85, 0);
        }
        assertThat(sim.requests("modify")).isEqualTo(25);
        assertThatThrownBy(() -> gateway.modify("F41E1", 0, 86, 0)).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.TERMINAL)).hasMessageContaining("at most 25 modifications");
        assertThat(sim.requests("modify")).as("the 26th is never sent").isEqualTo(25);
        // a restarted gateway does not know the count: Groww's own refusal (GA006) is terminal too
        GrowwGateway restarted = new GrowwGateway(GrowwConfig.simulator(sim.baseUri()), http, instruments, sim::book);
        assertThatThrownBy(() -> restarted.modify("F41E1", 0, 86, 0)).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.TERMINAL)).hasMessageContaining("GA006");
        gateway.cancel("F41E1");                                       // a cancel is not a modification
        assertThat(gateway.orderByTag("F41E1").orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void statusesMapOntoOursAndAnUnknownOneIsNeverTerminal() {
        assertThat(GrowwGateway.status("EXECUTED", 65)).isEqualTo(OrderStatus.FILLED);
        assertThat(GrowwGateway.status("COMPLETED", 65)).isEqualTo(OrderStatus.FILLED);
        assertThat(GrowwGateway.status("Rejected", 0)).isEqualTo(OrderStatus.REJECTED);
        assertThat(GrowwGateway.status("FAILED", 0)).isEqualTo(OrderStatus.REJECTED);
        assertThat(GrowwGateway.status("CANCELLED", 65)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(GrowwGateway.status("OPEN", 0)).isEqualTo(OrderStatus.OPEN);
        assertThat(GrowwGateway.status("ACKED", 0)).isEqualTo(OrderStatus.OPEN);
        assertThat(GrowwGateway.status("OPEN", 65)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(GrowwGateway.status("MODIFICATION_REQUESTED", 0)).isEqualTo(OrderStatus.OPEN);
        assertThat(GrowwGateway.status("CANCELLATION_REQUESTED", 65)).as("still live until CANCELLED")
                .isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(GrowwGateway.status("TRIGGER_PENDING", 0)).isEqualTo(OrderStatus.TRIGGER_PENDING);
        for (String pending : List.of("NEW", "APPROVED", "DELIVERY_AWAITED")) {
            assertThat(GrowwGateway.status(pending, 0)).as(pending).isEqualTo(OrderStatus.PENDING);
            assertThat(GrowwGateway.status(pending, 65)).as(pending).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        }
        assertThat(GrowwGateway.KNOWN_STATUSES).hasSize(13);
        assertThat(GrowwGateway.status("SOMETHING_NEW", 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(GrowwGateway.status("SOMETHING_NEW", 130)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(GrowwGateway.status("SOMETHING_NEW", 130).isTerminal()).isFalse();
    }

    @Test
    void positionsAreTheConfiguredProductsOnly() {
        sim.holding(KEY, 650, 90, "NRML");                            // not ours: another product
        gateway.place(buy("F21E1", 65, 100.2));
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.netQuantity()).isEqualTo(65);
            assertThat(p.dayBuyQuantity()).isEqualTo(65);
            assertThat(p.daySellQuantity()).isZero();
        });
        sim.quote(KEY, 110, 6500, 110.5, 6500, 110);
        gateway.place(new OrderRequest("F21X1", 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.LIMIT, 65, 109.0,
                0, "F21X1"));
        assertThat(gateway.positions()).singleElement().satisfies(p -> {
            assertThat(p.netQuantity()).isZero();
            assertThat(p.daySellValue()).isEqualTo(110.0 * 65);
        });
    }

    @Test
    void theInstrumentCsvGivesEachContractsKeyLotTickExpiryAndStrike() throws Exception {
        GrowwInstruments list = new GrowwInstruments(GrowwInstruments.parse(new ByteArrayInputStream(
                sim.instrumentFile())));
        assertThat(list.size()).as("the equity row is not ours").isEqualTo(2);
        assertThat(list.byInstrumentKey(KEY)).hasValueSatisfying(c -> {
            assertThat(c.exchange()).isEqualTo("NSE");
            assertThat(c.exchangeToken()).isEqualTo(47168);
            assertThat(c.tradingSymbol()).isEqualTo(NIFTY_SYMBOL);
            assertThat(c.expiry()).isEqualTo(NIFTY_EXPIRY);
            assertThat(c.strike()).isEqualTo(23100.0);
            assertThat(c.tickSize()).isEqualTo(0.05);
            assertThat(c.type()).isEqualTo("PE");
            assertThat(c.lotSize()).isEqualTo(65);
            assertThat(c.underlying()).isEqualTo("NIFTY");
        });
        assertThat(list.byInstrumentKey(SENSEX)).hasValueSatisfying(c -> {
            assertThat(c.exchange()).isEqualTo("BSE");
            assertThat(c.expiry()).isEqualTo(SENSEX_EXPIRY);
            assertThat(c.lotSize()).isEqualTo(20);
        });
        assertThat(list.bySymbol("nse", NIFTY_SYMBOL)).hasValueSatisfying(c -> assertThat(c.instrumentKey())
                .isEqualTo(KEY));
        assertThat(list.bySymbol("BSE", NIFTY_SYMBOL)).as("the symbol on another exchange").isEmpty();
        // columns found by name in any order; futures, other segments, malformed rows and a tick out of band (a unit
        // error) are not listed; a quoted name with a comma does not shift the columns
        String odd = "tick_size,lot_size,segment,exchange,instrument_type,exchange_token,trading_symbol,expiry_date,"
                + "strike_price,name\n"
                + "0.05,75,FNO,NSE,FUT,35001,NIFTY26NOVFUT,2026-11-25,0,NIFTY\n"
                + "0.05,75,CASH,NSE,CE,35002,X,2026-11-25,23000,X\n"
                + "5,75,FNO,NSE,CE,35003,NIFTY2611123000CE,2026-11-11,23000,NIFTY\n"
                + "0.05,75,FNO,NSE,CE,abc,NIFTY2611123000CE,2026-11-11,23000,NIFTY\n"
                + "0.05,75,FNO,NSE,CE,35005,NIFTY2611123000CE,11-11-2026,23000,NIFTY\n"
                + "0.05,75,FNO,MCX,CE,35006,CRUDE,2026-11-11,5000,CRUDE\n"
                + "0.05,75,FNO,NSE,CE,35004,NIFTY2611123050CE,2026-11-11,23050.0,\"NIFTY, 50\"\n";
        assertThat(GrowwInstruments.parse(new ByteArrayInputStream(odd.getBytes(StandardCharsets.UTF_8))))
                .singleElement().satisfies(c -> {
                    assertThat(c.instrumentKey()).isEqualTo("NSE_FO|35004");
                    assertThat(c.strike()).isEqualTo(23050.0);
                    assertThat(c.tickSize()).isEqualTo(0.05);
                    assertThat(c.underlying()).isEqualTo("NIFTY, 50");
                });
        assertThatThrownBy(() -> GrowwInstruments.parse(new ByteArrayInputStream(
                "exchange,trading_symbol\nNSE,X\n".getBytes(StandardCharsets.UTF_8)))).hasMessageContaining("no column");
        assertThatThrownBy(() -> GrowwInstruments.parse(new ByteArrayInputStream(new byte[0])))
                .hasMessageContaining("empty");
        assertThat(gateway.capabilities().tagMaxLength()).isEqualTo(20);
        assertThat(gateway.capabilities().staticIpSlots()).isEqualTo(1);
        assertThat(gateway.name()).isEqualTo("GROWW");
    }

    @Test
    void theConfigTrustsOnlyGrowwsDomainOrThisHost() {
        assertThat(GrowwConfig.simulator(sim.baseUri()).isRealBroker()).isFalse();
        assertThat(GrowwConfig.real().isRealBroker()).isTrue();
        assertThat(GrowwConfig.real().apiBase().getHost()).isEqualTo("api.groww.in");
        assertThat(GrowwConfig.real().assetsBase().getHost()).isEqualTo("growwapi-assets.groww.in");
        assertThatThrownBy(() -> GrowwConfig.at(URI.create("http://api.groww.in"))).hasMessageContaining("https");
        assertThatThrownBy(() -> GrowwConfig.at(URI.create("https://evil.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrowwConfig.at(URI.create("https://groww.in.evil.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrowwConfig.at(URI.create("https://notgroww.in")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrowwConfig(sim.baseUri(), sim.baseUri(), "MIS", null, 10, 200, null, 100))
                .hasMessageContaining("1-9");
    }

    @Test
    void growwTimestampsAndNumbersAreReadLeniently() {
        assertThat(GrowwHttp.time("2026-11-09T09:15:02")).isEqualTo(java.time.Instant.parse("2026-11-09T03:45:02Z"));
        assertThat(GrowwHttp.time("2026-11-09T09:15:02.123")).isEqualTo(java.time.Instant.parse("2026-11-09T03:45:02Z"));
        assertThat(GrowwHttp.time("2026-11-09 09:15:02")).isEqualTo(java.time.Instant.parse("2026-11-09T03:45:02Z"));
        assertThat(GrowwHttp.time("2026-11-09T09:15:02+05:30")).isEqualTo(java.time.Instant.parse("2026-11-09T03:45:02Z"));
        assertThat(GrowwHttp.time("null")).isNull();
        assertThat(GrowwHttp.time("whenever")).isNull();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(GrowwHttp.number(json.readTree("\" 1,234.50 \""), 0)).isEqualTo(1234.5);
        assertThat(GrowwHttp.number(json.readTree("65"), 0)).isEqualTo(65);
        assertThat(GrowwHttp.number(json.readTree("\"\""), -1)).isEqualTo(-1);
    }

    // ------------------------------------------------------------------ review fixes, 8 Nov 2026

    private static OrderRequest raw(String tag) {
        return buy(tag, 65, 90);
    }

    @Test
    void onlyTheOmsTagsWhosePaddingIsExactAreReadBackAsOurs() {
        assertThat(GrowwGateway.tagOf("ABCDEFGH")).as("another app's reference").isNull();
        assertThat(GrowwGateway.tagOf("F12E1-000")).as("not the padding we send for F12E1").isNull();
        assertThat(GrowwGateway.tagOf("F10E100")).as("F10E100 is sent as F10E100-0, never as it is").isNull();
        assertThat(GrowwGateway.tagOf("F12E1-00")).isEqualTo("F12E1");
        // orders placed by hand or another app with such references are in the book, but none is ours
        for (String reference : List.of("ABCDEFGHIJ", "F12E1-000", "manual-001")) {
            http.post(GrowwHttp.PLACE, http.object().put("trading_symbol", NIFTY_SYMBOL).put("quantity", 65)
                    .put("price", 90).put("validity", "DAY").put("exchange", "NSE").put("segment", "FNO")
                    .put("product", "MIS").put("order_type", "LIMIT").put("transaction_type", "BUY")
                    .put("order_reference_id", reference));
        }
        gateway.place(raw("F12E1"));
        assertThat(gateway.orders()).hasSize(4).extracting(BrokerOrder::clientTag).containsOnly(null, "F12E1")
                .containsOnlyOnce("F12E1");
        assertThat(gateway.orderByTag("F12E1")).isPresent();

        // GA007 for a tag that is not the OMS's: never adopted (it cannot be told to be ours), nothing looked up
        int status = sim.requests("status");
        sim.failNext("place", Fault.DUPLICATE_REF);
        assertThatThrownBy(() -> gateway.place(raw("X1E1"))).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL))
                .hasMessageContaining("not one of ours");
        assertThat(sim.requests("status")).isEqualTo(status);
    }

    @Test
    void theOrderListIsReadUntilAnEmptyPageAndARowOnTwoPagesIsKeptOnce() {
        for (int i = 1; i <= 5; i++) {
            gateway.place(raw("F2" + i + "E1"));
        }
        sim.pageCap(2);                                               // Groww serves 2 a page although 100 are asked
        assertThat(gateway.orders()).as("a short page is not the last").extracting(BrokerOrder::clientTag)
                .containsExactly("F21E1", "F22E1", "F23E1", "F24E1", "F25E1");
        sim.pageOverlap(true);                                        // the list moved: each page repeats a row
        assertThat(gateway.orders()).extracting(BrokerOrder::clientTag)
                .containsExactly("F21E1", "F22E1", "F23E1", "F24E1", "F25E1");
    }

    @Test
    void anOrderIsLookedUpByItsReferenceNotByReadingTheWholeList() {
        gateway.place(raw("F31E1"));
        int orders = sim.requests("orders");
        assertThat(gateway.orderByTag("F31E1")).hasValueSatisfying(o -> assertThat(o.clientTag()).isEqualTo("F31E1"));
        assertThat(sim.requests("orders")).as("no list read").isEqualTo(orders);
        assertThat(sim.requests("status")).isEqualTo(1);
        assertThat(sim.requests("detail")).isEqualTo(1);
        assertThat(gateway.orderByTag("NOTOURS1")).as("not an OMS tag: nothing read").isEmpty();
        assertThat(sim.requests("status")).isEqualTo(1);
    }

    @Test
    void aFinishedOrdersTradesAreKeptOnlyWhenTheyAddUpToItsFill() {
        sim.depth(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65),
                new Quote.Level(100.05, 6500)), 99.8);
        gateway.place(buy("F41E1", 130, 100.10));                     // two fills of 65
        sim.hideLastTrade(1);                                         // Groww's trade list lags: one fill shown
        assertThat(gateway.trades()).hasSize(1);
        assertThat(gateway.trades()).as("the short list was not kept: read again").hasSize(2);
        int read = sim.requests("trades");
        assertThat(gateway.trades()).hasSize(2);
        assertThat(sim.requests("trades")).as("complete now: kept").isEqualTo(read);
    }

    @Test
    void aModifyAlwaysCarriesAQuantityAndAPartlyFilledOrderIsNeverResized() {
        gateway.place(raw("F51E1"));                                  // rests
        gateway.modify("F51E1", 0, 91, 0);                            // the exit chase's re-price: quantity 0
        assertThat(gateway.orderByTag("F51E1").orElseThrow().quantity()).as("the order's own quantity was sent")
                .isEqualTo(65);
        assertThat(sim.requests("modify")).isEqualTo(1);

        sim.partialFills(0.5);
        gateway.place(buy("F52E1", 260, 100.2));                      // 130 of 260 filled, the rest open
        assertThat(gateway.orderByTag("F52E1").orElseThrow().filledQuantity()).isEqualTo(130);
        assertThatThrownBy(() -> gateway.modify("F52E1", 390, 100.2, 0)).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.TERMINAL)).hasMessageContaining("cancel and re-place");
        assertThatThrownBy(() -> gateway.modify("F52E1", 130, 100.2, 0)).hasMessageContaining("cancel and re-place");
        assertThat(sim.requests("modify")).as("nothing sent").isEqualTo(1);
        gateway.modify("F52E1", 260, 99.0, 0);                        // its own quantity: a price change only
        gateway.modify("F52E1", 0, 99.5, 0);
        assertThat(sim.requests("modify")).isEqualTo(3);
    }

    @Test
    void anOrderFilledToItsQuantityIsFilledWhateverTheStatusWord() {
        assertThat(GrowwGateway.status("DELIVERY_AWAITED", 65, 65)).isEqualTo(OrderStatus.FILLED);
        assertThat(GrowwGateway.status("SOMETHING_NEW", 130, 130)).isEqualTo(OrderStatus.FILLED);
        assertThat(GrowwGateway.status("OPEN", 65, 130)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(GrowwGateway.status("NEW", 0, 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(GrowwGateway.knownStatus("delivery_awaited")).isTrue();
        assertThat(GrowwGateway.knownStatus("SOMETHING_NEW")).isFalse();
        assertThat(GrowwGateway.knownStatus(null)).isFalse();
    }

    @Test
    void aMissingOrUnknownSideIsNoSideNeverABuy() {
        assertThat(GrowwGateway.side("SELL")).isEqualTo(OrderSide.SELL);
        assertThat(GrowwGateway.side(" buy ")).isEqualTo(OrderSide.BUY);
        assertThat(GrowwGateway.side("")).isNull();
        assertThat(GrowwGateway.side("SHORT")).isNull();
        assertThat(GrowwGateway.side(null)).isNull();
    }

    @Test
    void positionsWhoseNetIsNotBuysLessSellsAreRefused() {
        gateway.place(buy("F61E1", 65, 100.2));
        assertThat(gateway.positions()).singleElement().satisfies(p -> assertThat(p.netQuantity()).isEqualTo(65));
        sim.positionsDisagree(65);
        assertThatThrownBy(() -> gateway.positions()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE))
                .hasMessageContaining("is not credit 65 less debit 0");
    }

    @Test
    void aLostSessionDuringTheLookUpOfAnUncertainPlaceIsUnknownNotUnsent() {
        sim.failNext("place", Fault.TIMEOUT_PROCESSED);               // the order is at Groww, the answer lost
        sim.failNext("status", Fault.TOKEN_EXPIRED);                  // and the look-up meets an expired session
        assertThatThrownBy(() -> gateway.place(buy("F71E1", 65, 100.2))).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.UNKNOWN);
            assertThat(((BrokerException) e.getCause()).kind()).isEqualTo(Kind.SESSION);
        });
        assertThat(sim.orderCount()).isEqualTo(1);
    }

    @Test
    void aConnectTimeoutNeverReachedGrowwAndOtherTimeoutsMayHave() {
        GrowwException connect = GrowwHttp.failure(new java.net.http.HttpConnectTimeoutException("connect"),
                Call.ORDER, "POST /v1/order/create", "api.groww.in");
        assertThat(connect.kind()).isEqualTo(Kind.RETRYABLE);
        assertThat(connect.mayHaveReached()).isFalse();
        GrowwException timeout = GrowwHttp.failure(new java.net.http.HttpTimeoutException("request"), Call.ORDER,
                "POST /v1/order/create", "api.groww.in");
        assertThat(timeout.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(timeout.mayHaveReached()).isTrue();
        assertThat(GrowwHttp.failure(new java.net.http.HttpTimeoutException("request"), Call.READ, "GET", "h").kind())
                .isEqualTo(Kind.RETRYABLE);
        assertThat(GrowwHttp.failure(new java.net.ConnectException("refused"), Call.ORDER, "POST", "h").mayHaveReached())
                .isFalse();
    }

    @Test
    void theTapHearsEveryAnswerButNeverAHeaderAndCannotBreakACall() {
        List<String> heard = new ArrayList<>();
        http.tap((call, body) -> heard.add(call + " " + body));
        gateway.place(raw("F81E1"));
        gateway.orders();
        assertThat(heard).anySatisfy(h -> assertThat(h).startsWith("POST /v1/order/create ").contains("groww_order_id"));
        assertThat(heard).anySatisfy(h -> assertThat(h).startsWith("GET /v1/order/list?segment=FNO&page=0")
                .contains("F81E1"));
        assertThat(heard).noneSatisfy(h -> assertThat(h).contains("any-token"));
        http.tap((call, body) -> {
            throw new IllegalStateException("a broken tap");
        });
        assertThat(gateway.orders()).hasSize(1);
        http.tap(null);
        assertThat(gateway.orders()).hasSize(1);
    }

    @Test
    void readsOfALocalSimulatorAreNotThrottledButGrowwsAre() {
        assertThat(new GrowwHttp(GrowwConfig.real()).throttlesReads()).isTrue();
        assertThat(http.throttlesReads()).isFalse();
        long start = System.nanoTime();
        for (int i = 0; i < 40; i++) {
            gateway.orders();
        }
        assertThat((System.nanoTime() - start) / 1e9).as("40 reads; Groww's 15 a second would take 2.6 s")
                .isLessThan(1.5);
    }

    @Test
    void aCallerWaitingForTheLimiterDoesNotHoldItsLock() throws Exception {
        GrowwHttp.Limiter limiter = new GrowwHttp.Limiter(1, 100, Duration.ofMinutes(1));
        limiter.acquire();
        Thread waiter = Thread.ofPlatform().daemon(true).start(limiter::acquire);   // waits about a second
        Thread.sleep(100);
        long start = System.nanoTime();
        synchronized (limiter) {
            assertThat((System.nanoTime() - start) / 1e6).as("the lock is free while the waiter sleeps")
                    .isLessThan(300);
        }
        waiter.join(3_000);
        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void closingTheSimulatorStopsItsThreads() throws Exception {
        GrowwSimulator other = new GrowwSimulator(0, 100);
        GrowwInstruments.load(new GrowwHttp(GrowwConfig.simulator(other.baseUri())));
        assertThat(other.isClosed()).isFalse();
        other.close();
        assertThat(other.isClosed()).isTrue();
    }
}
