package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.autotrade.zerodha.KiteAuth;
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteGateway;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteInstruments;
import com.autotrade.zerodha.KiteSimulator;
import com.autotrade.zerodha.KiteSimulator.Fault;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * L2: a ZERODHA DRY_RUN follower through the whole broker path (FollowerSimulator, BrokerVenue, KiteGateway, HTTP, the
 * local Kite simulator) against the same day on a PAPER follower, and the failure matrix of live plan section 4:
 * unknown place outcome, partial entry fill, a stop that sells as its cancel arrives, a stop that sells while held, an
 * expired token, a refused stop, an exit that cannot fill, a restart mid-order, a manual trade, the kill switch, the
 * end-of-day ledger.
 */
@Testcontainers(disabledWithoutDocker = true)
class BrokerVenueIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private static final String KEY = "NSE_FO|47168";
    private static final String SYMBOL = "NIFTY 23100 PE 20 OCT 26";
    private static final int LOT = 65;
    private static final long FREEZE = 1755;                  // 27 lots
    private static final String V3 = "../config/risk/follower-risk.v3.yaml";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

    private KiteSimulator sim;
    private KiteGateway gateway;
    private KiteHttp http;
    /** What the next order's hook sets: the book at the order's price (as SimulatorMarket), unless a test overrides. */
    private final AtomicReference<double[]> bookOverride = new AtomicReference<>();

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
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @AfterEach
    void closeSim() {
        if (sim != null) {
            sim.close();
        }
    }

    private static Instant ist(String time) {
        return LocalDateTime.parse(DAY + "T" + time).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    /** One copy group with a ZERODHA DRY_RUN follower (and a PAPER twin when {@code paperTwin}) on opening-drive. */
    private record Setup(long group, long session, BrokerAccount broker, BrokerAccount paper) {
    }

    private Setup setup(boolean paperTwin) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-B" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "BROKER-" + n, signal);
        FanOut fanOut = new FanOut(dataSource);
        long broker = accounts.ensureFollowerAccount(tenant, owner, "KITE-" + n, BrokerType.ZERODHA, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, DAY, V3, 100);
        fanOut.subscribe(group, broker, List.of("opening-drive"), 1.0);
        Long paper = null;
        if (paperTwin) {
            paper = accounts.ensureFollowerAccount(tenant, owner, "PAPERTWIN-" + n, BrokerType.PAPER, TradeMode.PAPER,
                    500_000, EquityMode.FIXED, DAY, V3, 100);
            fanOut.subscribe(group, paper, List.of("opening-drive"), 1.0);
        }
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', ?, 'it', 'live-set', 'sha256:it', "
                + "'{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-B" + n, DAY);
        Long paperId = paper;
        return new Setup(group, session, account(broker), paperId == null ? null : account(paperId));
    }

    private static BrokerAccount account(long id) {
        return new AccountStore(dataSource).byId(id).orElseThrow();
    }

    private void decide(Setup s, String time, SignalDecision.Action action, double price) {
        int i = jdbc.queryForObject("select count(*) from exec.signal_decision where copy_group_id = ?", Integer.class,
                s.group()) + 1;
        Map<String, Double> context = action == SignalDecision.Action.EXIT ? Map.of()
                : Map.of("strategy_lots", 1.0, "intended_lots", 1.0, "liquidity_lots", 1000.0, "stop_pct", 25.0);
        new SignalDecisionStore(dataSource).insert(new SignalDecision(s.group(), s.session(), DAY, ist(time), "opening-drive", 2,
                "NIFTY", action, "PE", null, "CONFIRMED", action == SignalDecision.Action.EXIT ? "TARGET" : "IT", 3000047168L,
                KEY, SYMBOL, LOT, price, action == SignalDecision.Action.ENTER ? 260_000 : Double.NaN, 500_000, 0,
                s.group() + ":" + s.session() + ":" + i, context));
        new FanOut(dataSource).fanOut(s.group(), DAY);
    }

    /** A follower simulator for the broker account through a fresh Kite simulator (fast order rules). */
    private FollowerSimulator brokerFollower() throws Exception {
        sim = new KiteSimulator(0, "sim-api-key", "sim-secret", 1_500);
        sim.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26O2023100PE", "NIFTY", "2026-10-20", 23100, "PE", LOT, 0.05));
        KiteConfig config = KiteConfig.simulator(sim.baseUri());
        http = new KiteHttp(config);
        http.accessToken("dry-run");
        gateway = new KiteGateway(config, http, KiteInstruments.load(http));
        FollowerRisk risk = FollowerRisk.load(Path.of(V3));
        BrokerVenue venue = new BrokerVenue(dataSource, gateway, risk.orders().fast(), (k, u) -> FREEZE, o -> {
            sim.clock(o::at);
            double[] book = bookOverride.getAndSet(null);
            if (book != null) {
                sim.quote(KEY, book[0], (long) book[1], book[2], (long) book[3], book[4]);
            } else if (o.price() > 0) {
                sim.quote(KEY, o.price(), 1_000_000, o.price(), 1_000_000, o.price());
            }
        });
        return new FollowerSimulator(dataSource, costs, risk, venue, null);
    }

    private static FollowerSimulator paperFollower() {
        return new FollowerSimulator(dataSource, costs, FollowerRisk.load(Path.of(V3)), 1e9);
    }

    private static Map<String, Object> position(BrokerAccount a) {
        return jdbc.queryForMap("select quantity_open, average_entry, realised_gross, charges, status, exit_reason "
                + "from exec.position where broker_account_id = ? order by id desc limit 1", a.id());
    }

    private static List<Map<String, Object>> orders(BrokerAccount a) {
        return jdbc.queryForList("select role, client_tag, quantity_requested, quantity_filled, status, trigger_price "
                + "from exec.order_execution where broker_account_id = ? order by id", a.id());
    }

    private static String intentState(BrokerAccount a, String action) {
        return jdbc.queryForObject("select i.state || coalesce('_' || i.reject_code, '') from exec.trade_intent i "
                + "where i.broker_account_id = ? and i.action = ? order by i.id desc limit 1", String.class, a.id(), action);
    }

    // ------------------------------------------------------------------ the day, both ways

    @Test
    void aDryRunDayThroughTheKiteSimulatorEqualsThePaperFollowersDay() throws Exception {
        Setup s = setup(true);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        // 40 lots = 2,600 > the freeze quantity: two entry children; the stop rests for the whole quantity, in two
        List<Map<String, Object>> rows = orders(s.broker());
        assertThat(rows).filteredOn(r -> r.get("role").equals("ENTRY")).extracting(r -> r.get("quantity_filled"))
                .containsExactly(1755L, 845L);
        assertThat(rows).filteredOn(r -> r.get("role").equals("STOP")).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("OPEN");
            assertThat((Double) r.get("trigger_price")).isEqualTo(75.0);   // 25 % under the 100.00 fill
        });
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        kite.run(s.broker(), s.group(), DAY);
        paperFollower().run(s.paper(), s.group(), DAY);

        Map<String, Object> viaKite = position(s.broker()), viaPaper = position(s.paper());
        assertThat(viaKite.get("status")).isEqualTo("CLOSED");
        assertThat(viaKite.get("exit_reason")).isEqualTo("TARGET");
        assertThat((Double) viaKite.get("realised_gross")).isEqualTo(20.0 * 2600).isEqualTo(viaPaper.get("realised_gross"));
        // the same fills; the broker path's charges are per executed order (two entry and two exit children, against
        // one each on the PAPER follower), so they are higher by the per-order brokerage
        assertThat((Double) viaKite.get("charges")).isGreaterThan((Double) viaPaper.get("charges"));
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"));
        assertThat(sim.netQuantities()).as("flat at the broker: no short from a stop and an exit")
                .containsEntry("NIFTY26O2023100PE", 0L);

        BrokerReconciler.EndOfDay eod = new BrokerReconciler(dataSource, costs).endOfDay(s.broker(), gateway, DAY);
        assertThat(eod.differences()).isEmpty();
        assertThat(eod.ledger().difference()).isZero();
        assertThat(eod.ledger().trades()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ section 4 scenarios

    @Test
    void aPlaceWithAnUnknownOutcomeIsAdoptedNotDuplicated() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        sim.failNext("place", Fault.TIMEOUT_PROCESSED);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(2600L);
        assertThat(orders(s.broker()).stream().filter(r -> r.get("role").equals("ENTRY")).count()).isEqualTo(2);
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 2600L);
    }

    @Test
    void aPartialEntryFillIsThePositionAndItsStopCoversIt() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        sim.partialFills(0.5);
        kite.run(s.broker(), s.group(), DAY);
        long open = (Long) position(s.broker()).get("quantity_open");
        assertThat(open).isLessThan(2600L).isPositive();
        assertThat(open % LOT).isZero();
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("ENTRY"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"));
        long stopped = orders(s.broker()).stream().filter(r -> r.get("role").equals("STOP"))
                .mapToLong(r -> (Long) r.get("quantity_requested")).sum();
        assertThat(stopped).isEqualTo(open);
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", open);
    }

    @Test
    void aStopThatSellsAsItsCancelArrivesMeansNoExitIsSent() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        sim.quote(KEY, 74.0, 1_000_000, 74.5, 1_000_000, 76.0);        // above the trigger: still resting
        sim.fillStopOnCancel(true);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 74);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("SKIPPED_STOPPED_AT_BROKER");
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("CLOSED");
            assertThat(p.get("exit_reason")).isEqualTo("BROKER_STOP");
        });
        assertThat(orders(s.broker())).noneMatch(r -> r.get("role").equals("EXIT"));
        assertThat(sim.netQuantities()).as("never short").containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void aStopThatSellsWhileHeldIsBookedAndTheSignalsExitFindsNothing() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        sim.quote(KEY, 74.0, 1_000_000, 74.5, 1_000_000, 74.9);        // through the 75 trigger: both stops sell
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("CLOSED");
            assertThat(p.get("exit_reason")).isEqualTo("BROKER_STOP");
            assertThat((Double) p.get("realised_gross")).isEqualTo(-26.0 * 2600);
        });
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 70);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("SKIPPED_NOT_HELD");
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void anExpiredTokenRefusesEntriesAndKeepsTheExitForTheNextLogin() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        sim.expireToken();
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        FollowerSimulator.DayResult r = kite.run(s.broker(), s.group(), DAY);
        assertThat(r.reasons()).containsKey("PENDING_BROKER_SESSION");
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class,
                s.broker().id())).isEqualTo("TOKEN_EXPIRED");
        // the next morning's login: a new token, the account ACTIVE again; the exit goes out
        KiteAuth login = new KiteAuth(KiteConfig.simulator(sim.baseUri()), http);
        http.accessToken(login.exchange("req", "sim-secret").accessToken());
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void aRefusedStopExitsThePositionAtOnce() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        // one entry child (20 lots, under the freeze quantity), then the stop's place call is refused (price band)
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        sim.failNext("place", Fault.NONE);
        sim.failNext("place", Fault.INPUT_ERROR);
        kite.run(s.broker(), s.group(), DAY);
        Map<String, Object> p = position(s.broker());
        assertThat(p.get("status")).isEqualTo("CLOSED");
        assertThat(p.get("exit_reason")).isEqualTo("STOP_PLACEMENT_FAILED");
        assertThat(orders(s.broker())).extracting(r -> r.get("role") + ":" + r.get("status"))
                .containsExactly("ENTRY:FILLED", "STOP:REJECTED", "EMERGENCY_EXIT:FILLED");
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 0L);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).startsWith("ACCOUNT: STOP_PLACEMENT_FAILED");
        // a new entry is refused by the kill switch; it is audited
        decide(s, "11:00:00", SignalDecision.Action.ENTER, 100);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("REJECTED_KILL_SWITCH");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'KILL_ACCOUNT'", Integer.class, s.broker().id())).isEqualTo(1);
    }

    @Test
    void anExitThatCannotFillKeepsTheStopAndKillsTheAccount() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        bookOverride.set(new double[] {0, 0, 121, 650, 120});        // no bid at all
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("FAILED_EXIT_NOT_FILLED");
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("OPEN");
            assertThat(p.get("quantity_open")).isEqualTo(2600L);
        });
        long resting = orders(s.broker()).stream().filter(r -> r.get("role").equals("STOP") && r.get("status").equals("OPEN"))
                .mapToLong(r -> (Long) r.get("quantity_requested")).sum();
        assertThat(resting).as("re-armed for what is still held").isEqualTo(2600L);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).contains("EXIT_NOT_FILLED");
    }

    @Test
    void aRestartMidOrderResumesByTagAndAManualTradeIsAReconciliationDifference() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ?", Long.class,
                s.broker().id());
        // a worker died after placing the first child: its row says SUBMITTED, and the broker has the order
        sim.quote(KEY, 100, 1_000_000, 100, 1_000_000, 100);
        gateway.place(new OrderRequest("F" + intent + "E1N1", 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, 1755, 100.2, 0, "F" + intent + "E1N1"));
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                + "client_tag, attempt_no, order_side, order_type, quantity_requested, status) values (?, ?, ?, 'ZERODHA', "
                + "'ENTRY', ?, 1, 'BUY', 'LIMIT', 1755, 'SUBMITTED')", s.broker().tenantId(), intent, s.broker().id(),
                "F" + intent + "E1N1");
        FollowerSimulator restarted = kite;
        int before = sim.orderCount();
        restarted.run(s.broker(), s.group(), DAY);
        assertThat(sim.orderCount() - before).as("only the second child and the stops are new").isEqualTo(1 + 2);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(2600L);

        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(s.broker(), gateway).matched()).isTrue();
        gateway.place(new OrderRequest("MANUAL1", 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.BUY, OrderType.LIMIT, 65,
                100.2, 0, "MANUAL1"));
        BrokerReconciler.Check c = reconciler.check(s.broker(), gateway);
        assertThat(c.matched()).isFalse();
        assertThat(c.differences()).singleElement().asString().contains("ours 2600, broker 2665");
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).startsWith("ACCOUNT: RECONCILIATION");
    }

    @Test
    void killSwitchesOfEveryScopeRefuseEntriesNeverExits() throws Exception {
        Setup s = setup(false);
        KillSwitches kills = new KillSwitches(dataSource);
        long id = kills.engage(s.broker().tenantId(), "STRATEGY", null, "opening-drive", null, "drill", "it");
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("REJECTED_KILL_SWITCH");
        assertThat(kills.release(id, s.broker().tenantId(), "it")).isTrue();
        assertThat(kills.engaged(s.broker(), "opening-drive", KEY)).isNull();
        long instrument = kills.engage(null, "INSTRUMENT", null, null, KEY, "drill", "it");
        assertThat(kills.engaged(s.broker(), "other", KEY)).startsWith("INSTRUMENT");
        kills.release(instrument, null, "it");
        long global = kills.engage(null, "GLOBAL_LIVE", null, null, null, "drill", "it");
        assertThat(kills.engaged(s.broker(), "opening-drive", KEY)).as("GLOBAL_LIVE covers LIVE accounts only").isNull();
        kills.release(global, null, "it");
    }

    @Test
    void aRestartWithAnEmptyBrokerBookReArmsTheStopsAndTheExitStillGoesOut() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        brokerFollower().run(s.broker(), s.group(), DAY);
        sim.close();
        // a new process: a fresh simulator that holds the position (as BrokerConnections seeds it), but none of the
        // old stops; the old stop rows still say OPEN
        FollowerSimulator restarted = brokerFollower();
        sim.holding(KEY, 2600, 100);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        restarted.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"))
                .hasSize(4);                                             // two stale, two re-armed then taken off
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void aSessionLostWhileAnEntryWorksKeepsTheIntentAndResumesItByTagAfterTheLogin() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        sim.failNext("orders", Fault.TOKEN_EXPIRED);                  // placed, then the first order-book read fails
        FollowerSimulator.DayResult r = kite.run(s.broker(), s.group(), DAY);
        assertThat(r.reasons()).containsKey("PENDING_BROKER_SESSION");
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 1300L);
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        int before = sim.orderCount();
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
        assertThat(sim.orderCount() - before).as("no second entry: only the stop is new").isEqualTo(1);
    }

    @Test
    void anInstrumentKillSwitchOfOneTenantDoesNotReachAnother() {
        Setup s = setup(false);
        KillSwitches kills = new KillSwitches(dataSource);
        long other = new AccountStore(dataSource).ensureTenant("someone-else");
        long id = kills.engage(other, "INSTRUMENT", null, null, KEY, "their drill", "it");
        assertThat(kills.engaged(s.broker(), "opening-drive", KEY)).isNull();
        kills.release(id, other, "it");
        long mine = kills.engage(s.broker().tenantId(), "INSTRUMENT", null, null, KEY, "my drill", "it");
        assertThat(kills.engaged(s.broker(), "opening-drive", KEY)).startsWith("INSTRUMENT");
        kills.release(mine, s.broker().tenantId(), "it");
    }
}
