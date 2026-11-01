package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
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
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.Quote;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.autotrade.zerodha.KiteSimulator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Multi-broker plan step K1: a ZERODHA DRY_RUN follower on follower-risk v6 reads its "real" Kite (here a second local
 * simulator that stands for api.kite.trade) for funds, quotes with depth and the instrument list, and its orders go only
 * to its own simulator, filled against the real book of that moment. The real side never sees an order call; no session
 * means no read at all; a contract whose Kite listing disagrees with our contract master is blocked.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReadLiveIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    private static final String KEY = "NSE_FO|47168";
    private static final String SYMBOL = "NIFTY 23100 PE";
    private static final String OTHER = "NSE_FO|47169";
    private static final String OTHER_SYMBOL = "NIFTY 23150 PE";
    private static final int LOT = 65;
    private static final long FREEZE = 1755;                  // 27 lots
    private static final String V6 = "../config/risk/follower-risk.v6.yaml";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

    /** Stands for api.kite.trade: only reads may reach it. */
    private KiteSimulator kite;
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
        // our contract master: two NIFTY puts; the second's lot size (75) disagrees with Kite's (65)
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
    void realKite() throws Exception {
        kite = new KiteSimulator(0, "real-key", "real-secret", 1_500);
        String expiry = DAY.plusDays(2).toString();
        kite.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26X0323100PE", "NIFTY", expiry, 23100, "PE", LOT, 0.05));
        kite.list(new KiteSimulator.Listing(47169, "NFO", "NIFTY26X0323150PE", "NIFTY", expiry, 23150, "PE", LOT, 0.05));
        kite.funds(800_000);
        session.set(Optional.of(new BrokerSessions.Access("real-key", "day-token")));
    }

    @AfterEach
    void close() throws Exception {
        kite.close();
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
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-R" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "READLIVE-" + n, signal);
        long broker = accounts.ensureFollowerAccount(tenant, owner, "KITE-R" + n, BrokerType.ZERODHA, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, DAY, V6, 100);
        new FanOut(dataSource).subscribe(group, broker, List.of("opening-drive"), 1.0);
        long sessionId = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', ?, 'it', 'live-set', 'sha256:it', "
                + "'{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-R" + n, DAY);
        return new Setup(group, sessionId, new AccountStore(dataSource).byId(broker).orElseThrow());
    }

    /**
     * An opening-drive ENTER of 40 lots (Rs 2,60,000 at Rs 100) in {@code key}, decided now (a subscription made today
     * receives only decisions taken after it).
     */
    private void enter(Setup s, String key, String symbol) {
        int i = jdbc.queryForObject("select count(*) from exec.signal_decision where copy_group_id = ?", Integer.class,
                s.group()) + 1;
        long token = 3_000_000_000L + Long.parseLong(key.substring(key.indexOf('|') + 1));
        new SignalDecisionStore(dataSource).insert(new SignalDecision(s.group(), s.session(), DAY, Instant.now(),
                "opening-drive", 2, "NIFTY", SignalDecision.Action.ENTER, "PE", null, "CONFIRMED", "IT", token, key, symbol,
                LOT, 100, 260_000, 500_000, 0, s.group() + ":" + s.session() + ":" + i,
                Map.of("strategy_lots", 1.0, "intended_lots", 1.0, "liquidity_lots", 1000.0, "stop_pct", 25.0)));
        new FanOut(dataSource).fanOut(s.group(), DAY);
    }

    private FollowerSimulator follower(BrokerAccount account) throws Exception {
        FollowerRisk risk = FollowerRisk.load(Path.of(V6));
        assertThat(risk.orders().brokerMarket()).isTrue();
        ZerodhaDryRunAdapter adapter = new ZerodhaDryRunAdapter(dataSource, 1e9, a -> session.get(), kite.baseUri());
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
        return kite.requests("place") + kite.requests("modify") + kite.requests("cancel");
    }

    @Test
    void theEntryFillsAgainstKitesDepthAndNoOrderReachesKite() throws Exception {
        Setup s = setup();
        // the real book: 650 at 100.00, 650 at 100.15, then 100.50; the entry's limit is the ask + 4 ticks = 100.20
        kite.depth(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 650),
                new Quote.Level(100.15, 650), new Quote.Level(100.5, 65_000)), 99.8);
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);

        Map<String, Object> position = jdbc.queryForMap("select quantity_open, average_entry from exec.position "
                + "where broker_account_id = ?", s.broker().id());
        // the first child (1,755, limit 100.20) takes both levels within its limit, the rest is cancelled; the second
        // (845) finds them already taken by us (Kite's next quote still shows them: our fill never reached Kite), so it
        // is priced off 100.50 (+ 4 ticks) and fills there
        assertThat(position.get("quantity_open")).isEqualTo(2145L);
        assertThat((Double) position.get("average_entry"))
                .isCloseTo((650 * 100.0 + 650 * 100.15 + 845 * 100.5) / 2145, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(orderCalls()).as("no place, modify or cancel ever reaches Kite").isZero();
        assertThat(kite.requests("quote")).as("each child priced from Kite's book").isGreaterThanOrEqualTo(2);
        assertThat(kite.requests("margins")).as("funds read from Kite").isPositive();
        assertThat(jdbc.queryForList("select role, status from exec.order_execution where broker_account_id = ? "
                + "and role = 'STOP'", s.broker().id())).as("the protective stop rests in the simulator").isNotEmpty();
    }

    @Test
    void aRestingStopTriggersWhenKitesMarketReachesIt() throws Exception {
        Setup s = setup();
        kite.depth(KEY, List.of(new Quote.Level(99.5, 65_000)), List.of(new Quote.Level(100.0, 65_000)), 99.8);
        enter(s, KEY, SYMBOL);
        FollowerSimulator follower = follower(s.broker());
        follower.run(s.broker(), s.group(), DAY);
        assertThat(jdbc.queryForObject("select quantity_open from exec.position where broker_account_id = ?", Long.class,
                s.broker().id())).isEqualTo(2600L);
        // the real market falls through the stop (25 % under the 100.00 fill: trigger 75, limit 5 % under): last 72
        kite.depth(KEY, List.of(new Quote.Level(72.0, 65_000)), List.of(new Quote.Level(72.5, 65_000)), 72.0);
        Thread.sleep(ReadLiveConnection.STOP_REFRESH.toMillis() + 300);
        follower.run(s.broker(), s.group(), DAY);
        Map<String, Object> position = jdbc.queryForMap("select quantity_open, status from exec.position "
                + "where broker_account_id = ?", s.broker().id());
        assertThat(position.get("quantity_open")).isEqualTo(0L);
        assertThat(position.get("status")).isEqualTo("CLOSED");
        assertThat(orderCalls()).as("the stop rested and sold in the simulator only").isZero();
    }

    @Test
    void withoutTodaysLoginNothingIsReadAndTheEntryIsRefused() throws Exception {
        Setup s = setup();
        kite.depth(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8);
        session.set(Optional.empty());
        enter(s, KEY, SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).contains("BROKER_SESSION");
        assertThat(kite.requests()).as("no session: Kite is not called at all").isZero();
    }

    @Test
    void aContractWhoseKiteListingDisagreesWithOursIsBlocked() throws Exception {
        Setup s = setup();
        kite.depth(OTHER, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 65_000)), 99.8);
        enter(s, OTHER, OTHER_SYMBOL);
        follower(s.broker()).run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker())).startsWith("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        assertThat(jdbc.queryForObject("select detail::text from exec.audit_log where broker_account_id = ? and action = "
                + "'INSTRUMENT_MAPPING_MISMATCH'", String.class, s.broker().id())).contains("lot 65 at Kite, 75 ours");
        assertThat(orderCalls()).isZero();
    }

    @Test
    void theBrokerSideIsReadOnlyWhateverCallsIt() {
        com.autotrade.broker.BrokerConnection readOnly = ReadLiveConnection.readOnly(new KiteLess());
        assertThatThrownBy(() -> readOnly.place(new OrderRequest("T1", 1, KEY, SYMBOL, "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, LOT, 100, 0, "T1"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");
        assertThatThrownBy(() -> readOnly.modify("T1", 0, 101, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> readOnly.cancel("T1")).isInstanceOf(IllegalStateException.class);
        assertThat(readOnly.availableFunds()).isEqualTo(42.0);
    }

    @Test
    void ourOwnFillsAreTakenOffKitesNextQuote() {
        List<Quote.Level> asks = List.of(new Quote.Level(100.0, 650), new Quote.Level(100.15, 650), new Quote.Level(100.5, 650));
        assertThat(ZerodhaDryRunAdapter.less(asks, Map.of(100.0, 650L, 100.15, 130L)))
                .containsExactly(new Quote.Level(100.15, 520), new Quote.Level(100.5, 650));
        assertThat(BrokerSessionsAccess.redacted()).doesNotContain("day-token");
    }

    /** A stand-in connection whose reads answer and whose order calls would succeed (so only the wrapper can refuse). */
    private static final class KiteLess implements com.autotrade.broker.BrokerConnection {
        @Override public String name() { return "X"; }
        @Override public com.autotrade.broker.BrokerCapabilities capabilities() { return null; }
        @Override public void onUpdate(java.util.function.Consumer<com.autotrade.broker.OrderUpdate> l) { }
        @Override public void place(OrderRequest r) { }
        @Override public void modify(String id, long q, double l, double t) { }
        @Override public void cancel(String id) { }
        @Override public List<com.autotrade.broker.BrokerPosition> positions() { return List.of(); }
        @Override public double availableFunds() { return 42.0; }
        @Override public Quote quote(String k) { return null; }
        @Override public List<com.autotrade.broker.BrokerOrder> orders() { return List.of(); }
        @Override public List<com.autotrade.broker.BrokerTrade> trades() { return List.of(); }
        @Override public int poll() { return 0; }
    }

    private static final class BrokerSessionsAccess {
        static String redacted() {
            return new BrokerSessions.Access("real-key", "day-token").toString();
        }
    }
}
