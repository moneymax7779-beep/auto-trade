package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.Quote;
import com.autotrade.kotak.KotakConfig;
import com.autotrade.kotak.KotakGateway;
import com.autotrade.kotak.KotakHttp;
import com.autotrade.kotak.KotakInstruments;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.kotak.KotakSimulator;
import com.autotrade.kotak.KotakSimulator.Fault;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * READ_LIVE for Kotak Neo (live adapters plan step C, built ahead of the real sessions): a KOTAK_NEO DRY_RUN follower on
 * follower-risk v6 reads its "real" Kotak (a second local simulator standing for the account's base URL) for limits and
 * the scrip master, checked against our contract master; the book is our own live capture (Kotak's quote API is not
 * called); orders go only to its own simulator. The real side never sees an order call; no session means no read; a
 * contract whose Kotak row disagrees with ours is blocked; a stale capture sends nothing; and an account switched LIVE
 * gets no route at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class KotakReadLiveIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    private static final String KEY = "NSE_FO|47168";
    private static final String SYMBOL = "NIFTY 23100 PE";
    private static final String OTHER = "NSE_FO|47169";
    private static final String OTHER_SYMBOL = "NIFTY 23150 PE";
    private static final String UNCHECKED = "NSE_FO|47170";
    private static final int LOT = 65;
    private static final long FREEZE = 1755;
    private static final String V6 = "../config/risk/follower-risk.v6.yaml";
    private static final String V7 = "../config/risk/follower-risk.v7.yaml";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

    /** Stands for the account's Kotak base URL: only reads may reach it. */
    private KotakSimulator kotak;
    private BrokerAdapter.DryRun dryRun;
    private final AtomicReference<Optional<BrokerSessions.Access>> session = new AtomicReference<>();

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v2.yaml")));
        // our contract master: two NIFTY puts; the second's lot size (75) disagrees with Kotak's (65)
        long snapshot = jdbc.queryForObject("insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, "
                + "instruments) values (?, 'UPSTOX:NSE-it.json.gz', 'it', 2) returning id", Long.class, DAY);
        for (Object[] row : List.of(new Object[] {KEY, "47168", SYMBOL, 23100.0, LOT},
                new Object[] {OTHER, "47169", OTHER_SYMBOL, 23150.0, 75})) {
            jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                    + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values "
                    + "(?, ?, ?, 'NSE_FO', 'NSE', 'PE', 'NIFTY', ?, ?, ?, ?, 0.05, 1755, true)", snapshot, row[0], row[1],
                    row[2], DAY.plusDays(2), row[3], row[4]);
        }
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void realKotak() throws Exception {
        kotak = new KotakSimulator(0, "real-consumer-key", 1_500);
        LocalDate expiry = DAY.plusDays(2);
        kotak.list(new KotakSimulator.Listing(47168, "nse_fo", "NIFTY26X1023100PE", "NIFTY", expiry, 23100, "PE", LOT, 0.05));
        kotak.list(new KotakSimulator.Listing(47169, "nse_fo", "NIFTY26X1023150PE", "NIFTY", expiry, 23150, "PE", LOT, 0.05));
        kotak.list(new KotakSimulator.Listing(47170, "nse_fo", "NIFTY26X1023200PE", "NIFTY", expiry, 23200, "PE", LOT, 0.05));
        kotak.funds(800_000);
        session.set(Optional.of(new BrokerSessions.Access("real-consumer-key", "day-token",
                Map.of(KotakDryRunAdapter.SID, "day-sid", KotakDryRunAdapter.BASE_URL, kotak.baseUri().toString()))));
        jdbc.update("delete from md.option_tick");
    }

    @AfterEach
    void close() throws Exception {
        kotak.close();
        if (dryRun != null) {
            dryRun.closeAll();
        }
    }

    private record Setup(long group, long session, BrokerAccount broker) {
    }

    private Setup setup() {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-KR" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "KREADLIVE-" + n, signal);
        long broker = accounts.ensureFollowerAccount(tenant, owner, "KOTAK-R" + n, BrokerType.KOTAK_NEO, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, DAY, V6, 100);
        new FanOut(dataSource).subscribe(group, broker, List.of("opening-drive"), 1.0);
        // the test's clock and the database's differ by up to a second in Docker (LiveIT, 8 Nov 2026)
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?",
                group);
        long sessionId = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', ?, 'it', 'live-set', 'sha256:it', "
                + "'{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-KR" + n, DAY);
        return new Setup(group, sessionId, new AccountStore(dataSource).byId(broker).orElseThrow());
    }

    /** An opening-drive ENTER of 40 lots (Rs 2,60,000 at Rs 100) in {@code key}, decided now. */
    private void enter(Setup s, String key, String symbol) {
        int i = jdbc.queryForObject("select count(*) from exec.signal_decision where copy_group_id = ?", Integer.class,
                s.group()) + 1;
        new SignalDecisionStore(dataSource).insert(new SignalDecision(s.group(), s.session(), DAY, Instant.now(),
                "opening-drive", 2, "NIFTY", SignalDecision.Action.ENTER, "PE", null, "CONFIRMED", "IT",
                InstrumentKeys.token(key), key, symbol, LOT, 100, 260_000, 500_000, 0,
                s.group() + ":" + s.session() + ":" + i,
                Map.of("strategy_lots", 1.0, "intended_lots", 1.0, "liquidity_lots", 1000.0, "stop_pct", 25.0)));
        new FanOut(dataSource).fanOut(s.group(), DAY);
    }

    /** One tick of our live capture of {@code key}, {@code ageSeconds} old, with these levels (best first). */
    private static void capture(String key, List<Quote.Level> bids, List<Quote.Level> asks, double last, int ageSeconds) {
        Instant at = Instant.now().minusSeconds(ageSeconds);
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, "
                + "analytics_complete, depth_complete) values (0, ?, 'NIFTY', ?, 1, ?, ?, 23100, 'PE', ?, ?, ?, ?, ?, ?, ?, "
                + "false, true)", ps -> {
                    ps.setObject(1, DAY);
                    ps.setTimestamp(2, Timestamp.from(at));
                    ps.setLong(3, InstrumentKeys.token(key));
                    ps.setString(4, key);
                    ps.setDouble(5, last);
                    var c = ps.getConnection();
                    ps.setArray(6, c.createArrayOf("float8", bids.stream().map(Quote.Level::price).toArray()));
                    ps.setArray(7, c.createArrayOf("int8", bids.stream().map(Quote.Level::quantity).toArray()));
                    ps.setArray(8, c.createArrayOf("int4", bids.stream().map(l -> 1).toArray()));
                    ps.setArray(9, c.createArrayOf("float8", asks.stream().map(Quote.Level::price).toArray()));
                    ps.setArray(10, c.createArrayOf("int8", asks.stream().map(Quote.Level::quantity).toArray()));
                    ps.setArray(11, c.createArrayOf("int4", asks.stream().map(l -> 1).toArray()));
                });
    }

    private FollowerSimulator follower(BrokerAccount account) throws Exception {
        FollowerRisk risk = FollowerRisk.load(Path.of(V6));
        assertThat(risk.orders().brokerMarket()).isTrue();
        KotakDryRunAdapter adapter = new KotakDryRunAdapter(dataSource, 1e9, a -> session.get());
        dryRun = adapter.dryRun(account, risk.orders());
        BrokerVenue venue = new BrokerVenue(dataSource, dryRun.connection(), risk.orders().fast(), (k, u) -> FREEZE,
                dryRun.beforeOrder());
        return new FollowerSimulator(dataSource, costs, risk, venue, null);
    }

    private static String intentState(BrokerAccount a) {
        return jdbc.queryForObject("select i.state || coalesce('_' || i.reject_code, '') from exec.trade_intent i "
                + "where i.broker_account_id = ? order by i.id desc limit 1", String.class, a.id());
    }

    private int orderCalls() {
        return kotak.requests("place") + kotak.requests("modify") + kotak.requests("cancel");
    }

    @Test
    void theEntryFillsAgainstTheCapturedDepthAndNoOrderReachesKotak() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 650),
                new Quote.Level(100.15, 650), new Quote.Level(100.5, 65_000)), 99.8, 0);
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);

        Map<String, Object> position = jdbc.queryForMap("select quantity_open, average_entry from exec.position "
                + "where broker_account_id = ?", s.broker().id());
        // as for Kite: the first child takes both levels within its limit (100.20), the rest is cancelled; the second
        // finds them taken by us and is priced off 100.50
        assertThat(position.get("quantity_open")).isEqualTo(2145L);
        // the simulator reports avgPrc to the paisa, as Kotak's book shows prices (UNCONFIRMED for avgPrc): the first
        // child's 100.075 is booked as 100.08
        assertThat((Double) position.get("average_entry"))
                .isCloseTo((650 * 100.0 + 650 * 100.15 + 845 * 100.5) / 2145, within(0.005));
        assertThat(orderCalls()).as("no place, modify or cancel ever reaches Kotak").isZero();
        assertThat(kotak.requests("limits")).as("funds read from Kotak").isPositive();
        assertThat(kotak.requests("scrip")).as("the scrip master read once a day").isEqualTo(1);
        assertThat(kotak.requests("files")).as("nse_fo and bse_fo").isEqualTo(2);
        assertThat(dryRun.connection().availableFunds()).isCloseTo(800_000 - (650 * 100.0 + 650 * 100.15 + 845 * 100.5),
                within(0.01));
        assertThat(jdbc.queryForList("select role from exec.order_execution where broker_account_id = ? and role = 'STOP'",
                s.broker().id())).as("the protective stop rests in the simulator").isNotEmpty();
        KotakSimulator own = (KotakSimulator) dryRun.resource();
        assertThat(own.netQuantities()).as("listed in the simulator under Kotak's own trading symbol")
                .containsEntry("NIFTY26X1023100PE", 2145L);
    }

    @Test
    void withoutTodaysLoginNothingIsReadAndTheEntryIsRefused() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        session.set(Optional.empty());
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("BROKER_SESSION");
        assertThat(kotak.requests()).as("no session: Kotak is not called at all").isZero();
    }

    @Test
    void aSessionFromAnotherIpRefusesTheEntryAsALostSession() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        kotak.failNext("limits", Fault.WRONG_IP);
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("BROKER_SESSION");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        assertThat(orderCalls()).isZero();
    }

    @Test
    void aSessionWhoseBaseUrlIsNotKotaksIsNeverUsed() throws Exception {
        Setup s = setup();
        session.set(Optional.of(new BrokerSessions.Access("real-consumer-key", "day-token",
                Map.of(KotakDryRunAdapter.SID, "day-sid", KotakDryRunAdapter.BASE_URL, "http://evil.example.com"))));
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("BROKER_SESSION");
        assertThat(kotak.requests()).isZero();
    }

    @Test
    void aContractWhoseKotakRowDisagreesWithOursIsBlocked() throws Exception {
        Setup s = setup();
        capture(OTHER, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        enter(s, OTHER, OTHER_SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).startsWith("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        assertThat(jdbc.queryForObject("select detail::text from exec.audit_log where broker_account_id = ? and action = "
                + "'INSTRUMENT_MAPPING_MISMATCH'", String.class, s.broker().id())).contains("lot 65 at Kotak, 75 ours")
                .contains("KOTAK_NEO");
        assertThat(orderCalls()).isZero();
    }

    @Test
    void aKotakContractOurMasterDoesNotHaveIsNotTradable() throws Exception {
        Setup s = setup();
        capture(UNCHECKED, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        enter(s, UNCHECKED, "NIFTY 23200 PE");
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).startsWith("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        assertThat(orderCalls()).isZero();
    }

    @Test
    void aStaleCaptureIsNoQuoteAndSendsNothing() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8,
                (int) CapturedQuotes.MAX_AGE.toSeconds() + 20);
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).startsWith("REJECTED");
        assertThat(((KotakSimulator) dryRun.resource()).orderCount()).as("no order without a quote of now").isZero();
        assertThat(orderCalls()).isZero();
    }

    @Test
    void aKotakAccountSwitchedLiveGetsNoLiveRouteAndNothingIsSent() {
        assertThat(LiveBrokers.live(BrokerType.KOTAK_NEO)).as("Kotak is not in the LIVE set").isFalse();
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        long id = accounts.ensureFollowerAccount(tenant, owner, "KOTAK-L" + (++seq), BrokerType.KOTAK_NEO,
                TradeMode.DRY_RUN, 200_000, EquityMode.FIXED, DAY, V7, 0);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'KOTAK_NEO', ?, 'store:kotak-key', 'store:kotak-key') "
                + "returning id", Long.class, tenant, "kotak-app-" + seq);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = 'LIVE', "
                + "live_trading_enabled = true where id = ?", profile, id);
        BrokerAccount live = accounts.byId(id).orElseThrow();
        FollowerRisk v7 = FollowerRisk.load(Path.of(V7));
        assertThat(v7.live()).as("a risk file with live limits: only the adapter can refuse").isNotNull();
        BrokerSessions sessions = a -> session.get();
        try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, List.of(
                new ZerodhaDryRunAdapter(dataSource, 1e9, sessions, kotak.baseUri()),
                new KotakDryRunAdapter(dataSource, 1e9, sessions)))) {
            BrokerConnections.Route route = registry.route(live, v7);
            assertThat(route.venue()).isNull();
            assertThat(route.connection()).isNull();
            assertThat(route.description()).isEqualTo("LIVE refused: no LIVE adapter for KOTAK_NEO");
        }
        assertThat(kotak.requests()).as("nothing reached Kotak").isZero();
    }

    // ------------------------------------------------------------------ the READ_LIVE probe (code review 8 Nov 2026)

    /** The probe audit rows of an account, waited for (the probe runs on the route's own thread). */
    static List<tools.jackson.databind.JsonNode> probes(BrokerAccount a, int atLeast) throws InterruptedException {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        for (int i = 0; i < 150; i++) {
            List<String> rows = jdbc.queryForList("select detail::text from exec.audit_log where broker_account_id = ? "
                    + "and action = 'READ_LIVE_PROBE' order by id", String.class, a.id());
            if (rows.size() >= atLeast) {
                return rows.stream().map(json::readTree).toList();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no READ_LIVE_PROBE audit row for account " + a.id());
    }

    static List<String> savedBodies(Path dir) throws java.io.IOException {
        try (var files = java.nio.file.Files.walk(dir)) {
            return files.filter(java.nio.file.Files::isRegularFile).map(f -> {
                try {
                    return f.getFileName() + "\n" + java.nio.file.Files.readString(f);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
    }

    @Test
    void aReadLiveRouteProbesTheRealBooksAndSavesThemRedactedWithoutAnOrderCall(@TempDir Path dir) throws Exception {
        Setup s = setup();
        // the account's own day at Kotak (this simulator stands for it): one of our orders, filled
        KotakConfig config = KotakConfig.simulator(kotak.baseUri());
        try (KotakHttp h = new KotakHttp(config)) {
            h.session("day-token", "day-sid");
            KotakGateway own = new KotakGateway(config, h, new KotakInstruments(List.of(kotak.byKey(KEY).contract())),
                    kotak::book);
            kotak.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
            own.place(new OrderRequest("F9E1", 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.BUY, OrderType.LIMIT, 65,
                    100.2, 0, "F9E1"));
        }
        int before = orderCalls();
        KotakDryRunAdapter adapter = new KotakDryRunAdapter(dataSource, 1e9, a -> session.get(),
                new CapturedQuotes(dataSource), new ReadLiveProbe.Settings(java.time.Duration.ofHours(1), dir));
        dryRun = adapter.dryRun(s.broker(), FollowerRisk.load(Path.of(V6)).orders());
        var detail = probes(s.broker(), 1).getFirst();
        assertThat(detail.path("broker").asString()).isEqualTo("KOTAK_NEO");
        assertThat(detail.path("orders").asInt()).isEqualTo(1);
        assertThat(detail.path("trades").asInt()).isEqualTo(1);
        assertThat(detail.path("positions").asInt()).isEqualTo(1);
        assertThat(detail.path("clean").asBoolean()).as(detail.toString()).isTrue();
        assertThat(kotak.requests("orders")).as("the real book was read").isPositive();
        assertThat(kotak.requests("trades")).isPositive();
        assertThat(kotak.requests("positions")).isPositive();
        assertThat(orderCalls()).as("the probe never places, modifies or cancels").isEqualTo(before);
        assertThat(kotak.requests("scrip")).as("the scrip master warmed up once, off the order path").isEqualTo(1);
        List<String> saved = savedBodies(dir);
        assertThat(saved).as("the answers kept for contract tests").hasSizeGreaterThanOrEqualTo(3)
                .anyMatch(b -> b.contains("nOrdNo") && b.contains("F9E1"))
                .allMatch(b -> !b.contains("day-token") && !b.contains("day-sid") && !b.contains("real-consumer-key"));
        try (var files = java.nio.file.Files.walk(dir)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).map(Path::toString))
                    .allMatch(f -> f.contains("/kotak_neo/" + s.broker().id() + "/" + DAY + "/"));
        }
    }

    @Test
    void theProbeCountsWhatDoesNotParseAuditsItRedactsAndCanNeverSendAnOrder(@TempDir Path dir) throws Exception {
        Setup s = setup();
        List<String> orderCalls = new java.util.ArrayList<>();
        AtomicReference<java.util.function.BiConsumer<String, String>> tap = new AtomicReference<>();
        BrokerConnection real = new BrokerConnection() {
            @Override public String name() { return "KOTAK_NEO"; }
            @Override public com.autotrade.broker.BrokerCapabilities capabilities() { return null; }
            @Override public void onUpdate(java.util.function.Consumer<com.autotrade.broker.OrderUpdate> l) { }
            @Override public void place(OrderRequest r) { orderCalls.add("place"); }
            @Override public void modify(String t, long q, double l, double tr) { orderCalls.add("modify"); }
            @Override public void cancel(String t) { orderCalls.add("cancel"); }
            @Override public double availableFunds() { return 0; }
            @Override public Quote quote(String k) { return null; }
            @Override public int poll() { orderCalls.add("poll"); return 0; }

            @Override
            public List<BrokerOrder> orders() {
                tap.get().accept("GET /quick/user/orders", "{\"stat\":\"Ok\",\"data\":[{\"nOrdNo\":\"1\",\"Auth\":\"x\","
                        + "\"sid\":\"s-1\",\"actId\":\"KT1234\",\"note\":\"leaked day-token-123\",\"tok\":\"47168\"}]}");
                return List.of(new BrokerOrder(null, "1", null, "X", null, OrderType.LIMIT, 65, 70, 100, 100, 0,
                        OrderStatus.OPEN, "weird new status", null, null),
                        new BrokerOrder("F1E1", "2", KEY, SYMBOL, OrderSide.BUY, OrderType.LIMIT, 65, 65, 100, 100, 0,
                                OrderStatus.FILLED, "complete", null, Instant.now()));
            }

            @Override
            public List<BrokerTrade> trades() {
                throw new BrokerException(BrokerException.Kind.RETRYABLE, "trade book down");
            }

            @Override
            public List<BrokerPosition> positions() {
                return List.of(new BrokerPosition(0, "RELIANCE", -1, 0, null, 0, 1, Double.NaN, Double.NaN));
            }
        };
        ReadLiveProbe probe = new ReadLiveProbe(dataSource, s.broker(), "KOTAK_NEO", real, KotakGateway::knownStatus,
                new ReadLiveProbe.Settings(java.time.Duration.ofHours(1), dir), () -> List.of("day-token-123"));
        tap.set(probe.tap());
        ReadLiveProbe.Summary sum = probe.probe();
        assertThat(sum.orders()).isEqualTo(2);
        assertThat(sum.unknownStatuses()).containsExactly("weird new status");
        assertThat(sum.missingKey()).as("the order and the position").isEqualTo(2);
        assertThat(sum.missingSide()).isEqualTo(1);
        assertThat(sum.negativeQuantity()).as("70 filled of 65").isEqualTo(1);
        assertThat(sum.missingTime()).isEqualTo(1);
        assertThat(sum.errors()).containsOnlyKeys("trades");
        assertThat(sum.clean()).isFalse();
        assertThat(sum.saved()).isEqualTo(1);
        assertThat(probes(s.broker(), 1).getFirst().path("unknownStatuses").get(0).asString())
                .isEqualTo("weird new status");
        assertThat(savedBodies(dir)).singleElement().satisfies(b -> assertThat(b).contains("nOrdNo").contains("47168")
                .doesNotContain("day-token-123").doesNotContain("s-1").doesNotContain("KT1234").doesNotContain("\"x\""));
        assertThat(probe.probe().saved()).as("the same answer is kept once").isZero();

        // on its own thread: at once and then every interval, stopped by close; a probe that throws changes nothing
        probe.start(() -> {
            throw new IllegalStateException("warm-up failed");
        });
        probes(s.broker(), 3);
        assertThat(probe.running()).isTrue();
        probe.close();
        assertThat(probe.running()).isFalse();
        assertThat(orderCalls).as("read-only: no order call can reach the real broker").isEmpty();
        assertThat(ReadLiveProbe.redact("not json day-token-123", List.of("day-token-123"))).isEqualTo("not json <redacted>");
    }

    @Test
    void aCapturedQuoteNeedsNoKotakSessionAndTheScripMasterIsLoadedOncePerWorker() throws Exception {
        Setup s = setup();
        Setup other = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        KotakDryRunAdapter adapter = new KotakDryRunAdapter(dataSource, 1e9, a -> session.get());
        OrderRules rules = FollowerRisk.load(Path.of(V6)).orders();
        dryRun = adapter.dryRun(s.broker(), rules);
        assertThat(dryRun.connection().quote(KEY).ask()).isEqualTo(100.0);   // listed from the checked scrip master
        BrokerAdapter.DryRun second = adapter.dryRun(other.broker(), rules);
        try {
            assertThat(second.connection().quote(KEY).ask()).isEqualTo(100.0);
            assertThat(kotak.requests("scrip")).as("one download for the worker's accounts").isEqualTo(1);
            assertThat(kotak.requests("files")).isEqualTo(2);
        } finally {
            second.closeAll();
        }
        // the session ends: our own capture still quotes the contract (an exit is never priced blind for want of a login)
        session.set(Optional.empty());
        int calls = kotak.requests();
        assertThat(dryRun.connection().quote(KEY).bid()).isEqualTo(99.5);
        assertThat(kotak.requests()).as("no Kotak call for a quote").isEqualTo(calls);
    }
}
