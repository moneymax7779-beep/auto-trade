package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autotrade.account.AccountRole;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteGateway;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteInstruments;
import com.autotrade.zerodha.KiteSimulator;

/**
 * The READ_LIVE recorder (16 Nov 2026, D2): opt-in by account, each distinct redacted answer once per endpoint and IST
 * day under {@code <root>/<broker>/<day>/<endpoint>-<seq>.json} with its index line, capped, read back after a restart;
 * the redaction rules; a saved answer served back to the broker's own gateway as a contract-test fixture; and the probe
 * handing its answers (and the route's other reads, off the caller's thread) to it.
 */
class ReadLiveRecorderTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T04:30:00Z"), IST);   // 10:00 IST
    private static final String DAY = "2026-11-18";

    private static ReadLiveRecorder recorder(Path root, ReadLiveRecorder.Limits limits) {
        return new ReadLiveRecorder(root, Set.of(21L), limits, CLOCK);
    }

    private static List<Path> files(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void redactionTakesOutCredentialsIdentityContactAndBankFieldsAndKeepsTheBook() {
        String orders = """
                {"status":"success","data":[{"order_id":"151220000000000","exchange_order_id":"1100000012345678",
                "placed_by":"AB1234","status":"OPEN","tradingsymbol":"NIFTY26N2023100PE","exchange":"NFO",
                "instrument_token":12074242,"transaction_type":"BUY","quantity":65,"price":49.75,"tag":"F0C261118100000",
                "order_timestamp":"2026-11-18 10:00:00","guid":"9876543210xyz"}]}""";
        String out = ReadLiveRedaction.redact(orders, List.of());
        assertThat(out).doesNotContain("AB1234").contains("151220000000000").contains("1100000012345678")
                .contains("NIFTY26N2023100PE").contains("F0C261118100000").contains("49.75").contains("\"OPEN\"")
                .contains("2026-11-18 10:00:00").contains("12074242").contains("9876543210xyz");

        String profile = """
                {"status":"success","data":{"user_id":"AB1234","user_name":"Ravi Kumar","user_shortname":"Ravi",
                "email":"ravi@example.com","phone":"+91 9876543210","pan":"ABCDE1234F","broker":"ZERODHA",
                "bank_accounts":[{"name":"HDFC","account":"50100012345678","ifsc":"HDFC0000001"}],
                "meta":{"demat_consent":"physical","note":"call 9876543210 or ravi@example.com, PAN ABCDE1234F"},
                "access_token":"tok-secret-1","products":["MIS","NRML"],"name":"Ravi Kumar"}}""";
        String p = ReadLiveRedaction.redact(profile, List.of("day-token-123"));
        assertThat(p).doesNotContain("AB1234").doesNotContain("Ravi").doesNotContain("ravi@example.com")
                .doesNotContain("9876543210").doesNotContain("ABCDE1234F").doesNotContain("50100012345678")
                .doesNotContain("HDFC").doesNotContain("tok-secret-1").doesNotContain("physical")
                .contains("ZERODHA").contains("MIS").contains("NRML");

        // Kotak: the account id and the session's own values go, the instrument's name beside its symbol stays
        String kotak = """
                {"stat":"Ok","data":[{"nOrdNo":"231118000012345","actId":"KT1234","usrId":"XY9","trdSym":"NIFTY26N2023100PE",
                "sym":"NIFTY","name":"NIFTY","tok":"47168","fldQty":"0","qty":"65","prc":"49.75","GuiOrdId":"F0C1",
                "rejRsn":"session day-token-123 expired","hsUpTm":"2026/11/18 10:00:00"}]}""";
        String k = ReadLiveRedaction.redact(kotak, List.of("day-token-123"));
        assertThat(k).doesNotContain("KT1234").doesNotContain("XY9").doesNotContain("day-token-123")
                .contains("231118000012345").contains("\"name\":\"NIFTY\"").contains("47168").contains("F0C1");

        // funds amounts stay; a body that is not JSON keeps its text without the address or the token
        assertThat(ReadLiveRedaction.redact("{\"data\":{\"equity\":{\"available_margin\":12345.5}}}", List.of()))
                .contains("12345.5");
        assertThat(ReadLiveRedaction.redact("oops: ravi@example.com day-token-123", List.of("day-token-123")))
                .isEqualTo("oops: <redacted> <redacted>");
        assertThat(ReadLiveProbe.redact("not json day-token-123", List.of("day-token-123")))
                .as("the probe's saves use the same rules").isEqualTo("not json <redacted>");
    }

    @Test
    void phoneNumbersGoByFieldNameWordAndByExactValueUnlessTheFieldIsAnOrderTradeOrPrice() {
        String body = """
                {"data":{"contact_no":"98765 43210","regMobNo":"9876543210","phNo":"+919876543210","mob":"9876543211",
                "mobile":9876543212,"contact":{"primary":"9876543213"},"contactNumber":"9876543214",
                "regNo":"9876543215","alt_no":9876543216,"ref_numbers":[9876543217,"9876543218"],"note_no":"7012345678",
                "order_id":"9123456789","exchange_order_id":9123456788,"nOrdNo":"9123456787","trade_id":"8123456789",
                "flId":"8123456788","exchange_timestamp":"9123456786","price":9123456785,"quantity":"9123456784",
                "instrument_token":9123456783,"seq":"5123456789","guid":"9876543299xyz"}}""";
        String out = ReadLiveRedaction.redact(body, List.of());
        for (String phone : List.of("98765 43210", "9876543210", "9876543211", "9876543212", "9876543213", "9876543214",
                "9876543215", "9876543216", "9876543217", "9876543218", "7012345678")) {
            assertThat(out).as(phone).doesNotContain("\"" + phone).doesNotContain(":" + phone);
        }
        assertThat(out).contains("\"9123456789\"").contains("9123456788").contains("\"9123456787\"")
                .contains("\"8123456789\"").contains("\"8123456788\"").contains("\"9123456786\"")
                .contains("9123456785").contains("\"9123456784\"").contains("9123456783")
                .as("not a mobile (starts with 5)").contains("\"5123456789\"")
                .as("not exactly ten digits").contains("9876543299xyz");
        assertThat(ReadLiveRedaction.secretField("regMobNo")).isTrue();
        assertThat(ReadLiveRedaction.secretField("phNo")).isTrue();
        assertThat(ReadLiveRedaction.secretField("contact_no")).isTrue();
        assertThat(ReadLiveRedaction.secretField("phase")).isFalse();
        assertThat(ReadLiveRedaction.secretField("exchange_order_id")).isFalse();
    }

    @Test
    void identifierSegmentsOfARequestPathAreTakenOutOfTheIndexAndTheFileName(@TempDir Path root) throws Exception {
        assertThat(ReadLiveRedaction.redactPath("/user/AB1234/orders", List.of())).isEqualTo("/user/<redacted>/orders");
        assertThat(ReadLiveRedaction.redactPath("/v1/clients/xy9z/holdings", List.of("xy9z")))
                .isEqualTo("/v1/clients/<redacted>/holdings");
        assertThat(ReadLiveRedaction.redactPath("/v2/portfolio/KT1234X/positions", List.of()))
                .as("a client code anywhere").isEqualTo("/v2/portfolio/<redacted>/positions");
        assertThat(ReadLiveRedaction.redactPath("/profile/9876543210", List.of())).isEqualTo("/profile/<redacted>");
        assertThat(ReadLiveRedaction.redactPath("/quick/user/orders", List.of())).isEqualTo("/quick/user/orders");
        assertThat(ReadLiveRedaction.redactPath("/orders/151220000000000/trades", List.of()))
                .as("an order id stays").isEqualTo("/orders/151220000000000/trades");
        assertThat(ReadLiveRedaction.redactPath("/v1/order/status/reference/F0C261118100000000", List.of()))
                .as("our own tag stays").isEqualTo("/v1/order/status/reference/F0C261118100000000");
        assertThat(ReadLiveRedaction.redactPath("/v2/order/retrieve-all", List.of())).isEqualTo("/v2/order/retrieve-all");

        ReadLiveRecorder r = recorder(root, ReadLiveRecorder.Limits.DEFAULT);
        Optional<Path> saved = r.record("ZERODHA", 21, "GET /user/AB1234/margins?client_id=AB1234&segment=equity",
                "{\"data\":{\"net\":5}}", List.of("day-token"));
        Path day = root.resolve("zerodha").resolve(DAY);
        assertThat(saved).isPresent();
        assertThat(saved.orElseThrow().getFileName().toString()).doesNotContainIgnoringCase("ab1234");
        String index = Files.readString(day.resolve(ReadLiveRecorder.INDEX));
        assertThat(index).doesNotContain("AB1234").contains("\"path\":\"/user/<redacted>/margins\"")
                .contains("segment=equity");
        // the client's own id handed in with the session's values: gone from a path where no rule would catch it
        r.record("KOTAK_NEO", 21, "GET /quick/clients/zq77/limits", "{\"x\":1}", List.of("zq77"));
        assertThat(Files.readString(root.resolve("kotak_neo").resolve(DAY).resolve(ReadLiveRecorder.INDEX)))
                .doesNotContain("zq77");
    }

    @Test
    void theProbesRecorderQueueIsBoundedAndDropsWhenFull(@TempDir Path root) throws Exception {
        ReadLiveRecorder r = recorder(root, new ReadLiveRecorder.Limits(1024, 1000, 1000, 10_000_000));
        ReadLiveProbe probe = new ReadLiveProbe(null, account(21), "GROWW", null, s -> true,
                new ReadLiveProbe.Settings(null, null, r), List::of);
        java.util.concurrent.CountDownLatch busy = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        probe.start(() -> {                                    // the route's thread busy (a slow warm-up, a slow disk)
            busy.countDown();
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(busy.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) {
                probe.tap().accept("GET /v1/order/list", "{\"n\":" + i + "}");
            }
            assertThat(probe.pending()).isEqualTo(ReadLiveProbe.MAX_PENDING);
            assertThat(probe.dropped()).isEqualTo(100 - ReadLiveProbe.MAX_PENDING);
            release.countDown();
            Path day = root.resolve("groww").resolve(DAY);
            for (int i = 0; i < 200 && probe.pending() > 0; i++) {
                Thread.sleep(20);
            }
            assertThat(probe.pending()).isZero();
            assertThat(files(day)).hasSize(ReadLiveProbe.MAX_PENDING);
            // room again: the next answer is recorded
            probe.tap().accept("GET /v1/order/list", "{\"n\":\"next\"}");
            for (int i = 0; i < 200 && files(day).size() <= ReadLiveProbe.MAX_PENDING; i++) {
                Thread.sleep(20);
            }
            assertThat(files(day)).hasSize(ReadLiveProbe.MAX_PENDING + 1);
        } finally {
            release.countDown();
            probe.close();
        }
    }

    @Test
    void savesEachDistinctAnswerOncePerEndpointAndDayOnlyForTheAccountsNamed(@TempDir Path root) throws Exception {
        ReadLiveRecorder r = recorder(root, ReadLiveRecorder.Limits.DEFAULT);
        String empty = "{\"stat\":\"Not_Ok\",\"errMsg\":\"No Data\"}";
        Optional<Path> first = r.record("KOTAK_NEO", 21, "GET /quick/user/orders?sId=sid-value-77", empty, List.of("sid-value-77"));
        Path day = root.resolve("kotak_neo").resolve(DAY);
        assertThat(first).contains(day.resolve("get-quick-user-orders-1.json"));
        assertThat(r.record("KOTAK_NEO", 21, "GET /quick/user/orders?sId=sid-value-77", empty, List.of("sid-value-77")))
                .as("the same answer is kept once").isEmpty();
        assertThat(r.record("KOTAK_NEO", 22, "GET /quick/user/orders", "{\"stat\":\"Ok\",\"data\":[]}", List.of()))
                .as("an account not named is never recorded").isEmpty();
        assertThat(r.record("KOTAK_NEO", 21, "GET /quick/user/orders", "{\"stat\":\"Ok\",\"data\":[{\"nOrdNo\":\"1\"}]}",
                List.of())).contains(day.resolve("get-quick-user-orders-2.json"));
        assertThat(r.record("KOTAK_NEO", 21, "GET /quick/user/positions", empty, List.of()))
                .contains(day.resolve("get-quick-user-positions-1.json"));
        assertThat(files(day)).hasSize(3);
        List<String> index = Files.readAllLines(day.resolve(ReadLiveRecorder.INDEX));
        assertThat(index).hasSize(3);
        assertThat(index.getFirst()).contains("\"file\":\"get-quick-user-orders-1.json\"")
                .contains("\"path\":\"/quick/user/orders\"").contains("\"account\":21").doesNotContain("sid-value-77");
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(day))).isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(first.orElseThrow())))
                    .isEqualTo("rw-------");
        }

        // a restart: the day's answers and numbers are read back from the folder
        ReadLiveRecorder again = recorder(root, ReadLiveRecorder.Limits.DEFAULT);
        assertThat(again.record("KOTAK_NEO", 21, "GET /quick/user/orders", empty, List.of())).isEmpty();
        assertThat(again.record("KOTAK_NEO", 21, "GET /quick/user/orders", "{\"stat\":\"Ok\",\"data\":[{\"nOrdNo\":\"2\"}]}",
                List.of())).contains(day.resolve("get-quick-user-orders-3.json"));
    }

    @Test
    void capsTheBodySizeTheFilesPerEndpointAndTheFilesPerDay(@TempDir Path root) throws Exception {
        ReadLiveRecorder r = recorder(root, new ReadLiveRecorder.Limits(60, 2, 3, 1_000_000));
        assertThat(r.record("UPSTOX", 21, "GET /v2/order/retrieve-all", "{\"data\":[\"" + "x".repeat(80) + "\"]}",
                List.of())).as("over the body cap").isEmpty();
        for (int i = 0; i < 3; i++) {
            r.record("UPSTOX", 21, "GET /v2/order/retrieve-all", "{\"data\":[" + i + "]}", List.of());
        }
        Path day = root.resolve("upstox").resolve(DAY);
        assertThat(files(day)).extracting(p -> p.getFileName().toString())
                .containsExactly("get-v2-order-retrieve_all-1.json", "get-v2-order-retrieve_all-2.json");
        assertThat(r.record("UPSTOX", 21, "GET /v2/portfolio/short-term-positions", "{\"data\":[]}", List.of()))
                .isPresent();
        assertThat(r.record("UPSTOX", 21, "GET /v3/user/get-funds-and-margin", "{\"data\":{}}", List.of()))
                .as("three files a day in all").isEmpty();
    }

    @Test
    void endpointsAreNamedByMethodAndPathWithIdsFolded() {
        assertThat(ReadLiveRecorder.endpoint("GET /orders")).isEqualTo("get-orders");
        assertThat(ReadLiveRecorder.endpoint("GET /quick/user/orders?sId=x")).isEqualTo("get-quick-user-orders");
        assertThat(ReadLiveRecorder.endpoint("GET /v1/order/status/reference/F12E1-00?segment=FNO"))
                .isEqualTo("get-v1-order-status-reference-id");
        assertThat(ReadLiveRecorder.endpoint("GET /orders/151220000000000/trades")).isEqualTo("get-orders-id-trades");
    }

    @Test
    void aSavedAnswerBecomesAFixtureTheBrokersOwnGatewayParses(@TempDir Path root, @TempDir Path fixtures)
            throws Exception {
        KiteSimulator kite = new KiteSimulator(0, "sim-api-key", "sim-secret", 1_500);
        try {
            kite.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26N2023100PE", "NIFTY",
                    LocalDate.now(IST).plusDays(2).toString(), 23100, "PE", 65, 0.05));
            kite.quote("NSE_FO|47168", 99.5, 6500, 100.0, 6500, 99.8);
            KiteConfig config = KiteConfig.simulator(kite.baseUri());
            KiteHttp http = new KiteHttp(config);
            KiteInstruments instruments = KiteInstruments.load(http);
            http.accessToken("day-token-123");
            KiteGateway gateway = new KiteGateway(config, http, instruments);
            gateway.place(new OrderRequest("F9E1", 3000047168L, "NSE_FO|47168", "NIFTY 23100 PE", "NSE_FO",
                    OrderSide.BUY, OrderType.LIMIT, 65, 49.75, 0, "F9E1"));
            ReadLiveRecorder r = recorder(root, ReadLiveRecorder.Limits.DEFAULT);
            http.tap((call, body) -> r.record("ZERODHA", 21, call, body, List.of("day-token-123")));
            List<BrokerOrder> real = gateway.orders();
            Path saved = root.resolve("zerodha").resolve(DAY).resolve("get-orders-1.json");
            assertThat(saved).exists();

            ReadLiveFixture f = ReadLiveFixture.load(saved);
            assertThat(f.broker()).isEqualTo("zerodha");
            assertThat(f.method()).isEqualTo("GET");
            assertThat(f.path()).isEqualTo("/orders");
            Path fixture = f.writeTo(fixtures, 1);
            assertThat(fixture).isEqualTo(fixtures.resolve("zerodha").resolve("get-orders-" + DAY + "-1.json"));
            ReadLiveFixture back = ReadLiveFixture.fromFixture(fixture);
            assertThat(back.json().path("data").get(0).path("tag").asString()).isEqualTo("F9E1");
            try (ReadLiveFixture.Served served = back.serve()) {
                KiteConfig recorded = KiteConfig.simulator(served.baseUri());
                KiteHttp h = new KiteHttp(recorded);
                h.accessToken("any");
                List<BrokerOrder> parsed = new KiteGateway(recorded, h, instruments).orders();
                assertThat(parsed).singleElement().satisfies(o -> {
                    assertThat(o.clientTag()).isEqualTo("F9E1");
                    assertThat(o.instrumentKey()).isEqualTo("NSE_FO|47168");
                    assertThat(o.rawStatus()).isEqualTo(real.getFirst().rawStatus());
                    assertThat(o.limitPrice()).isEqualTo(49.75);
                });
            }
        } finally {
            kite.close();
        }
    }

    @Test
    void theProbeHandsItsAnswersAndTheRoutesOtherReadsToTheRecorder(@TempDir Path root) throws Exception {
        ReadLiveRecorder r = recorder(root, ReadLiveRecorder.Limits.DEFAULT);
        java.util.concurrent.atomic.AtomicReference<java.util.function.BiConsumer<String, String>> tap =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        BrokerConnection reads = new BrokerConnection() {
            @Override public String name() { return "GROWW"; }
            @Override public BrokerCapabilities capabilities() { return null; }
            @Override public void onUpdate(java.util.function.Consumer<OrderUpdate> l) { }
            @Override public void place(OrderRequest q) { throw new AssertionError("never"); }
            @Override public void modify(String t, long q, double l, double tr) { throw new AssertionError("never"); }
            @Override public void cancel(String t) { throw new AssertionError("never"); }
            @Override public double availableFunds() { return 0; }
            @Override public Quote quote(String k) { return null; }
            @Override public int poll() { return 0; }

            @Override
            public List<BrokerOrder> orders() {
                tap.get().accept("GET /v1/order/list?segment=FNO&page=0", "{\"status\":\"SUCCESS\",\"payload\":"
                        + "{\"order_list\":[],\"n\":" + n.incrementAndGet() + "}}");
                return List.of();
            }

            @Override
            public List<BrokerTrade> trades() {
                return List.of();
            }

            @Override
            public List<BrokerPosition> positions() {
                return List.of();
            }
        };
        BrokerAccount recorded = account(21);
        ReadLiveProbe probe = new ReadLiveProbe(null, recorded, "GROWW", reads, s -> true,
                new ReadLiveProbe.Settings(null, null, r), () -> List.of("day-token-123"));
        tap.set(probe.tap());
        probe.probe();
        Path day = root.resolve("groww").resolve(DAY);
        assertThat(files(day)).extracting(p -> p.getFileName().toString()).containsExactly("get-v1-order-list-1.json");

        // a read outside the probe (a funds read before an entry) is recorded on the route's thread, not the caller's
        probe.start(() -> { });
        try {
            probe.tap().accept("GET /v1/margins/detail/user", "{\"status\":\"SUCCESS\",\"payload\":{\"clear_cash\":5,"
                    + "\"ucc\":\"G123\"}}");
            for (int i = 0; i < 100 && files(day).size() < 2; i++) {
                Thread.sleep(20);
            }
        } finally {
            probe.close();
        }
        assertThat(files(day)).extracting(p -> p.getFileName().toString())
                .contains("get-v1-margins-detail-user-1.json");
        assertThat(Files.readString(day.resolve("get-v1-margins-detail-user-1.json"))).doesNotContain("G123")
                .contains("clear_cash");

        // an account not named: nothing, though its answer is new
        int before = files(day).size();
        ReadLiveProbe other = new ReadLiveProbe(null, account(22), "GROWW", reads, s -> true,
                new ReadLiveProbe.Settings(null, null, r), List::of);
        tap.set(other.tap());
        other.probe();
        assertThat(n.get()).isEqualTo(2);
        assertThat(files(day)).hasSize(before);
    }

    private static BrokerAccount account(long id) {
        return new BrokerAccount(id, 1, 1, BrokerType.GROWW, "REC-" + id, null, AccountRole.FOLLOWER_TRADE,
                TradeMode.DRY_RUN, false, 100_000, EquityMode.FIXED, LocalDate.now(), "x", 0, true);
    }
}
