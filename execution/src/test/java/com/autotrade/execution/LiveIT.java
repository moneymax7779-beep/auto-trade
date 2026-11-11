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
    private static final String V11 = "../config/risk/follower-risk.v11.yaml";
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
        return account(group, live, V7, 0, strategies);
    }

    /** A ZERODHA follower on {@code riskFile} at {@code sizeStep} (v11: 100 = full size from the allocation). */
    private BrokerAccount account(long group, boolean live, String riskFile, double sizeStep, String... strategies) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long id = accounts.ensureFollowerAccount(tenant, owner, "KITE-L" + n, BrokerType.ZERODHA, TradeMode.DRY_RUN,
                200_000, EquityMode.FIXED, DAY, riskFile, sizeStep);
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
        FollowerRisk risk = FollowerRisk.load(Path.of(account.riskFile()));
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
    void aV11LiveEntryIsSizedFromTheAllocationAndItsStopCoversAllOfIt() {
        long group = group();
        BrokerAccount a = account(group, true, V11, 100, "ma-cross");
        enter(group, signalSession(group), "ma-cross", 0);
        try (BrokerConnections registry = registry()) {
            follower(registry, a).run(a, group, DAY);
        }
        // the signal's 40 lots on a 260,000 budget at 5 L reference capital; this account's 2 L: 104,000, 16 lots at 100
        long held = jdbc.queryForObject("select quantity_open from exec.position where broker_account_id = ?", Long.class,
                a.id());
        assertThat(held).as("the allocation's share of the signal, not one lot").isEqualTo(16L * LOT);
        assertThat(jdbc.queryForObject("select max(quantity_requested) from exec.order_execution where broker_account_id = ? "
                + "and role = 'STOP'", Long.class, a.id())).as("the broker stop covers the whole position").isEqualTo(held);
    }

    @Test
    void aV11DailyLossWithOpenPositionsSellsEverythingAndStopsEntriesForTheDay() {
        long group = group();
        BrokerAccount a = account(group, true, V11, 100, "ma-cross", "oi-burst-cross");
        jdbc.update("update acct.broker_account set daily_loss_limit = 10000 where id = ?", a.id());
        a = new AccountStore(dataSource).byId(a.id()).orElseThrow();
        long session = signalSession(group);
        enter(group, session, "ma-cross", 0);
        try (BrokerConnections registry = registry()) {
            FollowerSimulator follower = follower(registry, a);
            follower.run(a, group, DAY);
            long held = jdbc.queryForObject("select quantity_open from exec.position where broker_account_id = ?",
                    Long.class, a.id());
            assertThat(held).isEqualTo(16L * LOT);

            // the bid drops 9 a unit: 1,040 x 9 = 9,360 open loss, under the 10,000 limit: nothing happens
            kite.depth(KEYS[0], List.of(new Quote.Level(91.0, 65_000)), List.of(new Quote.Level(91.5, 65_000)), 91.2);
            follower.run(a, group, DAY);
            assertThat(jdbc.queryForObject("select status from exec.position where broker_account_id = ?", String.class,
                    a.id())).isEqualTo("OPEN");

            // 11 a unit: 11,440 open loss: past the limit, sold now (the next mark is 5 s later)
            kite.depth(KEYS[0], List.of(new Quote.Level(89.0, 65_000)), List.of(new Quote.Level(89.5, 65_000)), 89.2);
            FollowerSimulator later = follower.withClock(Clock.fixed(CLOCK.instant().plusSeconds(6), IST));
            later.run(a, group, DAY);
            assertThat(jdbc.queryForObject("select status || ' ' || quantity_open from exec.position "
                    + "where broker_account_id = ?", String.class, a.id())).isEqualTo("CLOSED 0");
            assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                    + "and action = 'DAILY_LOSS_STOP'", Integer.class, a.id())).isEqualTo(1);

            // a new entry the same day is refused, here and by a fresh follower (a restarted worker)
            enter(group, session, "oi-burst-cross", 1);
            later.run(a, group, DAY);
            assertThat(intentState(a)).isEqualTo("REJECTED_DAILY_LOSS");
        }
        try (BrokerConnections registry = registry()) {
            enter(group, signalSession(group), "ma-cross", 2);
            follower(registry, a).withClock(Clock.fixed(CLOCK.instant().plusSeconds(7), IST)).run(a, group, DAY);
            assertThat(intentState(a)).isEqualTo("REJECTED_DAILY_LOSS");
        }
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

    /** V32 (user 9 Nov 2026): the owner's choice decides what trades LIVE, within the risk file's list. */
    @Test
    void theOwnersChoiceOfLiveStrategiesRefusesADeselectedOne() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross", "oi-burst-cross");
        FollowerRisk risk = FollowerRisk.load(Path.of(V7));
        LiveStrategies live = new LiveStrategies(dataSource);
        assertThat(live.chosen(a.id())).isNull();
        assertThat(live.effective(a.id(), risk)).containsExactly("early-confirm-runner", "ma-cross", "oi-burst-cross");
        live.choose(a.id(), risk, List.of("oi-burst-cross"));                     // ma-cross deselected
        assertThat(live.effective(a.id(), risk)).containsExactly("oi-burst-cross");
        enter(group, signalSession(group), "ma-cross", 0);
        try (BrokerConnections registry = registry()) {
            follower(registry, a).run(a, group, DAY);
        }
        assertThat(intentState(a)).isEqualTo("REJECTED_LIVE_STRATEGY");
        assertThat(kite.requests("place")).isZero();
        // never wider than the file: a strategy it does not list cannot be chosen
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.choose(a.id(), risk, List.of("opening-drive")))
                .hasMessageContaining("not allowed LIVE by the risk file: [opening-drive]");
        live.choose(a.id(), risk, null);                                           // back to the defaults
        assertThat(live.chosen(a.id())).isNull();
    }

    /** V33: the owner's global list governs every account without its own list; a strategy left out stays PAPER. */
    @Test
    void theGlobalListRefusesAStrategyLeftOutOnEveryAccountWithoutItsOwnList() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross", "oi-burst-cross");
        BrokerAccount own = account(group, true, "ma-cross");
        FollowerRisk risk = FollowerRisk.load(Path.of(V7));
        LiveStrategies live = new LiveStrategies(dataSource);
        try {
            live.choose(own.id(), risk, List.of("ma-cross"));                       // keeps its own list
            live.chooseGlobal(List.of("oi-burst-cross"), risk.live().strategies(), "it");
            assertThat(live.source(a.id())).isEqualTo(LiveStrategies.Source.GLOBAL);
            assertThat(live.effective(a.id(), risk)).containsExactly("oi-burst-cross");
            assertThat(live.source(own.id())).isEqualTo(LiveStrategies.Source.ACCOUNT);
            // user 11 Nov 2026: the owner's PAPER caps the account's own list (ma-cross is not in the owner's LIVE list)
            assertThat(live.effective(own.id(), risk)).isEmpty();
            enter(group, signalSession(group), "ma-cross", 0);
            try (BrokerConnections registry = registry()) {
                follower(registry, own).run(own, group, DAY);
            }
            assertThat(intentState(own)).isEqualTo("REJECTED_LIVE_STRATEGY");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.choose(own.id(), risk, List.of("ma-cross")))
                    .hasMessageContaining("on PAPER only");
            enter(group, signalSession(group), "ma-cross", 0);
            try (BrokerConnections registry = registry()) {
                follower(registry, a).run(a, group, DAY);
            }
            assertThat(intentState(a)).isEqualTo("REJECTED_LIVE_STRATEGY");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.chooseGlobal(List.of("opening-drive"),
                    risk.live().strategies(), "it")).hasMessageContaining("not a LIVE strategy of any account's risk file");
            live.chooseGlobal(null, risk.live().strategies(), "it");
            assertThat(live.global()).isNull();
            assertThat(live.source(a.id())).isEqualTo(LiveStrategies.Source.DEFAULT);
        } finally {
            jdbc.update("delete from acct.live_strategy_policy");                 // other tests run without a global list
        }
    }

    /**
     * V35 (user 11 Nov 2026): a strategy the owner turned OFF opens nothing on a LIVE account, even from a signal that
     * reached the follower, whatever the account's lists say; it cannot be chosen LIVE while OFF.
     */
    @Test
    void aStrategyTurnedOffIsRefusedOnALiveAccount() {
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross", "oi-burst-cross");
        FollowerRisk risk = FollowerRisk.load(Path.of(V7));
        LiveStrategies live = new LiveStrategies(dataSource);
        try {
            live.choose(a.id(), risk, List.of("ma-cross", "oi-burst-cross"));
            live.chooseOff(List.of("ma-cross"), java.util.Set.of("ma-cross", "oi-burst-cross"), "it");
            assertThat(live.effective(a.id(), risk)).containsExactly("oi-burst-cross");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.choose(a.id(), risk, List.of("ma-cross")))
                    .hasMessageContaining("turned OFF for every account: [ma-cross]");
            enter(group, signalSession(group), "ma-cross", 0);
            try (BrokerConnections registry = registry()) {
                follower(registry, a).run(a, group, DAY);
            }
            assertThat(intentState(a)).isEqualTo("REJECTED_STRATEGY_OFF");
            assertThat(kite.requests("place")).isZero();
        } finally {
            jdbc.update("delete from acct.strategy_off");                         // other tests run with every strategy on
        }
    }

    /** follower-risk v9: every live-set strategy may be chosen; v7's three trade until the owner chooses. */
    @Test
    void riskV9OffersTheLiveSetAndDefaultsToV7sList() {
        FollowerRisk v9 = FollowerRisk.load(Path.of("../config/risk/follower-risk.v9.yaml"));
        FollowerRisk v7 = FollowerRisk.load(Path.of(V7));
        assertThat(v9.live().strategies()).containsExactlyInAnyOrder("early-confirm-runner", "expiry-trend-rider",
                "ma-cross", "oi-burst-cross", "wall-break-rider", "break-retest-runner");
        assertThat(v9.live().defaults()).isEqualTo(v7.live().strategies());
        assertThat(v7.live().defaults()).isEqualTo(v7.live().strategies());   // a file without defaults: all of its list
        assertThat(v9.live().maxLotsPerOrder()).isEqualTo(v7.live().maxLotsPerOrder());
        assertThat(v9.live().maxOpenPositions()).isEqualTo(v7.live().maxOpenPositions());
        assertThat(v9.maxOpenPositions()).isEqualTo(v7.maxOpenPositions());
        assertThat(v9.maxLotsPerUnderlying()).isEqualTo(v7.maxLotsPerUnderlying());
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

    @Test
    void aStopMissingFromOneLiveBookReadIsNotTakenAsCancelledAndNothingIsSoldBesideIt() throws Exception {
        // review 8 Nov 2026: a stop absent from one order-book read was marked CANCELLED, so the exit went out beside
        // it (refused by the guard), the stop was "re-armed" (refused) and the account was emergency-killed
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        long session = signalSession(group);
        enter(group, session, "ma-cross", 0);
        HidingStops book = new HidingStops(rawKite());
        FollowerSimulator f = liveFollower(new LiveGuard(book, FollowerRisk.load(Path.of(V7)).live(), k -> LOT));
        f.run(a, group, DAY);
        assertThat(kite.requests("place")).as("the entry and its stop").isEqualTo(2);
        exit(group, session, 0);
        book.hideOnRead = 3;            // the stop-fill and broker-holding reads see it, the exit's release does not
        f.run(a, group, DAY);
        assertThat(intentState(a)).as("left for the next turn").isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select status from exec.order_execution where broker_account_id = ? and role = "
                + "'STOP'", String.class, a.id())).isEqualTo("OPEN");
        assertThat(kite.requests("place")).as("no exit beside the stop").isEqualTo(2);
        assertThat(new KillSwitches(dataSource).engaged(a, "ma-cross", KEYS[0])).isNull();
        f.run(a, group, DAY);
        assertThat(intentState(a)).isEqualTo("EXECUTED");
        assertThat(jdbc.queryForObject("select status from exec.position where broker_account_id = ?", String.class,
                a.id())).isEqualTo("CLOSED");
        assertThat(kite.netQuantities()).containsEntry(KITE_SYMBOLS[0], 0L);
    }

    @Test
    void aHandSaleThenTheStrategysExitSendNoRefusedOrderAndKillNothingAndTheSaleIsBooked() throws Exception {
        // review 8 Nov 2026 (second review): after a hand sale in Kite the stop is cut; a strategy exit or a stop check
        // before the next reconciliation then wrote an order our guard refused (a REJECTED row, a Telegram alert) every
        // turn, and STOP_NOT_ARMED killed the account after 5 turns
        long group = group();
        BrokerAccount a = account(group, true, "ma-cross");
        long session = signalSession(group);
        enter(group, session, "ma-cross", 0);
        com.autotrade.zerodha.KiteGateway raw = rawKite();
        LiveGuard guard = new LiveGuard(raw, FollowerRisk.load(Path.of(V7)).live(), k -> LOT);
        FollowerSimulator f = liveFollower(guard);
        f.run(a, group, DAY);
        raw.place(new OrderRequest("HAND1", 3_000_000_000L + 47168, KEYS[0], SYMBOLS[0], "NFO", OrderSide.SELL,
                OrderType.LIMIT, LOT, 99.0, 0, "HAND1"));                 // sold by hand in Kite (no tag of ours)
        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(a, guard).matched()).isFalse();       // our stop is cut
        exit(group, session, 0);                                            // the strategy's exit, before the next check
        for (int i = 0; i < 6; i++) {
            f.run(a, group, DAY);
        }
        FollowerSimulator restarted = liveFollower(guard);                 // a redeploy: its stops are checked again
        for (int i = 0; i < 6; i++) {
            restarted.run(a, group, DAY);
        }
        assertThat(kite.requests("place")).as("the entry, its stop, the hand sale: nothing refused").isEqualTo(3);
        assertThat(jdbc.queryForList("select role || ':' || status from exec.order_execution where broker_account_id = ? "
                + "order by id", String.class, a.id())).containsExactly("ENTRY:FILLED", "STOP:CANCELLED");
        assertThat(new KillSwitches(dataSource).engaged(a, "ma-cross", KEYS[0])).isNull();
        assertThat(reconciler.check(a, guard).matched()).isTrue();         // the next check books the hand sale
        assertThat(jdbc.queryForObject("select status || ':' || exit_reason from exec.position where broker_account_id = ?",
                String.class, a.id())).isEqualTo("CLOSED:MANUAL");
        f.run(a, group, DAY);
        assertThat(intentState(a)).isEqualTo("SKIPPED_NOT_HELD");
        assertThat(new KillSwitches(dataSource).engaged(a, "ma-cross", KEYS[0])).isNull();
        assertThat(kite.netQuantities()).containsEntry(KITE_SYMBOLS[0], 0L);
    }

    /** A Kite gateway of its own to the simulator standing for Kite (below any guard). */
    private com.autotrade.zerodha.KiteGateway rawKite() throws java.io.IOException {
        com.autotrade.zerodha.KiteConfig config = new com.autotrade.zerodha.KiteConfig("real-key", kite.baseUri(),
                kite.baseUri(), "MIS", java.time.Duration.ofSeconds(2), 8, java.time.Duration.ofMillis(50));
        com.autotrade.zerodha.KiteHttp http = new com.autotrade.zerodha.KiteHttp(config);
        http.accessToken("day-token");
        return new com.autotrade.zerodha.KiteGateway(config, http, com.autotrade.zerodha.KiteInstruments.load(http));
    }

    /** A LIVE follower on v7 over {@code guard}, with the waits shortened. */
    private FollowerSimulator liveFollower(LiveGuard guard) {
        FollowerRisk risk = FollowerRisk.load(Path.of(V7));
        return new FollowerSimulator(dataSource, costs, risk, new BrokerVenue(dataSource, guard, risk.orders().fast(),
                (k, u) -> 1755L, o -> { }), null).withClock(CLOCK);
    }

    /** The strategy's EXIT of contract {@code i}, decided now, fanned out. */
    /**
     * An exit's decision time: now, but never before {@link #CLOCK}'s entries (a run before 10:00 IST would otherwise
     * decide the exit before the entry it closes; merge of the test-clock branch, 9 Nov 2026).
     */
    private static Instant exitAt() {
        Instant now = Instant.now();
        return now.isAfter(CLOCK.instant()) ? now : CLOCK.instant().plusSeconds(1);
    }

    private void exit(long group, long session, int i) {
        long token = 3_000_000_000L + Long.parseLong(KEYS[i].substring(7));
        int n = jdbc.queryForObject("select count(*) from exec.signal_decision where copy_group_id = ?", Integer.class,
                group) + 1;
        new SignalDecisionStore(dataSource).insert(new SignalDecision(group, session, DAY, exitAt(), "ma-cross", 2,
                "NIFTY", SignalDecision.Action.EXIT, "PE", null, "EXIT", "IT", token, KEYS[i], SYMBOLS[i], LOT, 100,
                Double.NaN, 500_000, 0, group + ":" + session + ":" + n, Map.of()));
        new FanOut(dataSource).fanOut(group, DAY);
    }

    /** Kite, except that one order-book read ({@code hideOnRead}: the n-th from now) leaves out the stop orders. */
    private static final class HidingStops implements com.autotrade.broker.BrokerConnection {
        private final com.autotrade.broker.BrokerConnection kite;
        int hideOnRead;

        HidingStops(com.autotrade.broker.BrokerConnection kite) {
            this.kite = kite;
        }

        @Override
        public java.util.List<com.autotrade.broker.BrokerOrder> orders() {
            java.util.List<com.autotrade.broker.BrokerOrder> book = kite.orders();
            if (hideOnRead > 0 && --hideOnRead == 0) {
                return book.stream().filter(o -> o.type() != OrderType.STOP_LIMIT).toList();
            }
            return book;
        }

        @Override
        public void place(OrderRequest r) {
            kite.place(r);
        }

        @Override
        public void modify(String tag, long quantity, double limit, double trigger) {
            kite.modify(tag, quantity, limit, trigger);
        }

        @Override
        public void cancel(String tag) {
            kite.cancel(tag);
        }

        @Override
        public String name() {
            return kite.name();
        }

        @Override
        public com.autotrade.broker.BrokerCapabilities capabilities() {
            return kite.capabilities();
        }

        @Override
        public void onUpdate(java.util.function.Consumer<com.autotrade.broker.OrderUpdate> listener) {
            kite.onUpdate(listener);
        }

        @Override
        public java.util.List<com.autotrade.broker.BrokerPosition> positions() {
            return kite.positions();
        }

        @Override
        public double availableFunds() {
            return kite.availableFunds();
        }

        @Override
        public Quote quote(String instrumentKey) {
            return kite.quote(instrumentKey);
        }

        @Override
        public java.util.List<com.autotrade.broker.BrokerTrade> trades() {
            return kite.trades();
        }

        @Override
        public int poll() {
            return kite.poll();
        }
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
