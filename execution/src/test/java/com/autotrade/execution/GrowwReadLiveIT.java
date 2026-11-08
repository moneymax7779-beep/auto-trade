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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.Quote;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.autotrade.groww.GrowwConfig;
import com.autotrade.groww.GrowwSimulator;
import com.autotrade.groww.GrowwSimulator.Fault;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * READ_LIVE for Groww (live adapters plan step C, built ahead of the real sessions): a GROWW DRY_RUN follower on
 * follower-risk v6 reads its "real" Groww (a second local simulator standing for api.groww.in and
 * growwapi-assets.groww.in) for funds and the day's instrument CSV, checked against our contract master; the book is our
 * own live capture (Groww's quote API is not called); orders go only to its own simulator. The real side never sees an
 * order call, and the public instrument CSV never sees the token; no session means no read; a 403 (static IP) or a
 * failed margins read refuses the entry; a contract whose Groww row disagrees with ours is blocked (this is what proves
 * or disproves the UNCONFIRMED exchange_token mapping); a stale capture sends nothing; and an account switched LIVE gets
 * no route at all, with zero requests to Groww.
 */
@Testcontainers(disabledWithoutDocker = true)
class GrowwReadLiveIT {

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

    /** Stands for Groww's hosts: only reads may reach it. */
    private GrowwSimulator groww;
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
        // our contract master: two NIFTY puts; the second's lot size (75) disagrees with Groww's CSV of today (65)
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
    void realGroww() throws Exception {
        groww = new GrowwSimulator(0, 1_500);
        LocalDate expiry = DAY.plusDays(2);
        groww.list(new GrowwSimulator.Listing(KEY, SYMBOL, "NIFTY", expiry, 23100, "PE", LOT, 0.05));
        groww.list(new GrowwSimulator.Listing(OTHER, OTHER_SYMBOL, "NIFTY", expiry, 23150, "PE", LOT, 0.05));
        groww.list(new GrowwSimulator.Listing(UNCHECKED, "NIFTY 23200 PE", "NIFTY", expiry, 23200, "PE", LOT, 0.05));
        groww.funds(800_000);
        groww.strictSessions();
        session.set(Optional.of(new BrokerSessions.Access("member-api-key", groww.login().accessToken())));
        jdbc.update("delete from md.option_tick");
    }

    @AfterEach
    void close() throws Exception {
        groww.close();
        if (dryRun != null && dryRun.resource() != null) {
            dryRun.resource().close();
        }
    }

    private record Setup(long group, long session, BrokerAccount broker) {
    }

    private Setup setup() {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("uc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-GR" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "GREADLIVE-" + n, signal);
        long broker = accounts.ensureFollowerAccount(tenant, owner, "GROWW-R" + n, BrokerType.GROWW, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, DAY, V6, 100);
        new FanOut(dataSource).subscribe(group, broker, List.of("opening-drive"), 1.0);
        // the test's clock and the database's differ by up to a second in Docker (LiveIT, 8 Nov 2026)
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?",
                group);
        long sessionId = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', ?, 'it', 'live-set', 'sha256:it', "
                + "'{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-GR" + n, DAY);
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
        GrowwDryRunAdapter adapter = new GrowwDryRunAdapter(dataSource, 1e9, a -> session.get(),
                GrowwConfig.at(groww.baseUri()));
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
        return groww.requests("place") + groww.requests("modify") + groww.requests("cancel");
    }

    @Test
    void theEntryFillsAgainstTheCapturedDepthAndNoOrderReachesGroww() throws Exception {
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
        // the simulator reports average_price to the paisa (UNCONFIRMED for Groww's average_fill_price): the first child's 100.075 is
        // booked as 100.08
        assertThat((Double) position.get("average_entry"))
                .isCloseTo((650 * 100.0 + 650 * 100.15 + 845 * 100.5) / 2145, within(0.005));
        assertThat(orderCalls()).as("no place, modify or cancel ever reaches Groww").isZero();
        assertThat(groww.requests("funds")).as("funds read from Groww").isPositive();
        assertThat(groww.requests("instruments")).as("one CSV, once a day").isEqualTo(1);
        assertThat(groww.tokensSentToAssets()).as("the instrument CSV is public: no token sent there").isZero();
        assertThat(dryRun.connection().availableFunds()).isCloseTo(800_000 - (650 * 100.0 + 650 * 100.15 + 845 * 100.5),
                within(0.01));
        assertThat(jdbc.queryForList("select role from exec.order_execution where broker_account_id = ? and role = 'STOP'",
                s.broker().id())).as("the protective stop rests in the simulator").isNotEmpty();
        GrowwSimulator own = (GrowwSimulator) dryRun.resource();
        assertThat(own.netQuantities()).as("listed in the simulator as Groww's checked CSV has it")
                .containsEntry(KEY, 2145L);
    }

    @Test
    void withoutTodaysLoginNothingIsReadAndTheEntryIsRefused() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        session.set(Optional.empty());
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("BROKER_SESSION");
        assertThat(groww.requests()).as("no session: Groww is not called at all").isZero();
    }

    @Test
    void anUnregisteredIpRefusesTheEntryAsALostSession() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        groww.failNext("funds", Fault.STATIC_IP);
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("BROKER_SESSION");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        assertThat(orderCalls()).isZero();
    }

    @Test
    void anOldTokenIsALostSessionAndTheNextLoginTakesEffectAtOnce() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        session.set(Optional.of(new BrokerSessions.Access("member-api-key", "yesterdays-token")));
        enter(s, KEY, SYMBOL);
        FollowerSimulator follower = follower(s.broker());
        follower.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("BROKER_SESSION");
        assertThat(orderCalls()).isZero();
        assertThat(groww.tokensSentToAssets()).isZero();
        // the member logs in again: the next read uses the new token at once (read on every call)
        session.set(Optional.of(new BrokerSessions.Access("member-api-key", groww.login().accessToken())));
        assertThat(dryRun.connection().availableFunds()).isEqualTo(800_000.0);
    }

    @Test
    void aFailedMarginsReadRefusesTheEntryAsFundsUnknown() throws Exception {
        Setup s = setup();
        capture(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        groww.failNext("funds", Fault.HTTP_500);                        // GA000 on a read: retryable
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("FUNDS_UNKNOWN");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        assertThat(orderCalls()).isZero();
    }

    @Test
    void aContractWhoseGrowwRowDisagreesWithOursIsBlocked() throws Exception {
        Setup s = setup();
        capture(OTHER, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8, 0);
        enter(s, OTHER, OTHER_SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).startsWith("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        assertThat(jdbc.queryForObject("select detail::text from exec.audit_log where broker_account_id = ? and action = "
                + "'INSTRUMENT_MAPPING_MISMATCH'", String.class, s.broker().id())).contains("lot 65 at Groww, 75 ours")
                .contains("GROWW");
        assertThat(orderCalls()).isZero();
    }

    @Test
    void aGrowwContractOurMasterDoesNotHaveIsNotTradable() throws Exception {
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
        assertThat(((GrowwSimulator) dryRun.resource()).orderCount()).as("no order without a quote of now").isZero();
        assertThat(orderCalls()).isZero();
    }

    @Test
    void aGrowwAccountSwitchedLiveGetsNoLiveRouteAndNothingIsSent() {
        assertThat(LiveBrokers.live(BrokerType.GROWW)).as("Groww is not in the LIVE set").isFalse();
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("uc-owner", "owner");
        long id = accounts.ensureFollowerAccount(tenant, owner, "GROWW-L" + (++seq), BrokerType.GROWW,
                TradeMode.DRY_RUN, 200_000, EquityMode.FIXED, DAY, V7, 0);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'GROWW', ?, 'store:groww-key', 'store:groww-secret') "
                + "returning id", Long.class, tenant, "groww-app-" + seq);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = 'LIVE', "
                + "live_trading_enabled = true where id = ?", profile, id);
        BrokerAccount live = accounts.byId(id).orElseThrow();
        FollowerRisk v7 = FollowerRisk.load(Path.of(V7));
        assertThat(v7.live()).as("a risk file with live limits: only the adapter can refuse").isNotNull();
        BrokerSessions sessions = a -> session.get();
        GrowwDryRunAdapter adapter = new GrowwDryRunAdapter(dataSource, 1e9, sessions, GrowwConfig.at(groww.baseUri()));
        assertThat(adapter.live(live, v7.live())).as("no LIVE Groww connection in this build").isNull();
        try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, List.of(
                new ZerodhaDryRunAdapter(dataSource, 1e9, sessions, groww.baseUri()),
                new KotakDryRunAdapter(dataSource, 1e9, sessions),
                new UpstoxDryRunAdapter(dataSource, 1e9, sessions,
                        com.autotrade.upstox.orders.UpstoxConfig.at(groww.baseUri())), adapter))) {
            BrokerConnections.Route route = registry.route(live, v7);
            assertThat(route.venue()).isNull();
            assertThat(route.connection()).isNull();
            assertThat(route.description()).isEqualTo("LIVE refused: no LIVE adapter for GROWW");
        }
        assertThat(groww.requests()).as("nothing reached Groww").isZero();
    }
}
