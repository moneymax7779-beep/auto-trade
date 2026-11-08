package com.autotrade.kotak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.StringReader;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.autotrade.kotak.KotakHttp.Call;
import com.autotrade.kotak.KotakSimulator.Fault;

/**
 * The Kotak Neo adapter against the local Kotak simulator: the scrip master, orders with our tag, the books, the
 * session, and every broker fault of the Kite suite plus Kotak's own (wrong IP, IP not whitelisted, invalid session,
 * 1005).
 */
class KotakGatewayTest {

    private static final String KEY = "NSE_FO|47168";
    private static final String SENSEX = "BSE_FO|1125704";
    private static final String NIFTY_SYMBOL = "NIFTY25N1123100PE";
    private static final LocalDate NIFTY_EXPIRY = LocalDate.of(2026, 11, 11);
    private static final LocalDate SENSEX_EXPIRY = LocalDate.of(2026, 11, 13);

    private KotakSimulator sim;
    private KotakHttp http;
    private KotakGateway gateway;
    private KotakInstruments instruments;
    private final List<OrderUpdate> updates = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        sim = new KotakSimulator(0, "sim-consumer-key", 2_500);
        sim.list(new KotakSimulator.Listing(47168, "nse_fo", NIFTY_SYMBOL, "NIFTY", NIFTY_EXPIRY, 23100, "PE", 65, 0.05));
        sim.list(new KotakSimulator.Listing(1125704, "bse_fo", "SENSEX25N1374600PE", "SENSEX", SENSEX_EXPIRY, 74600, "PE",
                20, 0.05));
        sim.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
        KotakConfig config = KotakConfig.simulator(sim.baseUri());
        http = new KotakHttp(config);
        instruments = KotakInstruments.load(http);
        assertThat(instruments.size()).isEqualTo(2);
        http.session("any-token", "any-sid");
        gateway = new KotakGateway(config, http, instruments, sim::book);
        gateway.onUpdate(updates::add);
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private static OrderRequest buy(String tag, long quantity, double limit) {
        return new OrderRequest(tag, 3000047168L, KEY, "NIFTY 23100 PE 11 NOV 26", "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, quantity, limit, 0, tag);
    }

    private static OrderRequest stop(String tag, long quantity, double limit, double trigger) {
        return new OrderRequest(tag, 3000047168L, KEY, "N", "NSE_FO", OrderSide.SELL, OrderType.STOP_LIMIT, quantity, limit,
                trigger, tag);
    }

    private static Kind kind(Throwable e) {
        return ((BrokerException) e).kind();
    }

    @Test
    void onlyTheDaysSessionIsAcceptedAndItIsNeverInAMessage() {
        sim.strictSessions();
        assertThatThrownBy(() -> gateway.availableFunds()).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.SESSION);
            assertThat(e.getMessage()).doesNotContain("any-token").doesNotContain("any-sid");
        });
        KotakSimulator.Session s = sim.login();
        http.session(s.token(), s.sid());
        assertThat(gateway.availableFunds()).isEqualTo(1e9);
        assertThat(s.toString()).doesNotContain(s.token());
        sim.expireSession();                                            // HTTP 401, no stCode
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> {
            assertThat(kind(e)).isEqualTo(Kind.SESSION);
            assertThat(e.getMessage()).doesNotContain(s.token()).doesNotContain(s.sid());
        });
        KotakSimulator.Session next = sim.login();
        http.session(next.token(), next.sid());
        assertThat(gateway.orders()).as("an empty book (Not_Ok 'No Data') is no orders").isEmpty();
    }

    @Test
    void aMarketableLimitFillsAtTheAskAndPollReportsTheFillOnce() {
        gateway.place(buy("F1E1", 130, 100.20));
        assertThat(gateway.poll()).isEqualTo(1);
        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(u.filledQuantity()).isEqualTo(130);
            assertThat(u.lastFillPrice()).isEqualTo(100.0);
            assertThat(u.clientOrderId()).as("our tag comes back as GuiOrdId").isEqualTo("F1E1");
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
            assertThat(t.instrumentKey()).as("Kotak's trade book has no token: mapped by symbol").isEqualTo(KEY);
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
        assertThat(first.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(gateway.quote(KEY).asks()).containsExactly(new Quote.Level(101.0, 6500));

        KotakGateway noQuotes = new KotakGateway(KotakConfig.simulator(sim.baseUri()), http, instruments, null);
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

        sim.failNext("place", Fault.ERROR_1005);                      // Kotak's own error: unknown, looked up, not re-sent
        assertThatThrownBy(() -> gateway.place(buy("F4E2", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.UNKNOWN));
        assertThat(sim.orderCount()).isEqualTo(2);

        sim.failNext("place", Fault.HTTP_429);                        // dropped before processing: retried
        gateway.place(buy("F5E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(3);
        assertThat(gateway.orders()).extracting(BrokerOrder::clientTag).containsExactly("F2E1", "F3E1", "F5E1");

        // a restarted worker (a new gateway) places a tag Kotak already has: refused as a duplicate, adopted
        KotakGateway restarted = new KotakGateway(KotakConfig.simulator(sim.baseUri()), http, instruments, sim::book);
        restarted.place(buy("F5E1", 65, 100.2));
        assertThat(sim.orderCount()).isEqualTo(3);
        assertThatThrownBy(() -> restarted.cancel("F5E1")).as("it knows the order; filled, so Kotak refuses (1006)")
                .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void errorsAreClassified() {
        sim.failNext("place", Fault.TOKEN_EXPIRED);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION));
        sim.failNext("place", Fault.SESSION_INVALID);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.SESSION)).hasMessageContaining("1003");
        sim.failNext("orders", Fault.WRONG_IP);
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION))
                .hasMessageContaining("another IP").hasMessageContaining("log in again through the app page");
        sim.failNext("limits", Fault.IP_NOT_WHITELISTED);
        assertThatThrownBy(() -> gateway.availableFunds()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION))
                .hasMessageContaining("not whitelisted");
        sim.failNext("place", Fault.INPUT_ERROR);
        assertThatThrownBy(() -> gateway.place(buy("F6E1", 65, 100.2))).satisfies(e -> assertThat(kind(e))
                .isEqualTo(Kind.TERMINAL)).hasMessageContaining("circuit");
        assertThatThrownBy(() -> gateway.place(buy("F6-E1", 65, 100.2))).hasMessageContaining("alphanumerics");
        assertThatThrownBy(() -> gateway.place(new OrderRequest("F7E1", 1, "NSE_FO|1", "X", "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, 65, 10, 0, "F7E1"))).hasMessageContaining("not in Kotak's scrip master");
        assertThatThrownBy(() -> gateway.place(buy("F7E2", 64, 100.2))).as("not a whole lot: Kotak's 1009")
                .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.TERMINAL)).hasMessageContaining("1009");
        sim.failNext("place", Fault.REJECT_FUNDS);
        gateway.place(buy("F8E1", 65, 100.2));
        gateway.poll();
        assertThat(updates).last().satisfies(u -> {
            assertThat(u.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(u.message()).startsWith("RMS:Margin Exceeds");
        });
        sim.failNext("orders", Fault.HTTP_500);                       // a read: retryable, never unknown
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        sim.failNext("orders", Fault.ERROR_1005);
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        sim.expireSession();
        assertThatThrownBy(() -> gateway.orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
    }

    @Test
    void theErrorTableMapsKotaksCodes() {
        assertThat(KotakHttp.kind(200, 1003, Call.READ)).isEqualTo(Kind.SESSION);
        assertThat(KotakHttp.kind(403, 1037, Call.ORDER)).isEqualTo(Kind.SESSION);
        assertThat(KotakHttp.kind(200, 100008, Call.ORDER)).isEqualTo(Kind.SESSION);
        assertThat(KotakHttp.kind(401, 0, Call.READ)).isEqualTo(Kind.SESSION);
        assertThat(KotakHttp.kind(403, 0, Call.ORDER)).isEqualTo(Kind.SESSION);
        for (int terminal : new int[] {1004, 1006, 1007, 1009, 1021}) {
            assertThat(KotakHttp.kind(200, terminal, Call.ORDER)).as("stCode " + terminal).isEqualTo(Kind.TERMINAL);
        }
        assertThat(KotakHttp.kind(200, 1005, Call.ORDER)).isEqualTo(Kind.UNKNOWN);
        assertThat(KotakHttp.kind(200, 1005, Call.READ)).isEqualTo(Kind.RETRYABLE);
        assertThat(KotakHttp.kind(502, 0, Call.ORDER)).isEqualTo(Kind.UNKNOWN);
        assertThat(KotakHttp.kind(502, 0, Call.READ)).isEqualTo(Kind.RETRYABLE);
        assertThat(KotakHttp.kind(429, 0, Call.ORDER)).isEqualTo(Kind.RETRYABLE);
        assertThat(KotakHttp.kind(400, 0, Call.ORDER)).isEqualTo(Kind.TERMINAL);
    }

    @Test
    void anAnswerThatIsNotKotaksEnvelopeIsUnknownForAnOrderAndRetryableForARead() throws Exception {
        com.sun.net.httpserver.HttpServer proxy = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", ex -> {
            byte[] page = "<html>gateway</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, page.length);
            try (var out = ex.getResponseBody()) {
                out.write(page);
            }
        });
        proxy.start();
        try {
            KotakHttp h = new KotakHttp(KotakConfig.simulator(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort())));
            h.session("t", "s");
            assertThatThrownBy(() -> h.post(KotakHttp.PLACE, Map.of("qt", "65"), Call.ORDER))
                    .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.UNKNOWN));
            assertThatThrownBy(() -> h.get(KotakHttp.ORDERS, Call.READ))
                    .satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.RETRYABLE));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void jDataIsOneFormFieldOfStringsAndTheHeadersCarryTheSession() {
        Map<String, String> j = new java.util.LinkedHashMap<>();
        j.put("qt", "65");
        j.put("tp", "0");
        j.put("skip", null);
        String form = http.form(j);
        assertThat(form).startsWith("jData=").doesNotContain("skip");
        assertThat(java.net.URLDecoder.decode(form.substring(6), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"qt\":\"65\",\"tp\":\"0\"}");
        // the simulator refuses a call without neo-fin-key, Sid or Auth, and any jData value that is not a string
        KotakHttp anonymous = new KotakHttp(KotakConfig.simulator(sim.baseUri()));
        assertThatThrownBy(() -> new KotakGateway(KotakConfig.simulator(sim.baseUri()), anonymous, instruments, null)
                .orders()).satisfies(e -> assertThat(kind(e)).isEqualTo(Kind.SESSION));
        assertThat(KotakGateway.price(0, 0.05, null)).isEqualTo("0");
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
                .satisfies(e -> assertThat(kind(e)).as("1006: finished").isEqualTo(Kind.TERMINAL));
        assertThat(gateway.orderByTag("F11S1").orElseThrow().status()).as("the exit must not be sent: the stop sold")
                .isEqualTo(OrderStatus.FILLED);
        assertThatThrownBy(() -> gateway.modify("F11S1", 0, 70, 74)).hasMessageContaining("cannot be modified")
                .satisfies(e -> assertThat(kind(e)).as("1021").isEqualTo(Kind.TERMINAL));
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
        assertThat(KotakGateway.price(100.01, 0.05, OrderSide.BUY)).isEqualTo("100.05");
        assertThat(KotakGateway.price(100.04, 0.05, OrderSide.SELL)).isEqualTo("100.00");
        assertThat(KotakGateway.price(75.02, 0.05, null)).isEqualTo("75.00");
        gateway.place(buy("F14E1", 65, 90));                          // below the ask: rests
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.OPEN);
        sim.quote(KEY, 89.0, 6500, 95.0, 6500, 94);
        gateway.modify("F14E1", 0, 91, 0);                            // still below: Kotak shows "modified"
        assertThat(gateway.orderByTag("F14E1").orElseThrow()).satisfies(o -> {
            assertThat(o.rawStatus()).isEqualTo("modified");
            assertThat(o.status()).isEqualTo(OrderStatus.OPEN);
            assertThat(o.limitPrice()).isEqualTo(91.0);
            assertThat(o.quantity()).as("quantity 0 keeps the order's").isEqualTo(65);
        });
        gateway.modify("F14E1", 0, 95.5, 0);
        assertThat(gateway.orderByTag("F14E1").orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void statusesMapOntoOursAndAnUnknownOneIsNeverTerminal() {
        assertThat(KotakGateway.status("complete", 65)).isEqualTo(OrderStatus.FILLED);
        assertThat(KotakGateway.status("Rejected", 0)).isEqualTo(OrderStatus.REJECTED);
        assertThat(KotakGateway.status("cancelled", 65)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(KotakGateway.status("open", 0)).isEqualTo(OrderStatus.OPEN);
        assertThat(KotakGateway.status("open", 65)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(KotakGateway.status("modified", 65)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(KotakGateway.status("trigger pending", 0)).isEqualTo(OrderStatus.TRIGGER_PENDING);
        assertThat(KotakGateway.status("put order req received", 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(KotakGateway.status("validation pending", 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(KotakGateway.status("open pending", 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(KotakGateway.status("after market order req received", 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(KotakGateway.status("something new", 0)).isEqualTo(OrderStatus.PENDING);
        assertThat(KotakGateway.status("something new", 130)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(KotakGateway.status("something new", 130).isTerminal()).isFalse();
    }

    @Test
    void positionsAreTheConfiguredProductsOnly() {
        sim.holding(KEY, 650, 90, "NRML");                            // not ours: another product
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
    void theScripMasterMapsOurKeysToKotakContracts() throws Exception {
        List<KotakInstruments.Contract> nse = KotakInstruments.parse(new StringReader(sim.csv("nse_fo")));
        List<KotakInstruments.Contract> bse = KotakInstruments.parse(new StringReader(sim.csv("bse_fo")));
        KotakInstruments list = new KotakInstruments(new ArrayList<>(nse));
        bse.forEach(list::add);
        assertThat(list.byInstrumentKey(KEY)).hasValueSatisfying(c -> {
            assertThat(c.segment()).isEqualTo("nse_fo");
            assertThat(c.tradingSymbol()).isEqualTo(NIFTY_SYMBOL);
            assertThat(c.expiry()).as("nse_fo: seconds since 1980").isEqualTo(NIFTY_EXPIRY);
            assertThat(c.strike()).as("paise").isEqualTo(23100.0);
            assertThat(c.tickSize()).as("paise").isEqualTo(0.05);
            assertThat(c.type()).as("trimmed").isEqualTo("PE");
            assertThat(c.lotSize()).isEqualTo(65);
        });
        assertThat(list.byInstrumentKey(SENSEX)).hasValueSatisfying(c -> {
            assertThat(c.segment()).isEqualTo("bse_fo");
            assertThat(c.expiry()).as("bse_fo: epoch seconds").isEqualTo(SENSEX_EXPIRY);
            assertThat(c.lotSize()).isEqualTo(20);
        });
        assertThat(list.bySymbol("NSE_FO", NIFTY_SYMBOL)).isPresent();
        assertThat(list.byInstrumentKey("NSE_EQ|2885")).isEmpty();
        assertThat(KotakInstruments.instrumentKey("bse_fo", 1125704)).isEqualTo(SENSEX);
        assertThat(KotakInstruments.instrumentKey("nse_cm", 2885)).isEqualTo("NSE_CM|2885");   // never an option key
        assertThat(KotakInstruments.segment(KEY)).isEqualTo("nse_fo");
        // OpenAlgo's offset (30 minutes later) gives the same date for an expiry in trading hours
        long raw = KotakInstruments.rawExpiry("nse_fo", NIFTY_EXPIRY);
        assertThat(java.time.Instant.ofEpochSecond(raw + 315_513_000L).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .toLocalDate()).isEqualTo(NIFTY_EXPIRY);
        // stray header characters, quotes, other segments and futures are handled; a header without a column fails
        String odd = "\"pSymbol\" ;, pExchSeg,pTrdSymbol,lLotSize;;,lExpiryDate  ,dStrikePrice;,dTickSize ,pOptionType\n"
                + "35001,nse_fo,\"NIFTY25NOVFUT\",75,1447000000,-1,5,XX\n"
                + "35002,nse_cm,RELIANCE-EQ,1,0,0,5,\n"
                + "35003,nse_fo,NIFTY25N1123000CE,75,bad,2300000,5,CE\n"
                + "35004,nse_fo,NIFTY25N1123050CE,75,1447000000,2305000,5,CE\n";
        assertThat(KotakInstruments.parse(new StringReader(odd))).singleElement().satisfies(c -> {
            assertThat(c.exchangeToken()).isEqualTo(35004);
            assertThat(c.strike()).isEqualTo(23050.0);
        });
        assertThatThrownBy(() -> KotakInstruments.parse(new StringReader("pSymbol,pExchSeg\n1,nse_fo\n")))
                .hasMessageContaining("no column");
        assertThat(gateway.capabilities().tagMaxLength()).isEqualTo(20);
        assertThat(gateway.name()).isEqualTo("KOTAK_NEO");
    }

    @Test
    void theConfigTrustsOnlyKotaksDomainOrThisHost() {
        assertThat(KotakConfig.simulator(sim.baseUri()).isRealBroker()).isFalse();
        assertThat(KotakConfig.session("k", URI.create("https://e21.kotaksecurities.com")).isRealBroker()).isTrue();
        assertThatThrownBy(() -> KotakConfig.session("k", URI.create("http://e21.kotaksecurities.com")))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> KotakConfig.session("k", URI.create("https://evil.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KotakConfig.session("k", URI.create("https://kotaksecurities.com.evil.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KotakConfig("k", sim.baseUri(), "MIS", null, 10, null))
                .hasMessageContaining("1-9");
        assertThat(KotakHttp.trustedFile(URI.create("https://lapi.kotaksecurities.com/x/nse_fo.csv"))).isTrue();
        assertThat(KotakHttp.trustedFile(URI.create("https://cdn.example.com/nse_fo.csv"))).isFalse();
    }

    @Test
    void kotakTimestampsAndNumbersAreReadLeniently() {
        assertThat(KotakHttp.time("08-Nov-2026 09:15:02")).isEqualTo(java.time.Instant.parse("2026-11-08T03:45:02Z"));
        assertThat(KotakHttp.time("2026/11/08 09:15:02")).isEqualTo(java.time.Instant.parse("2026-11-08T03:45:02Z"));
        assertThat(KotakHttp.time("08-NOV-2026 09:15:02")).isNotNull();
        assertThat(KotakHttp.time("NA")).isNull();
        assertThat(KotakHttp.time("whenever")).isNull();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(KotakHttp.number(json.readTree("\" 1,234.50 \""), 0)).isEqualTo(1234.5);
        assertThat(KotakHttp.number(json.readTree("65"), 0)).isEqualTo(65);
        assertThat(KotakHttp.number(json.readTree("\"\""), -1)).isEqualTo(-1);
    }
}
