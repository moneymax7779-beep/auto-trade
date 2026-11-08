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
import com.autotrade.broker.BrokerCapabilities;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderType;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.kotak.KotakConfig;
import com.autotrade.kotak.KotakGateway;
import com.autotrade.kotak.KotakHttp;
import com.autotrade.kotak.KotakInstruments;
import com.autotrade.kotak.KotakSimulator;
import com.autotrade.kotak.KotakSimulator.Fault;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Step B1's gate: a KOTAK_NEO DRY_RUN follower through the whole broker path (FollowerSimulator, BrokerVenue,
 * KotakGateway, HTTP, the local Kotak simulator), against a PAPER follower on the same day, and the essential cases of
 * {@link BrokerVenueIT}'s failure matrix: an unknown place outcome adopted not duplicated, a partial entry and its stop,
 * a stop that sells as its cancel arrives, an expired session (and Kotak's wrong-IP refusal), an exit retried, a restart
 * resumed by tag, a restart with an empty broker book, a stop cancel that shows late, a refused stop, a hand sale booked
 * as MANUAL, a stop modify the adapter refuses (replaced by cancel and place); and the adapter's own STORED route.
 */
@Testcontainers(disabledWithoutDocker = true)
class KotakVenueIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private static final String KEY = "NSE_FO|47168";
    private static final String SYMBOL = "NIFTY 23100 PE 20 OCT 26";
    private static final String KOTAK_SYMBOL = "NIFTY26O2023100PE";
    private static final int LOT = 65;
    private static final long FREEZE = 1755;                  // 27 lots
    private static final String V3 = "../config/risk/follower-risk.v3.yaml";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

    private KotakSimulator sim;
    private KotakGateway gateway;
    private KotakHttp http;
    private BrokerAdapter.DryRun dryRun;
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
    void closeSim() throws Exception {
        if (sim != null) {
            sim.close();
        }
        if (dryRun != null) {
            dryRun.closeAll();
        }
    }

    private static Instant ist(String time) {
        return LocalDateTime.parse(DAY + "T" + time).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private record Setup(long group, long session, BrokerAccount broker, BrokerAccount paper) {
    }

    private Setup setup(boolean paperTwin) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("kc-owner", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-K" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "KOTAK-" + n, signal);
        FanOut fanOut = new FanOut(dataSource);
        long broker = accounts.ensureFollowerAccount(tenant, owner, "KOTAK-" + n, BrokerType.KOTAK_NEO, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, DAY, V3, 100);
        fanOut.subscribe(group, broker, List.of("opening-drive"), 1.0);
        Long paper = null;
        if (paperTwin) {
            paper = accounts.ensureFollowerAccount(tenant, owner, "KPAPERTWIN-" + n, BrokerType.PAPER, TradeMode.PAPER,
                    500_000, EquityMode.FIXED, DAY, V3, 100);
            fanOut.subscribe(group, paper, List.of("opening-drive"), 1.0);
        }
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', ?, 'it', 'live-set', 'sha256:it', "
                + "'{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-K" + n, DAY);
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
        new SignalDecisionStore(dataSource).insert(new SignalDecision(s.group(), s.session(), DAY, ist(time), "opening-drive",
                2, "NIFTY", action, "PE", null, "CONFIRMED", action == SignalDecision.Action.EXIT ? "TARGET" : "IT",
                3000047168L, KEY, SYMBOL, LOT, price, action == SignalDecision.Action.ENTER ? 260_000 : Double.NaN, 500_000,
                0, s.group() + ":" + s.session() + ":" + i, context));
        new FanOut(dataSource).fanOut(s.group(), DAY);
    }

    /** A follower for the broker account through a fresh Kotak simulator (fast order rules). */
    private FollowerSimulator brokerFollower() throws Exception {
        sim = new KotakSimulator(0, "sim-consumer-key", 1_500);
        sim.list(new KotakSimulator.Listing(47168, "nse_fo", KOTAK_SYMBOL, "NIFTY", LocalDate.of(2026, 10, 20), 23100, "PE",
                LOT, 0.05));
        KotakConfig config = KotakConfig.simulator(sim.baseUri());
        http = new KotakHttp(config);
        KotakInstruments instruments = KotakInstruments.load(http);
        http.session("dry-run", "dry-run");
        gateway = new KotakGateway(config, http, instruments, sim::book);
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

    @Test
    void aDryRunDayThroughTheKotakSimulatorEqualsThePaperFollowersDay() throws Exception {
        Setup s = setup(true);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        kotak.run(s.broker(), s.group(), DAY);
        List<Map<String, Object>> rows = orders(s.broker());
        assertThat(rows).filteredOn(r -> r.get("role").equals("ENTRY")).extracting(r -> r.get("quantity_filled"))
                .containsExactly(1755L, 845L);
        assertThat(rows).filteredOn(r -> r.get("role").equals("STOP")).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("OPEN");
            assertThat((Double) r.get("trigger_price")).isEqualTo(75.0);
        });
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        kotak.run(s.broker(), s.group(), DAY);
        paperFollower().run(s.paper(), s.group(), DAY);

        Map<String, Object> viaKotak = position(s.broker()), viaPaper = position(s.paper());
        assertThat(viaKotak.get("status")).isEqualTo("CLOSED");
        assertThat(viaKotak.get("exit_reason")).isEqualTo("TARGET");
        assertThat((Double) viaKotak.get("realised_gross")).isEqualTo(20.0 * 2600).isEqualTo(viaPaper.get("realised_gross"));
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"));
        assertThat(sim.netQuantities()).as("flat at the broker: no short from a stop and an exit")
                .containsEntry(KOTAK_SYMBOL, 0L);

        // the end-of-day ledger from Kotak's books (trades without tokens, positions as strings) matches ours
        BrokerReconciler.EndOfDay eod = new BrokerReconciler(dataSource, costs).endOfDay(s.broker(), gateway, DAY);
        assertThat(eod.differences()).isEmpty();
        assertThat(eod.ledger().difference()).isZero();
        assertThat(eod.ledger().trades()).isEqualTo(1);
        assertThat(sim.requests("place")).as("two entries, two stops, two exits").isEqualTo(6);
    }

    @Test
    void aPlaceWithAnUnknownOutcomeIsAdoptedNotDuplicated() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        sim.failNext("place", Fault.TIMEOUT_PROCESSED);
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(2600L);
        assertThat(orders(s.broker()).stream().filter(r -> r.get("role").equals("ENTRY")).count()).isEqualTo(2);
        assertThat(sim.netQuantities()).containsEntry(KOTAK_SYMBOL, 2600L);
    }

    @Test
    void aPartialEntryFillIsThePositionAndItsStopCoversIt() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        sim.partialFills(0.5);
        kotak.run(s.broker(), s.group(), DAY);
        long open = (Long) position(s.broker()).get("quantity_open");
        assertThat(open).isLessThan(2600L).isPositive();
        assertThat(open % LOT).isZero();
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("ENTRY"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"));
        long stopped = orders(s.broker()).stream().filter(r -> r.get("role").equals("STOP"))
                .mapToLong(r -> (Long) r.get("quantity_requested")).sum();
        assertThat(stopped).isEqualTo(open);
        assertThat(sim.netQuantities()).containsEntry(KOTAK_SYMBOL, open);
    }

    @Test
    void aStopThatSellsAsItsCancelArrivesMeansNoExitIsSent() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        kotak.run(s.broker(), s.group(), DAY);
        sim.quote(KEY, 74.0, 1_000_000, 74.5, 1_000_000, 76.0);         // above the trigger: still resting
        sim.fillStopOnCancel(true);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 74);
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("SKIPPED_STOPPED_AT_BROKER");
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("CLOSED");
            assertThat(p.get("exit_reason")).isEqualTo("BROKER_STOP");
        });
        assertThat(orders(s.broker())).noneMatch(r -> r.get("role").equals("EXIT"));
        assertThat(sim.netQuantities()).as("never short").containsEntry(KOTAK_SYMBOL, 0L);
    }

    @Test
    void anExpiredSessionRefusesEntriesAndKeepsTheExitForTheNextLogin() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        kotak.run(s.broker(), s.group(), DAY);
        sim.expireSession();
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        FollowerSimulator.DayResult r = kotak.run(s.broker(), s.group(), DAY);
        assertThat(r.reasons()).containsKey("PENDING_BROKER_SESSION");
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class,
                s.broker().id())).isEqualTo("TOKEN_EXPIRED");
        // the next login (TOTP + MPIN): a new token and sid, the account ACTIVE again; the exit goes out
        KotakSimulator.Session login = sim.login();
        http.session(login.token(), login.sid());
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(sim.netQuantities()).containsEntry(KOTAK_SYMBOL, 0L);
    }

    @Test
    void aSessionFromAnotherIpWhileAnEntryWorksKeepsTheIntentAndResumesItByTagAfterTheLogin() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kotak = brokerFollower();
        sim.failNext("orders", Fault.NONE);                            // the gateway's check of the tag before the place
        sim.failNext("orders", Fault.WRONG_IP);                        // placed, then Kotak's 1037 on the book read
        FollowerSimulator.DayResult r = kotak.run(s.broker(), s.group(), DAY);
        assertThat(r.reasons()).containsKey("PENDING_BROKER_SESSION");
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class,
                s.broker().id())).isEqualTo("TOKEN_EXPIRED");
        assertThat(sim.netQuantities()).containsEntry(KOTAK_SYMBOL, 1300L);
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        int before = sim.orderCount();
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
        assertThat(sim.orderCount() - before).as("no second entry: only the stop is new").isEqualTo(1);
    }

    @Test
    void aSessionFromAnotherIpOnThePlaceSendsNothingAndNeverPlacesTwice() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kotak = brokerFollower();
        sim.failNext("place", Fault.WRONG_IP);                         // Kotak's 1037 on the place itself
        FollowerSimulator.DayResult r = kotak.run(s.broker(), s.group(), DAY);
        assertThat(r.reasons()).containsKey("PENDING_BROKER_SESSION");
        assertThat(sim.orderCount()).isZero();
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        kotak.run(s.broker(), s.group(), DAY);
        // the venue's rule (as for Kite): a child refused for its session is ended, never sent again under its tag
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("REJECTED_NOT_FILLED");
        assertThat(sim.orderCount()).isZero();
    }

    @Test
    void anExitThatFailsOnceIsSentAgainAndCloses() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        kotak.run(s.broker(), s.group(), DAY);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        bookOverride.set(new double[] {0, 0, 121, 650, 120});          // no bid for a moment
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("status")).isEqualTo("OPEN");
        bookOverride.set(null);
        jdbc.update("update exec.trade_intent set updated_at = now() - interval '1 minute' where broker_account_id = ? "
                + "and action = 'EXIT'", s.broker().id());
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
        assertThat(sim.netQuantities()).containsEntry(KOTAK_SYMBOL, 0L);
    }

    @Test
    void aRestartMidOrderResumesByTagAndAManualTradeIsAReconciliationDifference() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ?", Long.class,
                s.broker().id());
        // a worker died after placing the first child: its row says SUBMITTED, and Kotak has the order (tag in ig)
        sim.quote(KEY, 100, 1_000_000, 100, 1_000_000, 100);
        gateway.place(new OrderRequest("F" + intent + "E1N1", 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, 1755, 100.2, 0, "F" + intent + "E1N1"));
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                + "client_tag, attempt_no, order_side, order_type, quantity_requested, status) values (?, ?, ?, 'KOTAK_NEO', "
                + "'ENTRY', ?, 1, 'BUY', 'LIMIT', 1755, 'SUBMITTED')", s.broker().tenantId(), intent, s.broker().id(),
                "F" + intent + "E1N1");
        int before = sim.orderCount();
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(sim.orderCount() - before).as("only the second child and the stops are new").isEqualTo(1 + 2);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(2600L);

        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(s.broker(), gateway).matched()).isTrue();
        gateway.place(new OrderRequest("MANUAL1", 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.BUY, OrderType.LIMIT, 65,
                100.2, 0, "MANUAL1"));
        BrokerReconciler.Check c = reconciler.check(s.broker(), gateway);
        assertThat(c.matched()).isFalse();
        assertThat(c.differences()).singleElement().asString().contains("ours 2600, broker 2665");
    }

    @Test
    void theAdaptersStoredRouteTradesItsOwnSimulatorAndHasNoLiveConnection() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerRisk risk = FollowerRisk.load(Path.of(V3));
        KotakDryRunAdapter adapter = new KotakDryRunAdapter(dataSource, 1e9);
        assertThat(adapter.broker()).isEqualTo(BrokerType.KOTAK_NEO);
        dryRun = adapter.dryRun(s.broker(), risk.orders());
        assertThat(dryRun.description()).startsWith("Kotak simulator http://127.0.0.1:");
        BrokerVenue venue = new BrokerVenue(dataSource, dryRun.connection(), risk.orders().fast(), (k, u) -> FREEZE,
                dryRun.beforeOrder());
        FollowerSimulator follower = new FollowerSimulator(dataSource, costs, risk, venue, null);
        follower.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(2600L);
        KotakSimulator own = (KotakSimulator) dryRun.resource();
        assertThat(own.netQuantities()).containsValue(2600L);

        // a restart: a new simulator is given the open position, so the exit sells what the account holds
        dryRun.closeAll();
        dryRun = adapter.dryRun(s.broker(), risk.orders());
        assertThat(((KotakSimulator) dryRun.resource()).netQuantities()).containsValue(2600L);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        new FollowerSimulator(dataSource, costs, risk, new BrokerVenue(dataSource, dryRun.connection(),
                risk.orders().fast(), (k, u) -> FREEZE, dryRun.beforeOrder()), null).run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(((KotakSimulator) dryRun.resource()).netQuantities()).containsValue(0L);
        assertThat(adapter.live(s.broker(), null)).as("no LIVE Kotak connection in this build").isNull();
    }

    // ------------------------------------------------------------------ code review 8 Nov 2026: the rest of the matrix

    @Test
    void aRestartWithAnEmptyBrokerBookReArmsTheStopsAndTheExitStillGoesOut() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        brokerFollower().run(s.broker(), s.group(), DAY);
        sim.close();
        // a new process: a fresh simulator that holds the position (as the adapter seeds it), but none of the old stops;
        // the old stop rows still say OPEN
        FollowerSimulator restarted = brokerFollower();
        sim.holding(KEY, 2600, 100);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        restarted.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"))
                .hasSize(4);                                             // two stale, two re-armed then taken off
        assertThat(sim.netQuantities()).containsEntry(KOTAK_SYMBOL, 0L);
    }

    @Test
    void anExitWaitsInTheSameTurnForItsStopCancelToShow() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        kotak.run(s.broker(), s.group(), DAY);
        sim.quote(KEY, 74.0, 1_000_000, 74.5, 1_000_000, 76.0);         // above the trigger: still resting
        sim.cancelLag(3);                                               // Kotak's book shows the cancel three reads late
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 74);
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).as("exited in one turn").isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"));
        assertThat(sim.netQuantities()).as("flat, never short").containsEntry(KOTAK_SYMBOL, 0L);
    }

    @Test
    void aRefusedStopExitsThePositionAtOnce() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        // one entry child (20 lots, under the freeze quantity), then the stop's place is refused (Kotak: circuit limits)
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        sim.failNext("place", Fault.NONE);
        sim.failNext("place", Fault.INPUT_ERROR);
        kotak.run(s.broker(), s.group(), DAY);
        Map<String, Object> p = position(s.broker());
        assertThat(p.get("status")).isEqualTo("CLOSED");
        assertThat(p.get("exit_reason")).isEqualTo("STOP_PLACEMENT_FAILED");
        assertThat(orders(s.broker())).extracting(r -> r.get("role") + ":" + r.get("status"))
                .containsExactly("ENTRY:FILLED", "STOP:REJECTED", "EMERGENCY_EXIT:FILLED");
        assertThat(sim.netQuantities()).containsEntry(KOTAK_SYMBOL, 0L);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY))
                .startsWith("ACCOUNT: STOP_PLACEMENT_FAILED");
    }

    @Test
    void aPositionSoldByHandAfterItsStopsWereCancelledIsBookedAsAManualExitNotAKill() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kotak = brokerFollower();
        kotak.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(2600L);
        // the user cancels the bot's stops in the Neo app and sells the position by hand (other apps' tags: not ours)
        orders(s.broker()).stream().filter(r -> r.get("role").equals("STOP"))
                .forEach(r -> gateway.cancel((String) r.get("client_tag")));
        sim.quote(KEY, 90, 1_000_000, 90.05, 1_000_000, 90);
        for (int i = 1; i <= 4; i++) {
            gateway.place(new OrderRequest("HAND" + i, 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.SELL, OrderType.LIMIT,
                    650, 90, 0, "HAND" + i));
        }
        assertThat(gateway.orders()).filteredOn(o -> o.side() == OrderSide.SELL && o.type() == OrderType.LIMIT)
                .extracting(BrokerOrder::clientTag).as("the Neo app's orders are not ours").containsOnlyNulls();

        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(s.broker(), gateway).differences()).singleElement().asString()
                .contains("ours 2600, broker 0");
        assertThat(position(s.broker()).get("status")).as("one check alone books nothing").isEqualTo("OPEN");
        assertThat(reconciler.check(s.broker(), gateway).matched()).isTrue();
        Map<String, Object> p = position(s.broker());
        assertThat(p.get("status")).isEqualTo("CLOSED");
        assertThat(p.get("exit_reason")).isEqualTo("MANUAL");
        assertThat((Double) p.get("realised_gross")).isEqualTo(-10.0 * 2600);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                + "and action = 'MANUAL_EXIT'", Integer.class, s.broker().id())).isEqualTo(1);
    }

    @Test
    void aStopModifyTheAdapterRefusesIsCancelledAndPlacedAgain() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kotak = brokerFollower();
        kotak.run(s.broker(), s.group(), DAY);                          // 1,300 bought, one stop at 75
        long positionId = jdbc.queryForObject("select id from exec.position where broker_account_id = ?", Long.class,
                s.broker().id());
        jdbc.update("update exec.position set stop_pct = 10 where id = ?", positionId);
        // the adapter refuses the change (as the Kotak, Upstox and Groww gateways refuse to re-size a partly filled
        // order): the venue must not give up on the stop, nor leave the position without one
        BrokerConnection refusing = new Refusing(gateway);
        BrokerVenue venue = new BrokerVenue(dataSource, refusing, FollowerRisk.load(Path.of(V3)).orders().fast(),
                (k, u) -> FREEZE, o -> { });
        FollowerVenue.Protection p = venue.protect(s.broker(), positionId, null);
        assertThat(p.failure()).isNull();
        assertThat(p.sold()).isEmpty();
        List<Map<String, Object>> stops = orders(s.broker()).stream().filter(r -> r.get("role").equals("STOP")).toList();
        assertThat(stops).extracting(r -> r.get("status")).containsExactly("CANCELLED", "OPEN");
        assertThat(((Number) stops.get(1).get("trigger_price")).doubleValue()).isEqualTo(90.0);
        assertThat(gateway.orders()).filteredOn(o -> o.type() == OrderType.STOP_LIMIT && !o.status().isTerminal())
                .singleElement().satisfies(o -> {
                    assertThat(o.quantity()).isEqualTo(1300L);
                    assertThat(o.triggerPrice()).isEqualTo(90.0);
                });
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
    }

    /** The gateway, whose modify is refused as TERMINAL. */
    private static final class Refusing implements BrokerConnection {
        private final BrokerConnection c;

        Refusing(BrokerConnection c) {
            this.c = c;
        }

        @Override public String name() { return c.name(); }
        @Override public BrokerCapabilities capabilities() { return c.capabilities(); }
        @Override public void onUpdate(java.util.function.Consumer<OrderUpdate> l) { c.onUpdate(l); }
        @Override public void place(OrderRequest r) { c.place(r); }
        @Override public void cancel(String tag) { c.cancel(tag); }
        @Override public double availableFunds() { return c.availableFunds(); }
        @Override public Quote quote(String k) { return c.quote(k); }
        @Override public List<BrokerOrder> orders() { return c.orders(); }
        @Override public List<BrokerTrade> trades() { return c.trades(); }
        @Override public List<BrokerPosition> positions() { return c.positions(); }
        @Override public int poll() { return c.poll(); }

        @Override
        public void modify(String tag, long quantity, double limit, double trigger) {
            throw new BrokerException(BrokerException.Kind.TERMINAL, "resize a partly filled order by cancel and re-place: "
                    + tag);
        }
    }

    @Test
    void aRouteThatFailsToOpenClosesItsSimulator() throws Exception {
        Setup s = setup(false);
        javax.sql.DataSource broken = new com.zaxxer.hikari.HikariDataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                throw new java.sql.SQLException("database gone");
            }
        };
        java.net.URI nowhere = java.net.URI.create("http://127.0.0.1:1");
        List<BrokerAdapter> adapters = List.of(new KotakDryRunAdapter(broken, 1e9),
                new UpstoxDryRunAdapter(broken, 1e9, BrokerSessions.NONE, com.autotrade.upstox.orders.UpstoxConfig.at(nowhere)),
                new GrowwDryRunAdapter(broken, 1e9, BrokerSessions.NONE, com.autotrade.groww.GrowwConfig.at(nowhere)));
        long servers = dispatchers();
        for (BrokerAdapter adapter : adapters) {
            for (String risk : List.of(V3, "../config/risk/follower-risk.v6.yaml")) {
                OrderRules rules = FollowerRisk.load(Path.of(risk)).orders();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.dryRun(s.broker(), rules))
                        .as(adapter.broker() + " " + risk).isInstanceOf(RuntimeException.class);
            }
        }
        for (int i = 0; i < 50 && dispatchers() > servers; i++) {
            Thread.sleep(50);
        }
        assertThat(dispatchers()).as("no simulator left listening").isEqualTo(servers);
    }

    /** Live HTTP server dispatcher threads (one per started simulator) in this JVM. */
    private static long dispatchers() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                && t.getName().startsWith("HTTP-Dispatcher")).count();
    }
}
