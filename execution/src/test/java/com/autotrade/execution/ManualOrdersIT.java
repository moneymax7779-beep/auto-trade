package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import com.autotrade.zerodha.KiteSimulator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The trader's BUY CE / BUY PE / EXIT (user, 9 Nov 2026) through the existing order path: the best ATM is the tightest
 * spread of the three strikes around spot; a PAPER follower buys and exits through the execution worker; a LIVE Kite
 * follower (a local simulator standing for api.kite.trade) sends the entry and its 25 % stop and sells on EXIT; an
 * entry that filled in part is the position EXIT sells, never more; a broker reject leaves nothing open; a kill switch
 * blocks the buy before and after it is written; EXIT before the buy ran cancels it; followers never copy it; a risk
 * file without manual in its LIVE list refuses it; buys only in trading hours.
 */
@Testcontainers(disabledWithoutDocker = true)
class ManualOrdersIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    private static final String V3 = "../config/risk/follower-risk.v3.yaml";
    private static final String V7 = "../config/risk/follower-risk.v7.yaml";
    private static final String V8 = "../config/risk/follower-risk.v8.yaml";
    private static final String V13 = "../config/risk/follower-risk.v13.yaml";
    private static final BrokerRules RULES = BrokerRules.load(Path.of("..", "config", "brokers", "broker-rules.v1.yaml"));
    private static final int LOT = 65;
    private static final double[] STRIKES = {23050, 23100, 23150, 23200, 23250};
    private static final double SPOT = 23140;          // ATM 23150; 23100 and 23200 either side

    /**
     * The checks' clock: now while that is a trading minute, else 10:00 today (the LIVE entry cut-off is 15:10 IST and
     * manual buys start 09:15).
     */
    private static final Clock MORNING = morning();

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

    private KiteSimulator kite;
    private final AtomicReference<Optional<BrokerSessions.Access>> session = new AtomicReference<>();

    private static Clock morning() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        Instant at = !now.toLocalTime().isBefore(LocalTime.of(9, 16)) && now.toLocalTime().isBefore(LocalTime.of(15, 9))
                ? now.toInstant() : now.toLocalDate().atTime(10, 0).atZone(IST).toInstant();
        return Clock.fixed(at, IST);
    }

    private static String key(double strike, String side) {
        return "NSE_FO|" + (47000 + (int) (strike - 23000) / 50 * 2 + (side.equals("CE") ? 0 : 1));
    }

    private static long token(double strike, String side) {
        return InstrumentKeys.token(key(strike, side));
    }

    private static String symbol(double strike, String side) {
        return "NIFTY " + (int) strike + " " + side;
    }

    private static String kiteSymbol(double strike, String side) {
        return "NIFTY26X03" + (int) strike + side;
    }

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v2.yaml")));
        long snapshot = jdbc.queryForObject("insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, "
                + "instruments) values (?, 'UPSTOX:NSE-live.json.gz', 'manual-it', 10) returning id", Long.class, DAY);
        for (double strike : STRIKES) {
            for (String side : List.of("CE", "PE")) {
                jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, "
                        + "type, underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) "
                        + "values (?, ?, ?, 'NSE_FO', 'NSE', ?, 'NIFTY', ?, ?, ?, ?, 0.05, 1755, true)", snapshot,
                        key(strike, side), key(strike, side).substring(7), side, symbol(strike, side), DAY.plusDays(2),
                        strike, LOT);
            }
        }
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void market() throws Exception {
        jdbc.update("delete from md.index_tick");
        jdbc.update("delete from md.option_tick");
        Instant now = MORNING.instant();
        spot(now.minusSeconds(2), SPOT);
        // 23100: the tightest (0.1 %), 23150 (ATM): 0.5 %, 23200: 1.6 %; 23050 and 23250 are outside the three
        quote(23050, "CE", now.minusSeconds(3), 120.00, 120.05);
        quote(23100, "CE", now.minusSeconds(3), 100.00, 100.10);
        quote(23150, "CE", now.minusSeconds(3), 80.00, 80.40);
        quote(23200, "CE", now.minusSeconds(3), 61.00, 62.00);
        quote(23250, "CE", now.minusSeconds(3), 45.00, 45.05);
        for (double strike : STRIKES) {
            quote(strike, "PE", now.minusSeconds(3), 70.00, 70.20);
        }
        kite = new KiteSimulator(0, "real-key", "real-secret", 1_500);
        for (double strike : STRIKES) {
            for (String side : List.of("CE", "PE")) {
                kite.list(new KiteSimulator.Listing(Long.parseLong(key(strike, side).substring(7)), "NFO",
                        kiteSymbol(strike, side), "NIFTY", DAY.plusDays(2).toString(), strike, side, LOT, 0.05));
                kite.depth(key(strike, side), List.of(new Quote.Level(99.9, 65_000)),
                        List.of(new Quote.Level(100.0, 65_000)), 99.95);
            }
        }
        kite.funds(800_000);
        session.set(Optional.of(new BrokerSessions.Access("real-key", "day-token")));
    }

    @AfterEach
    void close() {
        kite.close();
    }

    private static void spot(Instant at, double price) {
        jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, price) values "
                + "(1, ?, 'NIFTY', ?, 1, ?)", DAY, Timestamp.from(at), price);
    }

    private static void quote(double strike, String side, Instant at, double bid, double ask) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, lot_size, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, "
                + "ask_orders, analytics_complete, depth_complete) values (1, ?, 'NIFTY', ?, 1, ?, ?, ?, ?, ?, ?, ?, "
                + "array[?]::float8[], '{6500}', '{3}', array[?]::float8[], '{6500}', '{3}', false, true)", DAY,
                Timestamp.from(at), token(strike, side), symbol(strike, side), DAY.plusDays(2), strike, side, LOT,
                (bid + ask) / 2, bid, ask);
    }

    // ------------------------------------------------------------------------------------------------- accounts

    private record Setup(long tenant, long group, BrokerAccount account) {
    }

    /** A copy group and one follower of it ({@code broker} PAPER or ZERODHA, LIVE when {@code mode} is). */
    private Setup setup(BrokerType broker, TradeMode mode, String riskFile) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("manual-" + n);
        long owner = accounts.ensureUser("kc-manual-" + n, "owner " + n);
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-M" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "MANUAL-" + n, signal);
        long id = accounts.ensureFollowerAccount(tenant, owner, "MAN-" + n, broker,
                mode == TradeMode.LIVE ? TradeMode.DRY_RUN : mode, 200_000, EquityMode.FIXED, DAY, riskFile,
                mode == TradeMode.LIVE ? 0 : 100);
        if (mode == TradeMode.LIVE) {
            long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                    + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', ?, 'file:kite-key', 'file:kite-secret') "
                    + "returning id", Long.class, tenant, "kite-app-m" + n);
            jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = 'LIVE', "
                    + "live_trading_enabled = true where id = ?", profile, id);
        }
        new FanOut(dataSource).subscribe(group, id, List.of("ma-cross"), 1.0);
        return new Setup(tenant, group, accounts.byId(id).orElseThrow());
    }

    private ManualOrders manual() {
        return new ManualOrders(dataSource).withClock(MORNING);
    }

    /** The contract the trader confirms (the best ATM now). */
    private String best(String side) {
        return manual().pick("NIFTY", side, DAY).chosen().symbol();
    }

    private BrokerConnections registry() {
        return new BrokerConnections(dataSource, 1e9,
                List.of(new ZerodhaDryRunAdapter(dataSource, 1e9, a -> session.get(), kite.baseUri())));
    }

    /** The account's follower over its LIVE Kite route, with the waits shortened. */
    private FollowerSimulator follower(BrokerConnections registry, BrokerAccount account) {
        FollowerRisk risk = FollowerRisk.load(Path.of(account.riskFile()));
        BrokerConnections.Route route = registry.route(account, risk);
        assertThat(route.venue()).as(route.description()).isNotNull();
        BrokerVenue venue = new BrokerVenue(dataSource, route.connection(), risk.orders().fast(), (k, u) -> 1755L, o -> { });
        // main 16 Nov 2026: no LIVE entry without the broker's trading-day rules (ZERODHA's last LIVE entry 15:10)
        return new FollowerSimulator(dataSource, costs, risk, venue, null).withBrokerRules(RULES).withClock(MORNING);
    }

    private static String intentState(long intent) {
        return jdbc.queryForObject("select state || coalesce('_' || reject_code, '') from exec.trade_intent where id = ?",
                String.class, intent);
    }

    private static Map<String, Object> position(BrokerAccount a) {
        return jdbc.queryForMap("select id, strategy_id, symbol, quantity_open, status, exit_reason from exec.position "
                + "where broker_account_id = ? order by id desc limit 1", a.id());
    }

    private static long positions(BrokerAccount a) {
        return jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ?", Long.class, a.id());
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    void theBestAtmIsTheTightestSpreadOfTheThreeStrikesAroundSpot() {
        ManualOrders.Pick pick = manual().pick("NIFTY", "CE", DAY);
        assertThat(pick.candidates()).extracting(ManualOrders.Candidate::strike).containsExactly(23100.0, 23150.0, 23200.0);
        assertThat(pick.chosen().symbol()).as("0.1 % beats the ATM's 0.5 %").isEqualTo("NIFTY 23100 CE");
        assertThat(pick.chosen().lotSize()).isEqualTo(LOT);
        assertThat(pick.instrumentKey()).isEqualTo(key(23100, "CE"));

        jdbc.update("update md.option_tick set recv_ts = recv_ts - interval '2 minutes' where strike = 23100 "
                + "and option_type = 'CE'");                                                  // its newest is 2 min old
        ManualOrders.Pick withoutStale = manual().pick("NIFTY", "CE", DAY);
        assertThat(withoutStale.chosen().symbol()).as("then the ATM's 0.5 % beats 1.6 %").isEqualTo("NIFTY 23150 CE");
        assertThat(withoutStale.candidates().getFirst().refusal()).startsWith("last quote");
        assertThat(withoutStale.candidates().get(2).refusal()).isNull();

        quote(23150, "CE", MORNING.instant().minusSeconds(1), 70.00, 80.00);                    // 13 % wide
        quote(23200, "CE", MORNING.instant().minusSeconds(1), 55.00, 0);                        // no ask
        assertThatThrownBy(() -> manual().pick("NIFTY", "CE", DAY)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can be bought now").hasMessageContaining("spread 13.3 %")
                .hasMessageContaining("no two-sided quote");

        jdbc.update("delete from md.index_tick");
        spot(MORNING.instant().minusSeconds(300), SPOT);
        assertThatThrownBy(() -> manual().pick("NIFTY", "PE", DAY)).hasMessageContaining("spot in the last 60 s");   // "market is closed" or "feed is not running" by the clock
    }

    @Test
    void aPaperBuyRunsThroughTheWorkerAndExitSellsItAndNoFollowerCopiesIt() throws Exception {
        Setup s = setup(BrokerType.PAPER, TradeMode.PAPER, V3);
        // another follower of the same group, even subscribed to "manual", never gets the trader's order
        AccountStore accounts = new AccountStore(dataSource);
        long other = accounts.ensureFollowerAccount(s.tenant(), s.account().appUserId(), "OTHER-" + seq, BrokerType.PAPER,
                TradeMode.PAPER, 200_000, EquityMode.FIXED, DAY, V3, 100);
        new FanOut(dataSource).subscribe(s.group(), other, List.of("ma-cross", ManualOrders.STRATEGY), 1.0);
        QuoteSource none = (d, u, t, at) -> Double.NaN;
        try (ExecutionLoop loop = new ExecutionLoop(dataSource, "ignored", "*", "worker-manual", costs, none, 1e9)
                .manualClock(MORNING)) {
            ManualOrders.Placed placed = loop.manualBuy(s.account().id(), "nifty", "pe", best("PE"), "kc-trader", DAY);
            assertThat(placed.quantity()).isEqualTo(LOT);
            assertThat(placed.mode()).isEqualTo("PAPER");
            loop.tick(DAY, 20_000);
            assertThat(intentState(placed.intentId())).isEqualTo("EXECUTED");
            assertThat(position(s.account())).satisfies(p -> {
                assertThat(p.get("strategy_id")).isEqualTo("manual");
                assertThat(p.get("symbol")).isEqualTo(placed.pick().chosen().symbol());
                assertThat(p.get("quantity_open")).isEqualTo((long) LOT);
                assertThat(p.get("status")).isEqualTo("OPEN");
            });
            assertThatThrownBy(() -> loop.manualBuy(s.account().id(), "NIFTY", "CE", best("CE"), "kc-trader", DAY))
                    .hasMessageContaining("already holds manual");
            // a contract the account holds is never bought again (it would share the position's token)
            ManualOrders.Pick again = loop.manualQuote(s.account().id(), "NIFTY", "PE", DAY);
            assertThat(again.candidates()).filteredOn(c -> c.symbol().equals(placed.pick().chosen().symbol()))
                    .singleElement().satisfies(c -> assertThat(c.refusal()).isEqualTo("held by this account's manual"));
            assertThat(again.chosen().symbol()).isNotEqualTo(placed.pick().chosen().symbol());

            assertThat(loop.manualExit(s.account().id(), "NIFTY", "kc-trader", DAY)).isNotEmpty();
            assertThat(position(s.account())).satisfies(p -> {
                assertThat(p.get("status")).isEqualTo("CLOSED");
                assertThat(p.get("exit_reason")).isEqualTo("TRADER_EXIT");
            });
            assertThat(loop.manualExit(s.account().id(), "NIFTY", "kc-trader", DAY))
                    .containsExactly("no manual NIFTY position or buy to exit");
        }
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ?", Long.class,
                other)).as("followers never copy a manual order").isZero();
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_batch b join exec.signal_decision d on d.id = "
                + "b.signal_decision_id where d.copy_group_id = ? and b.source = 'SIGNAL'", Long.class, s.group())).isZero();
        assertThat(jdbc.queryForList("select action from exec.audit_log where broker_account_id = ? order by id",
                String.class, s.account().id())).contains("MANUAL_BUY", "POSITION_EXIT");
    }

    @Test
    void exitBeforeTheBuyRanCancelsItAndNothingIsSent() throws Exception {
        Setup s = setup(BrokerType.PAPER, TradeMode.PAPER, V3);
        QuoteSource none = (d, u, t, at) -> Double.NaN;
        try (ExecutionLoop loop = new ExecutionLoop(dataSource, "ignored", "*", "worker-cancel", costs, none, 1e9)
                .manualClock(MORNING)) {
            ManualOrders.Placed placed = loop.manualBuy(s.account().id(), "NIFTY", "CE", "NIFTY 23100 CE", "kc-trader", DAY);
            assertThat(loop.manualExit(s.account().id(), null, "kc-trader", DAY))
                    .containsExactly("NIFTY 23100 CE: buy cancelled before it was sent");
            loop.tick(DAY, 20_000);
            assertThat(intentState(placed.intentId())).isEqualTo("CANCELLED_MANUAL_EXIT");
        }
        assertThat(positions(s.account())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from exec.order_execution where broker_account_id = ?", Long.class,
                s.account().id())).isZero();
    }

    @Test
    void exitNeverCancelsABuyWhoseOrderIsAlreadyAtTheBroker() throws Exception {
        // review 9 Nov 2026: an intent stays CREATED while its order waits at the broker (a session lost mid-entry)
        Setup s = setup(BrokerType.PAPER, TradeMode.PAPER, V3);
        QuoteSource none = (d, u, t, at) -> Double.NaN;
        try (ExecutionLoop loop = new ExecutionLoop(dataSource, "ignored", "*", "worker-at-broker", costs, none, 1e9)
                .manualClock(MORNING)) {
            ManualOrders.Placed placed = loop.manualBuy(s.account().id(), "NIFTY", "CE", best("CE"), "kc-trader", DAY);
            jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                    + "client_tag, attempt_no, order_side, order_type, quantity_requested, status) values (?, ?, ?, 'PAPER', "
                    + "'ENTRY', 'it-entry-1', 1, 'BUY', 'LIMIT', 65, 'SUBMITTED')", s.tenant(), placed.intentId(),
                    s.account().id());
            assertThat(loop.manualExit(s.account().id(), "NIFTY", "kc-trader", DAY)).singleElement()
                    .satisfies(line -> assertThat(line).contains("at the broker and its outcome is not known yet"));
            assertThat(intentState(placed.intentId())).as("left for its turn to resume").isEqualTo("CREATED");
        }
    }

    @Test
    void twoBuysAtTheSameMomentWriteOne() throws Exception {
        Setup s = setup(BrokerType.PAPER, TradeMode.PAPER, V3);
        String symbol = best("CE");
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<String>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    try {
                        manual().buy(s.account(), s.group(), "NIFTY", "CE", symbol, "kc-tab", DAY);
                        return "BOUGHT";
                    } catch (IllegalArgumentException e) {
                        return e.getMessage();
                    }
                }));
            }
            go.countDown();
            List<String> outcomes = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<String> call : calls) {
                outcomes.add(call.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsOnlyOnce("BOUGHT");
            assertThat(outcomes).anySatisfy(o -> assertThat(o).contains("still working"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ?", Long.class,
                s.account().id())).isEqualTo(1L);
    }

    @Test
    void theConfirmedContractMustStillBeTheBestAtm() {
        Setup s = setup(BrokerType.PAPER, TradeMode.PAPER, V3);
        assertThatThrownBy(() -> manual().buy(s.account(), s.group(), "NIFTY", "CE", null, "kc", DAY))
                .hasMessageContaining("confirm the contract");
        assertThatThrownBy(() -> manual().buy(s.account(), s.group(), "NIFTY", "CE", "NIFTY 23150 CE", "kc", DAY))
                .hasMessageContaining("the best ATM is now NIFTY 23100 CE");
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ?", Long.class,
                s.account().id())).isZero();
    }

    /**
     * Code review 9 Nov 2026: in a DAILY_SWITCH workspace only the entry account takes a buy, refused before anything is
     * written (idle() let other accounts through and the turn then rejected the written buy as TARGET_CHANGED).
     */
    @Test
    void inADailySwitchWorkspaceOnlyTheEntryAccountTakesABuy() {
        Setup s = setup(BrokerType.PAPER, TradeMode.PAPER, V3);
        AccountStore accounts = new AccountStore(dataSource);
        long otherId = accounts.ensureFollowerAccount(s.tenant(), s.account().appUserId(), "MAN-OTHER-" + seq,
                BrokerType.PAPER, TradeMode.PAPER, 200_000, EquityMode.FIXED, DAY, V3, 100);
        new FanOut(dataSource).subscribe(s.group(), otherId, List.of("ma-cross"), 1.0);
        BrokerAccount other = accounts.byId(otherId).orElseThrow();
        jdbc.update("update acct.tenant set trading_control = 'DAILY_SWITCH' where id = ?", s.tenant());
        String ce = best("CE");

        // nothing chosen today: trading is Off, no account takes a buy
        assertThatThrownBy(() -> manual().buy(s.account(), s.group(), "NIFTY", "CE", ce, "kc", DAY))
                .hasMessageContaining("trading is Off today");
        // the first account chosen (PAPER): the other one is refused, naming where entries go
        new TradingDay(dataSource).append(s.tenant(), s.account().id(), TradingDay.Mode.PAPER, "TODAYS_BROKER", null);
        assertThatThrownBy(() -> manual().buy(other, s.group(), "NIFTY", "CE", ce, "kc", DAY))
                .hasMessageContaining("entries go to " + s.account().displayName());
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ?", Long.class,
                other.id())).isZero();
        assertThat(manual().notEntryTarget(s.account(), DAY)).isNull();
        ManualOrders.Placed placed = manual().buy(s.account(), s.group(), "NIFTY", "CE", ce, "kc", DAY);
        assertThat(intentState(placed.intentId())).isEqualTo("CREATED");
    }

    /** A locked book (bid = ask) has spread 0 and can be bought; it never reaches the page as NaN. */
    @Test
    void aLockedBookHasSpreadZero() {
        quote(23150, "CE", MORNING.instant().minusSeconds(1), 80.00, 80.00);
        ManualOrders.Candidate locked = manual().pick("NIFTY", "CE", DAY).candidates().stream()
                .filter(c -> c.strike() == 23150).findFirst().orElseThrow();
        assertThat(locked.refusal()).isNull();
        assertThat(locked.spreadPct()).isZero();
        assertThat(manual().pick("NIFTY", "CE", DAY).chosen().symbol()).isEqualTo("NIFTY 23150 CE");
    }

    @Test
    void aLiveBuyOnKiteSendsTheEntryAndItsStopAndExitSellsIt() {
        Setup s = setup(BrokerType.ZERODHA, TradeMode.LIVE, V8);
        ManualOrders.Placed placed = manual().buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc-trader", DAY);
        assertThat(placed.mode()).isEqualTo("LIVE");
        try (BrokerConnections registry = registry()) {
            FollowerSimulator follower = follower(registry, s.account());
            follower.run(s.account(), s.group(), DAY);
            assertThat(intentState(placed.intentId())).isEqualTo("EXECUTED");
            assertThat(position(s.account()).get("quantity_open")).isEqualTo((long) LOT);
            assertThat(kite.requests("place")).as("the entry and its resting stop reached Kite").isEqualTo(2);
            double fill = jdbc.queryForObject("select average_fill_price from exec.order_execution where broker_account_id = ? "
                    + "and role = 'ENTRY'", Double.class, s.account().id());
            double trigger = jdbc.queryForObject("select trigger_price from exec.order_execution where broker_account_id = ? "
                    + "and role = 'STOP'", Double.class, s.account().id());
            assertThat(trigger).as("the 25 % stop").isCloseTo(fill * 0.75, org.assertj.core.data.Offset.offset(0.1));
            assertThat(kite.netQuantities()).containsEntry(kiteSymbol(23100, "CE"), (long) LOT);

            long positionId = ((Number) position(s.account()).get("id")).longValue();
            follower.flatten(s.account(), DAY, p -> 99.9, "TRADER_EXIT", positionId);
        }
        assertThat(kite.netQuantities()).as("sold at Kite, never short").containsEntry(kiteSymbol(23100, "CE"), 0L);
        assertThat(position(s.account())).satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("CLOSED");
            assertThat(p.get("exit_reason")).isEqualTo("TRADER_EXIT");
        });
    }

    @Test
    void aLiveBuyOnV13WithNoStrategiesChosenIsSentLikeOnV8() {
        // review 16 Nov 2026 (D4): v13 = v11 + manual; manual must be in the defaults too, or an account that has not
        // chosen its LIVE strategies refuses every manual buy (REJECTED_LIVE_STRATEGY)
        Setup s = setup(BrokerType.ZERODHA, TradeMode.LIVE, V13);
        ManualOrders.Placed placed = manual().buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc-trader", DAY);
        assertThat(placed.mode()).isEqualTo("LIVE");
        try (BrokerConnections registry = registry()) {
            follower(registry, s.account()).run(s.account(), s.group(), DAY);
        }
        assertThat(intentState(placed.intentId())).isEqualTo("EXECUTED");
        assertThat(kite.requests("place")).as("the entry and its resting stop reached Kite").isEqualTo(2);
    }

    @Test
    void aLiveRiskFileWithoutManualInItsStrategiesRefusesTheBuy() {
        Setup s = setup(BrokerType.ZERODHA, TradeMode.LIVE, V7);
        QuoteSource none = (d, u, t, at) -> Double.NaN;
        try (ExecutionLoop loop = new ExecutionLoop(dataSource, "ignored", "*", "worker-v7", costs, none, 1e9)
                .manualClock(MORNING)) {
            assertThatThrownBy(() -> loop.manualBuy(s.account().id(), "NIFTY", "PE", best("PE"), "kc-trader", DAY))
                    .hasMessageContaining("does not allow manual LIVE orders");
        }
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ?", Long.class,
                s.account().id())).as("refused before anything is written").isZero();
        // and if one is written anyway (an older worker), the turn refuses it
        ManualOrders.Placed placed = manual().buy(s.account(), s.group(), "NIFTY", "PE", best("PE"), "kc-trader", DAY);
        try (BrokerConnections registry = registry()) {
            follower(registry, s.account()).run(s.account(), s.group(), DAY);
        }
        assertThat(intentState(placed.intentId())).isEqualTo("REJECTED_LIVE_STRATEGY");
        assertThat(kite.requests("place")).as("nothing sent").isZero();
    }

    @Test
    void anEntryThatFilledInPartIsWhatExitSellsNeverMore(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // a 1-lot order cannot fill in part at the exchange (F&O fills are whole lots): two lots here, LIVE limit raised
        Path twoLots = dir.resolve("follower-risk.v8.yaml");
        Files.writeString(twoLots, Files.readString(Path.of(V8)).replace("max_lots_per_order: 1", "max_lots_per_order: 2"));
        Setup s = setup(BrokerType.ZERODHA, TradeMode.LIVE, twoLots.toString());
        kite.partialFills(0.5);
        ManualOrders.Placed placed = manual().buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc-trader", DAY, 2);
        assertThat(placed.quantity()).isEqualTo(2L * LOT);
        try (BrokerConnections registry = registry()) {
            FollowerSimulator follower = follower(registry, s.account());
            follower.run(s.account(), s.group(), DAY);
            assertThat(position(s.account()).get("quantity_open")).as("one of two lots filled").isEqualTo((long) LOT);
            assertThat(jdbc.queryForObject("select status from exec.order_execution where broker_account_id = ? "
                    + "and role = 'ENTRY'", String.class, s.account().id())).as("the rest cancelled").isEqualTo("CANCELLED");
            long positionId = ((Number) position(s.account()).get("id")).longValue();
            assertThat(follower.flatten(s.account(), DAY, p -> 99.9, "TRADER_EXIT", positionId))
                    .containsExactly("NIFTY 23100 CE: 65 sold at 99.90");
        }
        assertThat(jdbc.queryForList("select role || ' ' || status || ' ' || quantity_filled || '/' || quantity_requested "
                + "from exec.order_execution where broker_account_id = ? order by id", String.class, s.account().id()))
                .as("only what filled is sold, its stop off first").containsExactly("ENTRY CANCELLED 65/130",
                        "STOP CANCELLED 0/65", "EMERGENCY_EXIT FILLED 65/65");
        assertThat(kite.netQuantities()).containsEntry(kiteSymbol(23100, "CE"), 0L);
        assertThat(position(s.account()).get("status")).isEqualTo("CLOSED");
    }

    @Test
    void aBrokerRejectLeavesNothingOpenAndTheNextBuyMayGo() {
        Setup s = setup(BrokerType.ZERODHA, TradeMode.LIVE, V8);
        kite.failNext("place", KiteSimulator.Fault.INPUT_ERROR);
        ManualOrders.Placed placed = manual().buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc-trader", DAY);
        try (BrokerConnections registry = registry()) {
            follower(registry, s.account()).run(s.account(), s.group(), DAY);
        }
        assertThat(intentState(placed.intentId())).matches("(FAILED|REJECTED).*");
        assertThat(jdbc.queryForObject("select status from exec.order_execution where broker_account_id = ? and role = 'ENTRY'",
                String.class, s.account().id())).isEqualTo("REJECTED");
        assertThat(positions(s.account())).isZero();
        assertThat(kite.netQuantities().getOrDefault(kiteSymbol(23100, "CE"), 0L)).isZero();
        assertThat(manual().today(s.account().id(), DAY)).singleElement().satisfies(r -> {
            assertThat(r.get("state")).isEqualTo(intentState(placed.intentId()).replaceAll("_.*", ""));
            assertThat((List<?>) r.get("orders")).isNotEmpty();
        });
        assertThat(manual().buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc-trader", DAY).intentId())
                .as("a rejected buy is not still working").isGreaterThan(placed.intentId());
    }

    @Test
    void aKillSwitchBlocksTheBuyBeforeAndAfterItIsWritten() {
        Setup s = setup(BrokerType.ZERODHA, TradeMode.LIVE, V8);
        KillSwitches kills = new KillSwitches(dataSource);
        kills.engageAccount(s.account(), "drill", "it");
        assertThatThrownBy(() -> manual().buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc-trader", DAY))
                .hasMessageContaining("kill switch engaged").hasMessageContaining("drill");
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ?", Long.class,
                s.account().id())).isZero();
        assertThat(jdbc.queryForList("select action from exec.audit_log where broker_account_id = ?", String.class,
                s.account().id())).contains("MANUAL_BUY_REFUSED");
        jdbc.update("update exec.kill_switch set released_at = now() where broker_account_id = ?", s.account().id());

        long strategySwitch = kills.engage(s.tenant(), "STRATEGY", null, ManualOrders.STRATEGY, null, "no manual today", "it");
        assertThatThrownBy(() -> manual().buy(s.account(), s.group(), "NIFTY", "PE", best("PE"), "kc-trader", DAY))
                .hasMessageContaining("no manual today");
        jdbc.update("update exec.kill_switch set released_at = now() where id = ?", strategySwitch);

        ManualOrders.Placed placed = manual().buy(s.account(), s.group(), "NIFTY", "PE", best("PE"), "kc-trader", DAY);
        jdbc.update("insert into exec.kill_switch (scope, reason, engaged_by) values ('GLOBAL_LIVE', 'ops stop', 'it')");
        try (BrokerConnections registry = registry()) {
            follower(registry, s.account()).run(s.account(), s.group(), DAY);
        } finally {
            jdbc.update("update exec.kill_switch set released_at = now() where scope = 'GLOBAL_LIVE'");
        }
        assertThat(intentState(placed.intentId())).isEqualTo("REJECTED_KILL_SWITCH");
        assertThat(kite.requests("place")).as("nothing sent").isZero();
    }

    @Test
    void onlyFollowersInTradingHoursAndOneManualBuyAnIndexAtATime() {
        Setup s = setup(BrokerType.PAPER, TradeMode.PAPER, V3);
        Instant late = DAY.atTime(15, 12).atZone(IST).toInstant();
        Instant early = DAY.atTime(9, 10).atZone(IST).toInstant();
        assertThatThrownBy(() -> new ManualOrders(dataSource).withClock(Clock.fixed(late, IST))
                .buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc", DAY)).hasMessageContaining("only from 09:15 to 15:10");
        assertThatThrownBy(() -> new ManualOrders(dataSource).withClock(Clock.fixed(early, IST))
                .buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc", DAY)).hasMessageContaining("only from 09:15 to 15:10");
        BrokerAccount signal = new AccountStore(dataSource).byId(jdbc.queryForObject("select signal_source_account_id from "
                + "acct.copy_group where id = ?", Long.class, s.group())).orElseThrow();
        assertThatThrownBy(() -> manual().buy(signal, s.group(), "NIFTY", "CE", best("CE"), "kc", DAY))
                .hasMessageContaining("signal source");
        assertThatThrownBy(() -> manual().buy(s.account(), s.group(), "BANKNIFTY", "CE", null, "kc", DAY))
                .hasMessageContaining("NIFTY or SENSEX");
        manual().buy(s.account(), s.group(), "NIFTY", "CE", best("CE"), "kc", DAY);
        assertThatThrownBy(() -> manual().buy(s.account(), s.group(), "NIFTY", "PE", best("PE"), "kc", DAY))
                .hasMessageContaining("still working");
    }
}
