package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import com.autotrade.broker.BrokerException;
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
 * The Kite LIVE pilot (user, 2 Nov 2026): a ZERODHA follower switched LIVE, on follower-risk v7 at one lot an entry,
 * sends its orders to "the real Kite" (here a local simulator standing for api.kite.trade) behind the {@link LiveGuard}:
 * the entry and its resting stop reach Kite; a buy over one lot, a third contract, a sell beyond what is held, a strategy
 * outside the file's live list, an engaged GLOBAL_LIVE switch, an account without its LIVE switch and a drill on another
 * day never send anything.
 */
@Testcontainers(disabledWithoutDocker = true)
class LiveIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    /**
     * The tests' one clock, today at 10:00 IST ({@code -Dexecution.it.time=HH:mm} for another time; the entries that
     * should reach Kite need one before the 15:10 last LIVE entry): subscriptions, decisions and the LIVE checks read it,
     * never the wall clock. Decisions made at the wall clock were STALE_SIGNAL against a fixed 10:00 check clock before
     * 09:59, and fell "before" a subscription stamped by the database container's clock, a few ms ahead of the JVM's,
     * so FanOut gave them no intent (6 Nov 2026).
     */
    private static final Clock CLOCK = Clock.fixed(DAY.atTime(LocalTime.parse(System.getProperty("execution.it.time",
            "10:00"))).atZone(IST).toInstant(), IST);
    private static final String V7 = "../config/risk/follower-risk.v7.yaml";
    private static final int LOT = 65;
    private static final String[] KEYS = {"NSE_FO|47168", "NSE_FO|47169", "NSE_FO|47170"};
    private static final String[] SYMBOLS = {"NIFTY 23100 PE", "NIFTY 23150 PE", "NIFTY 23200 PE"};
    private static final String[] KITE_SYMBOLS = {"NIFTY26X0323100PE", "NIFTY26X0323150PE", "NIFTY26X0323200PE"};
    private static final double[] STRIKES = {23100, 23150, 23200};

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

    /** Stands for api.kite.trade: in LIVE, orders do reach it. */
    private KiteSimulator kite;
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
        long snapshot = jdbc.queryForObject("insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, "
                + "instruments) values (?, 'UPSTOX:NSE-live.json.gz', 'live', 3) returning id", Long.class, DAY);
        for (int i = 0; i < KEYS.length; i++) {
            jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                    + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values "
                    + "(?, ?, ?, 'NSE_FO', 'NSE', 'PE', 'NIFTY', ?, ?, ?, ?, 0.05, 1755, true)", snapshot, KEYS[i],
                    KEYS[i].substring(7), SYMBOLS[i], DAY.plusDays(2), STRIKES[i], LOT);
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
        for (int i = 0; i < KEYS.length; i++) {
            kite.list(new KiteSimulator.Listing(Long.parseLong(KEYS[i].substring(7)), "NFO", KITE_SYMBOLS[i], "NIFTY",
                    DAY.plusDays(2).toString(), STRIKES[i], "PE", LOT, 0.05));
            kite.depth(KEYS[i], List.of(new Quote.Level(99.5, 65_000)), List.of(new Quote.Level(100.0, 65_000)), 99.8);
        }
        kite.funds(800_000);
        session.set(Optional.of(new BrokerSessions.Access("real-key", "day-token")));
    }

    @AfterEach
    void close() {
        kite.close();
    }

    /** A ZERODHA follower on v7 at one lot an entry, switched LIVE ({@code live}) or LIVE without its switch. */
    private BrokerAccount account(long group, boolean live, String... strategies) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long id = accounts.ensureFollowerAccount(tenant, owner, "KITE-L" + n, BrokerType.ZERODHA, TradeMode.DRY_RUN,
                200_000, EquityMode.FIXED, DAY, V7, 0);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', ?, 'file:kite-key', 'file:kite-secret') returning id",
                Long.class, tenant, "kite-app-" + n);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = 'LIVE', "
                + "live_trading_enabled = ? where id = ?", profile, live, id);
        new FanOut(dataSource).withClock(CLOCK).subscribe(group, id, List.of(strategies), 1.0);
        return accounts.byId(id).orElseThrow();
    }

    private long group() {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-L" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        return accounts.ensureCopyGroup(tenant, "LIVEPILOT-" + n, signal);
    }

    private long signalSession(long group) {
        return jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', ?, 'it', 'live-set', 'sha256:it', "
                + "'{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-" + group, DAY);
    }

    /** An ENTER of {@code strategy} in contract {@code i}, decided at {@link #CLOCK}, fanned out. */
    private void enter(long group, long sessionId, String strategy, int i) {
        int n = jdbc.queryForObject("select count(*) from exec.signal_decision where copy_group_id = ?", Integer.class,
                group) + 1;
        long token = 3_000_000_000L + Long.parseLong(KEYS[i].substring(7));
        new SignalDecisionStore(dataSource).insert(new SignalDecision(group, sessionId, DAY, CLOCK.instant(), strategy, 2,
                "NIFTY", SignalDecision.Action.ENTER, "PE", null, "CONFIRMED", "IT", token, KEYS[i], SYMBOLS[i], LOT, 100,
                260_000, 500_000, 0, group + ":" + sessionId + ":" + n,
                Map.of("strategy_lots", 40.0, "intended_lots", 40.0, "liquidity_lots", 1000.0, "stop_pct", 25.0)));
        new FanOut(dataSource).fanOut(group, DAY);
    }

    private BrokerConnections registry() {
        return new BrokerConnections(dataSource, 1e9,
                List.of(new ZerodhaDryRunAdapter(dataSource, 1e9, a -> session.get(), kite.baseUri())));
    }

    /** The account's follower over its LIVE route, with the waits shortened. */
    private FollowerSimulator follower(BrokerConnections registry, BrokerAccount account) {
        FollowerRisk risk = FollowerRisk.load(Path.of(V7));
        BrokerConnections.Route route = registry.route(account, risk);
        assertThat(route.venue()).as(route.description()).isNotNull();
        BrokerVenue venue = new BrokerVenue(dataSource, route.connection(), risk.orders().fast(), (k, u) -> 1755L, o -> { });
        return new FollowerSimulator(dataSource, costs, risk, venue, null).withClock(CLOCK);
    }

    private static String intentState(BrokerAccount a) {
        return jdbc.queryForObject("select i.state || coalesce('_' || i.reject_code, '') from exec.trade_intent i "
                + "where i.broker_account_id = ? order by i.id desc limit 1", String.class, a.id());
    }

    @Test
    void aLiveEntryFollowsItsSignalAtOnceOrNotAtAll() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        enter(group, signalSession(group), "ma-cross", 0);
        Instant decided = CLOCK.instant();                                          // decided 10:00, run at 10:02
        try (BrokerConnections registry = registry()) {
            follower(registry, a).withClock(Clock.fixed(decided.plusSeconds(120), IST)).run(a, group, DAY);
        }
        assertThat(intentState(a)).isEqualTo("REJECTED_STALE_SIGNAL");
        assertThat(kite.requests("place")).as("nothing sent").isZero();
    }

    @Test
    void noLiveEntryJustBeforeTheSquareOff() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        enter(group, signalSession(group), "ma-cross", 0);
        Instant at1512 = DAY.atTime(15, 12).atZone(IST).toInstant();
        Instant decided = CLOCK.instant();
        Instant clockAt = decided.isAfter(at1512) ? decided.plusSeconds(1)    // a clock set late in the day: the same day
                : at1512;                                                    // decided earlier: 15:12 today
        try (BrokerConnections registry = registry()) {
            follower(registry, a).withClock(Clock.fixed(clockAt, IST)).run(a, group, DAY);
        }
        assertThat(intentState(a)).isEqualTo("REJECTED_LATE_ENTRY");
        assertThat(kite.requests("place")).as("nothing sent").isZero();
    }

    @Test
    void aLiveEntryOfOneLotAndItsStopReachKite() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        enter(group, signalSession(group), "ma-cross", 0);
        try (BrokerConnections registry = registry()) {
            follower(registry, a).run(a, group, DAY);
        }
        assertThat(jdbc.queryForObject("select quantity_open from exec.position where broker_account_id = ?", Long.class,
                a.id())).as("one lot, whatever the signal's 40").isEqualTo((long) LOT);
        assertThat(kite.requests("place")).as("the entry and its resting stop were sent to Kite").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from exec.order_execution where broker_account_id = ? "
                + "and role = 'STOP'", Integer.class, a.id())).isEqualTo(1);
    }

    @Test
    void theGuardRefusesWhatThePilotForbidsAndNothingReachesKite() {
        FollowerRisk.LiveLimits limits = FollowerRisk.load(Path.of(V7)).live();
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        try (BrokerConnections registry = registry()) {
            var c = registry.route(a, FollowerRisk.load(Path.of(V7))).connection();
            assertThat(c).isInstanceOf(LiveGuard.class);
            assertThatThrownBy(() -> c.place(buy("A1", 0, 2L * LOT))).isInstanceOf(BrokerException.class)
                    .hasMessageContaining("over 1 lot");
            assertThatThrownBy(() -> c.place(sell("S1", 0, LOT))).isInstanceOf(BrokerException.class)
                    .hasMessageContaining("never short");
            assertThat(kite.requests("place")).isZero();
            c.place(buy("B1", 0, LOT));
            c.place(buy("B2", 1, LOT));
            assertThat(limits.maxOpenPositions()).isEqualTo(2);
            assertThatThrownBy(() -> c.place(buy("B3", 2, LOT))).isInstanceOf(BrokerException.class)
                    .hasMessageContaining("max 2 contracts");
            c.place(sell("X1", 0, LOT));                                // what is held may be sold
            assertThatThrownBy(() -> c.place(sell("X2", 0, LOT))).as("held, but already being sold")
                    .isInstanceOf(BrokerException.class).hasMessageContaining("never short");
            assertThat(kite.requests("place")).isEqualTo(3);
        }
    }

    @Test
    void aStrategyOutsideTheLiveListIsRefused() {
        long group = group();
        BrokerAccount a = account(group, true, "opening-drive-momentum");
        enter(group, signalSession(group), "opening-drive-momentum", 0);
        try (BrokerConnections registry = registry()) {
            follower(registry, a).run(a, group, DAY);
        }
        assertThat(intentState(a)).isEqualTo("REJECTED_LIVE_STRATEGY");
        assertThat(kite.requests("place")).isZero();
    }

    @Test
    void anEngagedGlobalLiveSwitchRefusesEntries() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        long kill = new KillSwitches(dataSource).engage(null, "GLOBAL_LIVE", null, null, null, "test", "it");
        try {
            enter(group, signalSession(group), "ma-cross", 0);
            try (BrokerConnections registry = registry()) {
                follower(registry, a).run(a, group, DAY);
            }
            assertThat(intentState(a)).isEqualTo("REJECTED_KILL_SWITCH");
            assertThat(kite.requests("place")).isZero();
        } finally {
            new KillSwitches(dataSource).release(kill, null, "it");
        }
    }

    @Test
    void withoutItsLiveSwitchALiveAccountHasNoRoute() {
        long group = group();
        BrokerAccount a = account(group, false, "ma-cross");
        try (BrokerConnections registry = registry()) {
            BrokerConnections.Route route = registry.route(a, FollowerRisk.load(Path.of(V7)));
            assertThat(route.venue()).isNull();
            assertThat(route.description()).contains("LIVE switch is off");
        }
        FollowerRisk v6 = FollowerRisk.load(Path.of("../config/risk/follower-risk.v6.yaml"));
        BrokerAccount on = account(group, true, "ma-cross");
        try (BrokerConnections registry = registry()) {
            assertThat(registry.route(on, v6).description()).contains("no live limits");
        }
        assertThat(kite.requests()).isZero();
    }

    @Test
    void aDrillOnAnotherDayNeverSendsALiveOrder() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        QuoteSource none = (d, u, t, at) -> Double.NaN;
        try (ExecutionLoop loop = new ExecutionLoop(dataSource, "owner", "LIVEPILOT-" + (seq - 1), "drill-l", costs, none,
                1e9, x -> session.get(), kite.baseUri())) {
            loop.tick(DAY.minusDays(7), 5_000);
            assertThat(loop.status()).filteredOn(s -> s.accountId() == a.id()).singleElement()
                    .satisfies(s -> assertThat(s.lastResult()).contains("only today's session"));
        }
        assertThat(kite.requests("place")).isZero();
    }

    private static OrderRequest buy(String tag, int i, long quantity) {
        return new OrderRequest(tag, 3_000_000_000L + Long.parseLong(KEYS[i].substring(7)), KEYS[i], SYMBOLS[i], "NFO",
                OrderSide.BUY, OrderType.LIMIT, quantity, 100.2, 0, tag);
    }

    private static OrderRequest sell(String tag, int i, long quantity) {
        return new OrderRequest(tag, 3_000_000_000L + Long.parseLong(KEYS[i].substring(7)), KEYS[i], SYMBOLS[i], "NFO",
                OrderSide.SELL, OrderType.LIMIT, quantity, 99.0, 0, tag);
    }
}
