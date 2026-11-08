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
        // review 8 Nov 2026: processed, then a 500 (TIMEOUT_PROCESSED stalls 1.5 s here, inside the client's 2 s read
        // timeout, so the place used to succeed normally and this test never exercised the unknown outcome)
        sim.failNext("place", Fault.HTTP_500_PROCESSED);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(orders(s.broker()).stream().filter(r -> r.get("role").equals("STOP")).count()).isEqualTo(2);
        assertThat(sim.requests("place")).as("2 entry slices (the uncertain one found by its tag, never placed again) "
                + "and 2 stop slices: a second entry place would make 5").isEqualTo(4);
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
    void anExitWaitsInTheSameTurnForItsStopCancelToShow() throws Exception {
        // 4 Nov 2026, first LIVE day: one book read right after the cancel still showed the stop, so the exit went out a
        // turn later (about 1.15 s); now the book is read again until the cancel shows (up to STOP_RELEASE_WAIT_SEC)
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        sim.quote(KEY, 74.0, 1_000_000, 74.5, 1_000_000, 76.0);        // above the trigger: still resting
        sim.cancelLag(3);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 74);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).as("exited in one turn").isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"));
        assertThat(sim.netQuantities()).as("flat, never short").containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void aStopCancelThatNeverShowsSendsNoExitThisTurn() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        sim.quote(KEY, 74.0, 1_000_000, 74.5, 1_000_000, 76.0);
        sim.cancelLag(1_000_000);                                       // longer than the wait
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 74);
        long start = System.nanoTime();
        kite.run(s.broker(), s.group(), DAY);
        assertThat((System.nanoTime() - start) / 1e9).as("the wait is bounded")
                .isLessThan(BrokerVenue.STOP_RELEASE_WAIT_SEC + 3);
        assertThat(intentState(s.broker(), "EXIT")).as("left for the next turn").isEqualTo("CREATED");
        assertThat(orders(s.broker())).noneMatch(r -> r.get("role").equals("EXIT"));
        assertThat(sim.netQuantities()).as("still long, no exit beside a stop").containsEntry("NIFTY26O2023100PE", 2600L);
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
    void aLostSessionIsAuditedOnceNotOnEveryCheck() {
        // 7 Nov 2026: a LIVE account nobody logged in to wrote BROKER_SESSION_LOST (each one sent on Telegram) on every
        // 30 s reconciliation; now once when the session goes, once a day while it stays gone, and again after a login
        Setup s = setup(false);
        KillSwitches kills = new KillSwitches(dataSource);
        java.util.function.IntSupplier rows = () -> jdbc.queryForObject("select count(*) from exec.audit_log where "
                + "broker_account_id = ? and action = 'BROKER_SESSION_LOST'", Integer.class, s.broker().id());
        assertThat(kills.loseSession(s.broker(), "test", Map.of("during", "reconciliation"))).isTrue();
        assertThat(kills.loseSession(s.broker(), "test", Map.of("during", "reconciliation"))).isFalse();
        assertThat(kills.loseSession(s.broker(), "test", Map.of("during", "reconciliation"))).isFalse();
        assertThat(rows.getAsInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class,
                s.broker().id())).isEqualTo("TOKEN_EXPIRED");
        // yesterday's row only (no login since): told again today, once
        jdbc.update("update exec.audit_log set at = at - interval '1 day' where broker_account_id = ?", s.broker().id());
        assertThat(kills.loseSession(s.broker(), "test", Map.of())).isTrue();
        assertThat(kills.loseSession(s.broker(), "test", Map.of())).isFalse();
        // a login, then the session goes again the same day: told again
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        assertThat(kills.loseSession(s.broker(), "test", Map.of())).isTrue();
        assertThat(rows.getAsInt()).isEqualTo(3);
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
        // no bid at all; code review 3 Nov 2026: tried EXIT_ATTEMPTS times, EXIT_RETRY_AFTER apart, each audited, before giving up
        for (int attempt = 1; attempt < FollowerSimulator.EXIT_ATTEMPTS; attempt++) {
            bookOverride.set(new double[] {0, 0, 121, 650, 120});    // still no bid (the override is used once)
            kite.run(s.broker(), s.group(), DAY);
            assertThat(intentState(s.broker(), "EXIT")).startsWith("CREATED");
            assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
            kite.run(s.broker(), s.group(), DAY);                     // too soon: waits, nothing sent
            assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                    + "'EXIT_RETRY'", Integer.class, s.broker().id())).isEqualTo(attempt);
            jdbc.update("update exec.trade_intent set updated_at = now() - interval '1 minute' where broker_account_id = ? "
                    + "and action = 'EXIT'", s.broker().id());
        }
        bookOverride.set(new double[] {0, 0, 121, 650, 120});
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
    void anExitThatFailsOnceIsSentAgainAndCloses() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        bookOverride.set(new double[] {0, 0, 121, 650, 120});        // no bid for a moment
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("status")).isEqualTo("OPEN");
        bookOverride.set(null);                                       // the book is back
        jdbc.update("update exec.trade_intent set updated_at = now() - interval '1 minute' where broker_account_id = ? "
                + "and action = 'EXIT'", s.broker().id());
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
        assertThat(orders(s.broker())).extracting(r -> r.get("role") + ":" + r.get("client_tag").toString().replaceAll("^F\\d+", ""))
                .contains("EXIT:E2N1").noneMatch(t -> t.startsWith("EXIT:E1"));
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
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY))
                .as("one check alone: a stop not yet booked or a lagging position looks the same").isNull();
        assertThat(reconciler.check(s.broker(), gateway).matched()).isFalse();
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).startsWith("ACCOUNT: RECONCILIATION");

        // the end-of-day check names the contract, in its result and in the day's audit row
        BrokerReconciler.EndOfDay eod = reconciler.endOfDay(s.broker(), gateway, DAY);
        assertThat(eod.differences()).singleElement().asString().startsWith(KEY + ": ours bought 2600");
        assertThat(jdbc.queryForObject("select detail -> 'differences' ->> 0 from exec.audit_log where broker_account_id = ? "
                + "and action = 'END_OF_DAY'", String.class, s.broker().id())).isEqualTo(eod.differences().get(0));
    }

    /** Sells by hand (no tag of ours) of {@code lots} × 650 at {@code price}, as the user does in Kite. */
    private void sellByHand(String tag, int lots, double price) {
        sim.quote(KEY, price, 1_000_000, price + 0.05, 1_000_000, price);
        for (int i = 1; i <= lots; i++) {
            gateway.place(new OrderRequest(tag + i, 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.SELL, OrderType.LIMIT,
                    650, price, 0, tag + i));
        }
    }

    @Test
    void aPositionSoldByHandAfterItsStopsWereCancelledIsBookedAsAManualExitNotAKill() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(2600L);
        // 6 Nov 2026 on ZERODHA-1: the user cancels the bot's stops in Kite and sells the position by hand
        orders(s.broker()).stream().filter(r -> r.get("role").equals("STOP"))
                .forEach(r -> gateway.cancel((String) r.get("client_tag")));
        sellByHand("HAND", 4, 90);

        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(s.broker(), gateway).differences()).singleElement().asString()
                .contains("ours 2600, broker 0");
        assertThat(position(s.broker()).get("status")).as("one check alone books nothing").isEqualTo("OPEN");
        assertThat(reconciler.check(s.broker(), gateway).matched()).isTrue();
        Map<String, Object> p = position(s.broker());
        assertThat(p.get("status")).isEqualTo("CLOSED");
        assertThat(p.get("exit_reason")).isEqualTo("MANUAL");
        assertThat(p.get("quantity_open")).isEqualTo(0L);
        assertThat((Double) p.get("realised_gross")).isEqualTo(-10.0 * 2600);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
        Map<String, Object> audit = jdbc.queryForMap("select detail ->> 'quantity' q, detail ->> 'price' price, "
                + "jsonb_array_length(detail -> 'trades') n from exec.audit_log where broker_account_id = ? "
                + "and action = 'MANUAL_EXIT'", s.broker().id());
        assertThat(audit).containsEntry("q", "2600").containsEntry("price", "90.0").containsEntry("n", 4);

        // the strategy's exit later finds nothing to sell: no order goes to the broker, the account stays tradable
        int before = sim.orderCount();
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(sim.orderCount()).isEqualTo(before);
        assertThat(reconciler.check(s.broker(), gateway).matched()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                + "and action = 'MANUAL_EXIT'", Integer.class, s.broker().id())).as("the hand sells are used once").isEqualTo(1);
    }

    @Test
    void aHandSaleWhileAStopIsStillLiveCutsTheStopsSoTheyCannotSellShortAndIsBooked() throws Exception {
        // review 8 Nov 2026: a hand sale in Kite with our stops still resting left them live (only a kill), so a later
        // fall through the trigger sold 2,600 the account no longer held: a short. Now the first check cuts our working
        // sells to what the broker holds, and the next books the hand sale
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        sellByHand("HANDX", 4, 90);                                   // the stops still rest: they could sell again
        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(s.broker(), gateway).matched()).isFalse();
        assertThat(gateway.orders()).filteredOn(o -> o.clientTag() != null && o.clientTag().matches("F\\d+S.*"))
                .hasSize(2).allSatisfy(o -> assertThat(o.status()).isEqualTo(com.autotrade.broker.OrderStatus.CANCELLED));
        sim.quote(KEY, 72, 1_000_000, 72.05, 1_000_000, 72);         // through the 75 trigger, above the 71.25 limit
        assertThat(sim.netQuantities()).as("never short").containsEntry("NIFTY26O2023100PE", 0L);
        assertThat(reconciler.check(s.broker(), gateway).matched()).isTrue();
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("CLOSED");
            assertThat(p.get("exit_reason")).isEqualTo("MANUAL");
        });
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                + "and action = 'SELLS_CUT'", Integer.class, s.broker().id())).isEqualTo(1);
        kite.run(s.broker(), s.group(), DAY);                          // the turn after: nothing re-armed, still flat
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void theSameReconciliationDifferenceEveryCheckWritesOneKillRow() throws Exception {
        // 6 Nov 2026: the same difference every 30 s wrote 1,076 audit rows (each one an alert)
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        brokerFollower().run(s.broker(), s.group(), DAY);
        sim.quote(KEY, 100, 1_000_000, 100, 1_000_000, 100);
        gateway.place(new OrderRequest("MANUALB1", 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.BUY, OrderType.LIMIT, 65,
                100.2, 0, "MANUALB1"));                                 // bought by hand: the broker holds more
        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        for (int i = 0; i < 4; i++) {
            assertThat(reconciler.check(s.broker(), gateway).matched()).isFalse();
        }
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).startsWith("ACCOUNT: RECONCILIATION");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action like "
                + "'KILL_ACCOUNT%'", Integer.class, s.broker().id())).as("the same difference every check: one row").isEqualTo(1);
    }

    @Test
    void aCutOfOurSellsThatFailsIsAuditedOnceNotAtEveryCheck() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        brokerFollower().run(s.broker(), s.group(), DAY);
        sellByHand("HANDC", 4, 90);
        for (int i = 0; i < 4; i++) {
            sim.failNext("cancel", Fault.HTTP_500);                      // two stops, two checks: no cancel goes through
        }
        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        reconciler.check(s.broker(), gateway);
        reconciler.check(s.broker(), gateway);
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                + "and action = 'SELLS_CUT'", Integer.class, s.broker().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select detail::text from exec.audit_log where broker_account_id = ? "
                + "and action = 'SELLS_CUT'", String.class, s.broker().id())).contains("NOT cut");
    }

    @Test
    void aResumeWhoseBookReadFailsIsWatchedAndItsUnbookedFillIsAdopted() throws Exception {
        // review 8 Nov 2026 (second review): the resumed order whose read failed kept its status, so nothing watched it,
        // and a fill recorded before the lost session (never booked) was taken as booked
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        sim.partialFills(0.5);
        sim.failNext("orders", Fault.NONE);                            // the first read records 650 filled
        sim.failNext("orders", Fault.TOKEN_EXPIRED);                   // then the session goes
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        assertThat(orders(s.broker())).singleElement().satisfies(r -> assertThat(r.get("quantity_filled")).isEqualTo(650L));
        sim.quote(KEY, 100, 1_000_000, 100, 1_000_000, 100);           // the rest fills meanwhile: 1,300 held
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        sim.failNext("orders", Fault.HTTP_500);                        // the resume's read fails
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("RECONCILIATION_REQUIRED_ORDER_UNKNOWN");
        assertThat(orders(s.broker())).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("UNKNOWN"));
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .extracting(r -> r.get("quantity_requested")).containsExactly(1300L);
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 1300L);
    }

    @Test
    void anEntryWithOrdersAtTheBrokerWaitsWhileTheSessionIsLostAndIsResumedAfterTheLogin() throws Exception {
        // review 8 Nov 2026 (second review): a turn with the session still lost refused it (BROKER_SESSION), and a
        // refusal written before resuming left it refused when the session went again mid-resume
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        sim.failNext("orders", Fault.TOKEN_EXPIRED);                   // placed, then the first read fails
        kite.run(s.broker(), s.group(), DAY);
        kite.run(s.broker(), s.group(), DAY);                          // still logged out
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        new KillSwitches(dataSource).engageAccount(s.broker(), "drill: killed while logged out", "it");
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        sim.failNext("orders", Fault.TOKEN_EXPIRED);                   // the session goes again while it is resumed
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        int before = sim.orderCount();
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
        assertThat(sim.orderCount() - before).as("only the stop is new").isEqualTo(1);
    }

    @Test
    void aRecordedOrderPlacedAgainThatFailsIsOfUnknownOutcomeNeverRejected() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ?", Long.class,
                s.broker().id());
        // a worker stopped after recording the order: not in the broker's book, so it is placed again (by its tag)
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                + "client_tag, attempt_no, order_side, order_type, quantity_requested, status) values (?, ?, ?, 'ZERODHA', "
                + "'ENTRY', ?, 1, 'BUY', 'LIMIT', 1300, 'SUBMITTED')", s.broker().tenantId(), intent, s.broker().id(),
                "F" + intent + "E1");
        sim.failNext("place", Fault.INPUT_ERROR);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(orders(s.broker())).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("UNKNOWN"));
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("RECONCILIATION_REQUIRED_ORDER_UNKNOWN");
    }

    @Test
    void aDryRunRestartThatLostTheBookDoesNotLeaveAnUnresolvedSellBlockingTheExit() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ?", Long.class,
                s.broker().id());
        // an emergency exit whose cancel was not confirmed, in a simulator a restart has since replaced
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                + "client_tag, broker_order_id, attempt_no, order_side, order_type, quantity_requested, status) values "
                + "(?, ?, ?, 'ZERODHA', 'EMERGENCY_EXIT', ?, ?, 9, 'SELL', 'LIMIT', 1300, 'RECONCILIATION_REQUIRED')",
                s.broker().tenantId(), intent, s.broker().id(), "F" + intent + "X9", "lost-" + intent);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "EXIT")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("status")).isEqualTo("CLOSED");
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void positionsThatLagAFillNeverCutAStop() throws Exception {
        // Kite's positions can lag its order book: a fresh entry not yet in them is no reason to cut its stop
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        brokerFollower().run(s.broker(), s.group(), DAY);
        com.autotrade.broker.BrokerConnection lagging = (com.autotrade.broker.BrokerConnection) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[] {com.autotrade.broker.BrokerConnection.class},
                        (proxy, m, args) -> {
                            if (m.getName().equals("positions")) {
                                return List.of();
                            }
                            try {
                                return m.invoke(gateway, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(s.broker(), lagging).differences()).singleElement().asString()
                .contains("ours 2600, broker 0");
        assertThat(gateway.orders()).filteredOn(o -> o.clientTag() != null && o.clientTag().matches("F\\d+S.*"))
                .hasSize(2).allSatisfy(o -> assertThat(o.status().isTerminal()).isFalse());
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                + "and action = 'SELLS_CUT'", Integer.class, s.broker().id())).isZero();
    }

    @Test
    void aPartialHandSaleReducesTheStopToWhatIsStillHeld() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        brokerFollower().run(s.broker(), s.group(), DAY);              // 1,300 with one stop of 1,300
        sellByHand("HANDP", 1, 90);                                    // 650 sold by hand: 650 still held
        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        assertThat(reconciler.check(s.broker(), gateway).matched()).isFalse();
        assertThat(gateway.orders()).filteredOn(o -> o.clientTag() != null && o.clientTag().matches("F\\d+S.*"))
                .singleElement().satisfies(o -> {
                    assertThat(o.status().isTerminal()).isFalse();
                    assertThat(o.quantity()).isEqualTo(650L);
                });
        sim.quote(KEY, 72, 1_000_000, 72.05, 1_000_000, 72);         // through the 75 trigger, above the 71.25 limit
        assertThat(sim.netQuantities()).as("the reduced stop sold what was held, no more").containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void aFilledEntryIsNotCancelledAsUnseenWhileTheOrderBookCannotBeRead() throws Exception {
        // review 8 Nov 2026: a read that failed counted as "not in the book"; Kite's read timeout is 5 s, so one slow read
        // cancelled a filled entry as never seen and left it at the broker with no position and no stop
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        for (int i = 0; i < 4; i++) {
            sim.failNext("orders", Fault.TIMEOUT);                     // 4 x 1.5 s: the book unreadable for 6 s
        }
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .extracting(r -> r.get("quantity_requested")).containsExactly(1300L);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
    }

    @Test
    void anEntryCutShortByALostSessionIsStillResumedWhenTheAccountWasKilledMeanwhile() throws Exception {
        // review 8 Nov 2026: the resumed entry ran its checks first (kill switch, and LIVE: signal age, time) and was
        // refused, so its filled order at the broker never became a position and had no stop
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        sim.failNext("orders", Fault.TOKEN_EXPIRED);                  // placed, then the first order-book read fails
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        new KillSwitches(dataSource).engageAccount(s.broker(), "drill: killed while logged out", "it");
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        int before = sim.orderCount();
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
        assertThat(sim.orderCount() - before).as("no second entry: only the stop is new").isEqualTo(1);
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .extracting(r -> r.get("quantity_requested")).containsExactly(1300L);
    }

    @Test
    void anEntryWhoseCancelWasNotConfirmedIsAdoptedWhenItFillsLater() throws Exception {
        // review 8 Nov 2026: the rest of an entry whose cancel failed filled later and was never booked or protected
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        sim.partialFills(0.5);                                        // 650 of 1,300 fill, the rest rests
        sim.failNext("cancel", Fault.HTTP_500);                       // the timeout's cancel does not go through
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(650L);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).contains("ORDER_UNKNOWN");
        sim.quote(KEY, 100, 1_000_000, 100, 1_000_000, 100);           // the resting rest of the entry fills
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 1300L);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("quantity_open")).isEqualTo(1300L);
            assertThat((Double) p.get("average_entry")).isEqualTo(100.0, org.assertj.core.data.Offset.offset(0.01));
        });
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP") && r.get("status").equals("OPEN"))
                .extracting(r -> r.get("quantity_requested")).containsExactly(1300L);
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("ENTRY"))
                .extracting(r -> r.get("status")).containsExactly("FILLED");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                + "and action = 'LATE_FILL'", Integer.class, s.broker().id())).isEqualTo(1);
        kite.run(s.broker(), s.group(), DAY);                          // booked once
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
    }

    @Test
    void anEntryOfUnknownOutcomeThatShowsUpFilledLaterBecomesAPositionWithItsStop() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        sim.failNext("place", Fault.TIMEOUT);                         // not processed: looked up, not found: unknown
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("RECONCILIATION_REQUIRED_ORDER_UNKNOWN");
        assertThat(jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Integer.class,
                s.broker().id())).isZero();
        // Kite's book shows the order after all, filled
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ?", Long.class,
                s.broker().id());
        sim.quote(KEY, 100, 1_000_000, 100, 1_000_000, 100);
        gateway.place(new OrderRequest("F" + intent + "E1", 3000047168L, KEY, SYMBOL, "NSE_FO", OrderSide.BUY,
                OrderType.LIMIT, 1300, 100.2, 0, "F" + intent + "E1"));
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("OPEN");
            assertThat(p.get("quantity_open")).isEqualTo(1300L);
        });
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP"))
                .extracting(r -> r.get("quantity_requested")).containsExactly(1300L);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("EXECUTED_LATE_FILL");
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("quantity_open")).as("booked once").isEqualTo(1300L);
    }

    @Test
    void aStopWhosePlaceHasAnUnknownOutcomeIsTriedAgainNotAnEmergencyExit() throws Exception {
        // review 8 Nov 2026: any non-session error placing the stop (a 5xx, a timeout, a failed read) sold the whole
        // position at once and killed the account; now only a stop the broker refused does
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        sim.failNext("place", Fault.NONE);
        sim.failNext("place", Fault.HTTP_500);                         // the stop: not processed, outcome unknown
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("status")).isEqualTo("OPEN");
        assertThat(orders(s.broker())).noneMatch(r -> r.get("role").equals("EMERGENCY_EXIT"));
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
        int before = sim.orderCount();
        kite.run(s.broker(), s.group(), DAY);                          // backed off: nothing placed every turn
        assertThat(sim.orderCount()).isEqualTo(before);
        kite.withProtectBackOff(java.time.Duration.ZERO);
        kite.run(s.broker(), s.group(), DAY);                          // the next try arms it
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP") && r.get("status").equals("OPEN"))
                .extracting(r -> r.get("quantity_requested")).containsExactly(1300L);
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 1300L);
    }

    @Test
    void aStopCheckThatFailsAfterARestartIsTriedAgainNextTurn() throws Exception {
        // review 8 Nov 2026: the account was marked checked before its stops were, so one failed read after a redeploy
        // left the positions without a broker stop for the day
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        brokerFollower().run(s.broker(), s.group(), DAY);
        sim.close();
        FollowerSimulator restarted = brokerFollower();
        sim.holding(KEY, 2600, 100);
        sim.failNext("orders", Fault.NONE);                            // the stop-fill read
        sim.failNext("orders", Fault.HTTP_500);                        // the stop check's read
        restarted.run(s.broker(), s.group(), DAY);
        restarted.run(s.broker(), s.group(), DAY);
        assertThat(gateway.orders()).filteredOn(o -> o.clientTag() != null && o.clientTag().matches("F\\d+S.*")
                && !o.status().isTerminal()).extracting(o -> o.quantity()).containsExactlyInAnyOrder(1755L, 845L);
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
    }

    @Test
    void everyEmergencyExitGetsItsOwnTagSoALaterOneIsReallySent() throws Exception {
        // review 8 Nov 2026: the emergency exit's tag restarted at X1 on every call, so the second exit attempt's
        // emergency sell "resumed" the first one's refused order and sent nothing
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        decide(s, "10:00:00", SignalDecision.Action.EXIT, 120);
        for (int i = 0; i < 3; i++) {
            sim.failNext("place", Fault.INPUT_ERROR);                  // the exit, the re-armed stop, the emergency exit
        }
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker()).get("status")).isEqualTo("OPEN");
        jdbc.update("update exec.trade_intent set updated_at = now() - interval '1 minute' where broker_account_id = ? "
                + "and action = 'EXIT'", s.broker().id());
        sim.failNext("place", Fault.INPUT_ERROR);                      // the second exit
        sim.failNext("place", Fault.INPUT_ERROR);                      // its stop; the emergency exit goes through
        kite.run(s.broker(), s.group(), DAY);
        assertThat(position(s.broker())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("CLOSED");
            assertThat(p.get("exit_reason")).isEqualTo("STOP_PLACEMENT_FAILED");
        });
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("EMERGENCY_EXIT"))
                .extracting(r -> r.get("client_tag").toString().replaceAll("^F\\d+", "") + ":" + r.get("status"))
                .containsExactly("X1:REJECTED", "X2:FILLED");
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 0L);
    }

    @Test
    void aStopReplacedAfterAnAddWaitsForItsCancelsToShow() throws Exception {
        // review 8 Nov 2026: replacing several stops looked each cancel up once; a cancel that showed a read later
        // was STOP_CANCEL_UNCONFIRMED and the whole position was sold as an emergency
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 182000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);                          // 28 lots = 1,820: stops of 1,755 and 65
        assertThat(orders(s.broker())).filteredOn(r -> r.get("role").equals("STOP")).hasSize(2);
        decide(s, "09:40:00", SignalDecision.Action.ADD, 100);
        sim.cancelLag(2);
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ADD")).isEqualTo("EXECUTED");
        assertThat(orders(s.broker())).noneMatch(r -> r.get("role").equals("EMERGENCY_EXIT"));
        long open = (Long) position(s.broker()).get("quantity_open");
        assertThat(open).isGreaterThan(1820L);
        assertThat(gateway.orders()).filteredOn(o -> o.clientTag() != null && o.clientTag().matches("F\\d+S.*")
                && !o.status().isTerminal()).extracting(o -> o.quantity()).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(Long.class))
                .satisfies(q -> assertThat(q.stream().mapToLong(Long::longValue).sum()).isEqualTo(open));
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
    }

    @Test
    void aDifferenceGoneByTheNextCheckEngagesNothing() throws Exception {
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        FollowerSimulator kite = brokerFollower();
        kite.run(s.broker(), s.group(), DAY);
        BrokerReconciler reconciler = new BrokerReconciler(dataSource, costs);
        // the broker stop sold everything, not yet booked (the next turn books it): one check sees ours open, broker flat
        jdbc.update("update exec.position set quantity_open = quantity_open + 65 where broker_account_id = ?", s.broker().id());
        assertThat(reconciler.check(s.broker(), gateway).matched()).isFalse();
        jdbc.update("update exec.position set quantity_open = quantity_open - 65 where broker_account_id = ?", s.broker().id());
        assertThat(reconciler.check(s.broker(), gateway).matched()).isTrue();
        assertThat(new KillSwitches(dataSource).engaged(s.broker(), "opening-drive", KEY)).isNull();
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
    void aSessionLostWhileAnUnknownPlaceIsLookedUpIsNeverRejectedAndTheFillIsAdoptedWithItsStopAfterTheLogin()
            throws Exception {
        // review 8 Nov 2026: the gateway rethrew the lookup's SESSION, so the row was written REJECTED although the order
        // had reached Kite (and filled); nothing watched it
        Setup s = setup(false);
        decide(s, "09:25:00", SignalDecision.Action.ENTER, 100);
        jdbc.update("update exec.signal_decision set premium_budget = 130000 where copy_group_id = ?", s.group());
        FollowerSimulator kite = brokerFollower();
        // processed, then a 500 (this simulator's 1.5 s TIMEOUT is inside the client's 2 s read timeout)
        sim.failNext("place", Fault.HTTP_500_PROCESSED);
        sim.failNext("orders", Fault.TOKEN_EXPIRED);                   // the token expires as it is looked up by tag
        FollowerSimulator.DayResult r = kite.run(s.broker(), s.group(), DAY);
        assertThat(r.reasons()).containsKey("PENDING_BROKER_SESSION");
        assertThat(jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class,
                s.broker().id())).as("the session loss is noticed").isEqualTo("TOKEN_EXPIRED");
        assertThat(orders(s.broker())).singleElement().satisfies(o -> assertThat(o.get("status")).isEqualTo("UNKNOWN"));
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 1300L);
        kite.run(s.broker(), s.group(), DAY);                          // still logged out: it waits
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("CREATED");
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", s.broker().id());
        int before = sim.orderCount();
        kite.run(s.broker(), s.group(), DAY);
        assertThat(intentState(s.broker(), "ENTER")).isEqualTo("EXECUTED");
        assertThat(position(s.broker()).get("quantity_open")).isEqualTo(1300L);
        assertThat(orders(s.broker())).filteredOn(o -> o.get("role").equals("STOP")).singleElement().satisfies(o -> {
            assertThat(o.get("quantity_requested")).isEqualTo(1300L);
            assertThat(o.get("status")).isEqualTo("OPEN");
        });
        assertThat(sim.orderCount() - before).as("no second entry: only the stop is new").isEqualTo(1);
        assertThat(sim.netQuantities()).containsEntry("NIFTY26O2023100PE", 1300L);
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

    // ------------------------------------------------------------------ L6: several accounts of one tenant

    /** Two ZERODHA DRY_RUN accounts of one new tenant on {@code riskFile}, following opening-drive in one group. */
    private record Pair(long group, long session, BrokerAccount a, BrokerAccount b) {
    }

    private Pair pair(String riskFile, double capitalA, double capitalB) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("pair-" + n);
        long owner = accounts.ensureUser("kc-pair-" + n, "pair");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-P" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "PAIR-" + n, signal);
        long a = accounts.ensureFollowerAccount(tenant, owner, "PA-" + n, BrokerType.ZERODHA, TradeMode.DRY_RUN, capitalA,
                EquityMode.FIXED, DAY, riskFile, 100);
        long b = accounts.ensureFollowerAccount(tenant, owner, "PB-" + n, BrokerType.ZERODHA, TradeMode.DRY_RUN, capitalB,
                EquityMode.FIXED, DAY, riskFile, 100);
        new FanOut(dataSource).subscribe(group, a, List.of("opening-drive"), 1.0);
        new FanOut(dataSource).subscribe(group, b, List.of("opening-drive"), 1.0);
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', ?, 'it', 'live-set', 'sha256:it', "
                + "'{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-P" + n, DAY);
        return new Pair(group, session, account(a), account(b));
    }

    private void enterDecision(Pair p, double budget, double liquidityLots) {
        new SignalDecisionStore(dataSource).insert(new SignalDecision(p.group(), p.session(), DAY, ist("09:25:00"),
                "opening-drive", 2, "NIFTY", SignalDecision.Action.ENTER, "PE", null, "CONFIRMED", "IT", 3000047168L, KEY,
                SYMBOL, LOT, 100, budget, 500_000, 0, p.group() + ":" + p.session() + ":1", Map.of("strategy_lots", 1.0,
                "intended_lots", 1.0, "liquidity_lots", liquidityLots, "stop_pct", 25.0)));
        new FanOut(dataSource).fanOut(p.group(), DAY);
    }

    private FollowerSimulator follower(String riskFile) {
        FollowerRisk risk = FollowerRisk.load(Path.of(riskFile));
        return new FollowerSimulator(dataSource, costs, risk, new BrokerVenue(dataSource, gateway, risk.orders().fast(),
                (k, u) -> FREEZE, o -> {
                    sim.clock(o::at);
                    sim.quote(KEY, 100, 1_000_000, 100, 1_000_000, 100);
                }), null);
    }

    @Test
    void aDecisionsBookIsSharedAcrossATenantsBrokerAccountsProRataToAllocation() throws Exception {
        brokerFollower();                                                // the simulator and gateway
        Pair p = pair("../config/risk/follower-risk.v4.yaml", 500_000, 250_000);
        enterDecision(p, 500_000, 30);                                   // the book allows 30 lots in all
        follower("../config/risk/follower-risk.v4.yaml").run(p.a(), p.group(), DAY);
        follower("../config/risk/follower-risk.v4.yaml").run(p.b(), p.group(), DAY);
        assertThat(position(p.a()).get("quantity_open")).as("2/3 of 30 lots").isEqualTo(20L * LOT);
        assertThat(position(p.b()).get("quantity_open")).as("1/3 of 30 lots").isEqualTo(10L * LOT);
    }

    @Test
    void combinedLimitsAcrossATenantsBrokerAccountsRefuseTheEntryThatWouldPassThem() throws Exception {
        brokerFollower();
        String lotsFile = "src/test/resources/follower-risk.v900.yaml";
        Pair lots = pair(lotsFile, 500_000, 500_000);
        enterDecision(lots, 130_000, 1000);                               // 20 lots each; the tenant's cap is 25
        follower(lotsFile).run(lots.a(), lots.group(), DAY);
        follower(lotsFile).run(lots.b(), lots.group(), DAY);
        assertThat(position(lots.a()).get("quantity_open")).isEqualTo(20L * LOT);
        assertThat(intentState(lots.b(), "ENTER")).isEqualTo("REJECTED_COMBINED_LOTS");

        String premiumFile = "src/test/resources/follower-risk.v901.yaml";
        Pair premium = pair(premiumFile, 500_000, 500_000);
        enterDecision(premium, 130_000, 1000);                            // 1.3L each; 20 % of 10L = 2L for both
        follower(premiumFile).run(premium.a(), premium.group(), DAY);
        follower(premiumFile).run(premium.b(), premium.group(), DAY);
        assertThat(position(premium.a()).get("quantity_open")).isEqualTo(20L * LOT);
        assertThat(intentState(premium.b(), "ENTER")).isEqualTo("REJECTED_COMBINED_PREMIUM");
    }

    @Test
    void aKilledAccountGivesUpItsShareAndParallelLanesStayWithinTheCombinedCap() throws Exception {
        brokerFollower();
        Pair p = pair("../config/risk/follower-risk.v4.yaml", 500_000, 250_000);
        new KillSwitches(dataSource).engageAccount(p.b(), "drill", "it");
        enterDecision(p, 500_000, 30);
        follower("../config/risk/follower-risk.v4.yaml").run(p.a(), p.group(), DAY);
        assertThat(position(p.a()).get("quantity_open")).as("all 30 lots: B cannot take its share").isEqualTo(30L * LOT);

        String lotsFile = "src/test/resources/follower-risk.v900.yaml";
        Pair q = pair(lotsFile, 500_000, 500_000);
        enterDecision(q, 130_000, 1000);                                  // 20 lots each, cap 25: one of them only
        FollowerSimulator a = follower(lotsFile), b = follower(lotsFile);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var fa = pool.submit(() -> a.run(q.a(), q.group(), DAY));
        var fb = pool.submit(() -> b.run(q.b(), q.group(), DAY));
        fa.get();
        fb.get();
        pool.shutdown();
        assertThat(jdbc.queryForObject("select coalesce(sum(quantity_open), 0) from exec.position where broker_account_id in "
                + "(?, ?)", Long.class, q.a().id(), q.b().id())).isEqualTo(20L * LOT);
    }
}
