package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import java.util.function.ToLongFunction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Desk trades, PAPER only (plan 2026-11-23 feature B; user 23 Nov 2026): one owner order to the paper books of chosen
 * accounts, each through the follower risk path; never fanned out; a group exit, and the stop watched on the captured bid.
 */
@Testcontainers(disabledWithoutDocker = true)
class DeskOrdersIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    private static final String V7 = "../config/risk/follower-risk.v7.yaml";
    private static final int LOT = 65;
    private static final double[] STRIKES = {23050, 23100, 23150, 23200, 23250};
    private static final double SPOT = 23140;
    private static final String PE = "NIFTY 23150 PE";
    private static final Clock MORNING = morning();

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

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
                + "instruments) values (?, 'UPSTOX:NSE-live.json.gz', 'desk-it', 10) returning id", Long.class, DAY);
        for (double strike : STRIKES) {
            for (String side : List.of("CE", "PE")) {
                jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, "
                        + "type, underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) "
                        + "values (?, ?, ?, 'NSE_FO', 'NSE', ?, 'NIFTY', ?, ?, ?, ?, 0.05, 1755, true)", snapshot,
                        key(strike, side), key(strike, side).substring(7), side, "NIFTY " + (int) strike + " " + side,
                        DAY.plusDays(2), strike, LOT);
            }
        }
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void market() {
        jdbc.update("delete from md.index_tick");
        jdbc.update("delete from md.option_tick");
        Instant now = MORNING.instant();
        jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, price) values "
                + "(1, ?, 'NIFTY', ?, 1, ?)", DAY, Timestamp.from(now.minusSeconds(2)), SPOT);
        for (double strike : STRIKES) {
            quote(strike, "PE", now.minusSeconds(3), 70.00, 70.20);
            quote(strike, "CE", now.minusSeconds(3), 80.00, 80.20);
        }
    }

    private static void quote(double strike, String side, Instant at, double bid, double ask) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, lot_size, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, "
                + "ask_orders, analytics_complete, depth_complete) values (1, ?, 'NIFTY', ?, 1, ?, ?, ?, ?, ?, ?, ?, "
                + "array[?]::float8[], '{6500}', '{3}', array[?]::float8[], '{6500}', '{3}', false, true)", DAY,
                Timestamp.from(at), token(strike, side), "NIFTY " + (int) strike + " " + side, DAY.plusDays(2), strike, side,
                LOT, (bid + ask) / 2, bid, ask);
    }

    // ------------------------------------------------------------------------------------------------- accounts

    private record Group(long tenant, long group) {
    }

    private Group group() {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("desk-" + n);
        long owner = accounts.ensureUser("kc-desk-" + n, "owner " + n);
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-D" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        return new Group(tenant, accounts.ensureCopyGroup(tenant, "DESK-" + n, signal));
    }

    private BrokerAccount follower(Group g, String name, BrokerType broker, double capital, boolean subscribed) {
        AccountStore accounts = new AccountStore(dataSource);
        long owner = accounts.ensureUser("kc-desk-" + g.tenant(), "o");
        long id = accounts.ensureFollowerAccount(g.tenant(), owner, name, broker, TradeMode.PAPER, capital, EquityMode.FIXED,
                DAY, V7, 100);
        if (subscribed) {
            new FanOut(dataSource).subscribe(g.group(), id, List.of("ma-cross"), 1.0);
        }
        return accounts.byId(id).orElseThrow();
    }

    /** The served group a paper account follows (as the worker's manualGroup), else refused. */
    private static final ToLongFunction<BrokerAccount> GROUP_OF = a -> jdbc.queryForList("select copy_group_id from "
            + "acct.strategy_subscription where broker_account_id = ? and enabled order by copy_group_id limit 1", Long.class,
            a.id()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException(a.displayName()
            + " follows no copy group this worker serves"));

    private static DeskOrders desk() {
        return new DeskOrders(dataSource).withClock(MORNING);
    }

    private static DeskOrders.Spec spec(DeskOrders.Sizing sizing, double value) {
        return new DeskOrders.Spec("NIFTY", "PE", PE, sizing, value, 25, null, "owner view: breakdown below VWAP");
    }

    private FollowerSimulator paper() {
        return new FollowerSimulator(dataSource, costs, FollowerRisk.load(Path.of(V7)), 1e9).withClock(MORNING);
    }

    private static String intentState(long intent) {
        return jdbc.queryForObject("select state || coalesce('_' || reject_code, '') from exec.trade_intent where id = ?",
                String.class, intent);
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    void eachChosenAccountGetsItsPaperBookOrAReasonSizedByItsEquityUnderTheDeskCap() {
        Group g = group();
        AccountStore accounts = new AccountStore(dataSource);
        BrokerAccount paperAcct = follower(g, "PAPER-A", BrokerType.PAPER, 200_000, true);
        BrokerAccount real = follower(g, "KITE-B", BrokerType.ZERODHA, 200_000, true);
        long twin = accounts.ensurePaperTwin(real.id());
        accounts.syncPaperTwin(real.id());
        BrokerAccount noTwin = follower(g, "KITE-C", BrokerType.ZERODHA, 200_000, true);
        BrokerAccount small = follower(g, "PAPER-SMALL", BrokerType.PAPER, 30_000, true);
        BrokerAccount notFollowing = follower(g, "PAPER-LONE", BrokerType.PAPER, 200_000, false);
        BrokerAccount mirror = follower(g, "DEMO-M", BrokerType.PAPER, 200_000, true);
        jdbc.update("update acct.broker_account set mirror_of = 'PAPER-1' where id = ?", mirror.id());

        // 5 % of Rs 2 L = Rs 10,000; one lot of NIFTY 23150 PE at 70.20 is Rs 4,563: 2 lots
        DeskOrders.Preview p = desk().preview(spec(DeskOrders.Sizing.PCT_EQUITY, 5), List.of(paperAcct.id(), real.id(),
                twin, noTwin.id(), small.id(), notFollowing.id(), mirror.id()), DAY, GROUP_OF);
        assertThat(p.contract().symbol()).isEqualTo(PE);
        Map<Long, DeskOrders.Target> t = new java.util.HashMap<>();
        p.targets().forEach(x -> t.put(x.requestedId(), x));
        assertThat(t.get(paperAcct.id())).satisfies(x -> {
            assertThat(x.refusal()).isNull();
            assertThat(x.accountId()).isEqualTo(paperAcct.id());
            assertThat(x.lots()).isEqualTo(2);
            assertThat(x.quantity()).isEqualTo(130);
        });
        assertThat(t.get(real.id())).as("a real account's trade goes to its paper twin").satisfies(x -> {
            assertThat(x.refusal()).isNull();
            assertThat(x.accountId()).isEqualTo(twin);
        });
        assertThat(t.get(twin).refusal()).as("the twin chosen too").contains("already in this order");
        assertThat(t.get(noTwin.id()).refusal()).contains("no paper twin");
        assertThat(t.get(small.id()).refusal()).as("5 % of Rs 30K is under one lot").contains("1 lot costs Rs 4,563");
        assertThat(t.get(notFollowing.id()).refusal()).contains("follows no copy group");
        assertThat(t.get(mirror.id()).refusal()).contains("mirrors PAPER-1");
        assertThat(p.totalQuantity()).isEqualTo(260);
        assertThat(p.depthWarning()).isNull();                                   // 260 of 6,500 offered

        // fixed 10 lots: cut to the desk cap, 20 % of Rs 2 L = Rs 40,000: 8 lots
        DeskOrders.Preview fixed = desk().preview(spec(DeskOrders.Sizing.FIXED_LOTS, 10), List.of(paperAcct.id()), DAY,
                GROUP_OF);
        assertThat(fixed.targets().getFirst().lots()).isEqualTo(8);
        assertThatThrownBy(() -> desk().preview(spec(DeskOrders.Sizing.PCT_EQUITY, 25), List.of(paperAcct.id()), DAY,
                GROUP_OF)).hasMessageContaining("at most 20");
        assertThatThrownBy(() -> desk().preview(new DeskOrders.Spec("NIFTY", "PE", "NIFTY 23250 PE",
                DeskOrders.Sizing.PCT_EQUITY, 5, 25, null, "far"), List.of(paperAcct.id()), DAY, GROUP_OF))
                .hasMessageContaining("no longer one of the strikes around ATM");
    }

    @Test
    void aSendWritesOneIntentPerAccountNeverFannedOutAndTheSameKeyWritesNothingMore() {
        Group g = group();
        BrokerAccount a = follower(g, "PAPER-S1", BrokerType.PAPER, 200_000, true);
        BrokerAccount b = follower(g, "PAPER-S2", BrokerType.PAPER, 200_000, true);
        DeskOrders.Placed placed = desk().place(spec(DeskOrders.Sizing.PCT_EQUITY, 5), List.of(a.id(), b.id()), "satya",
                "key-1", DAY, GROUP_OF);
        assertThat(placed.repeat()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where strategy_id = 'desk' and broker_account_id "
                + "in (?, ?) and execution_mode = 'PAPER' and action = 'ENTER'", Integer.class, a.id(), b.id())).isEqualTo(2);
        DeskOrders.Placed again = desk().place(spec(DeskOrders.Sizing.PCT_EQUITY, 5), List.of(a.id(), b.id()), "satya",
                "key-1", DAY, GROUP_OF);
        assertThat(again.repeat()).isTrue();
        assertThat(again.orderId()).isEqualTo(placed.orderId());
        assertThat(jdbc.queryForObject("select count(*) from exec.desk_order where created_by = 'satya' and idempotency_key = "
                + "'key-1'", Integer.class)).isEqualTo(1);
        // the group's fan-out copies no desk decision to anyone
        assertThat(new FanOut(dataSource).fanOut(g.group(), DAY)).isZero();
        // a second desk order on the index for the same account waits for the first
        assertThat(desk().preview(spec(DeskOrders.Sizing.PCT_EQUITY, 5), List.of(a.id()), DAY, GROUP_OF).targets()
                .getFirst().refusal()).contains("has not run yet");
        assertThat(jdbc.queryForList("select action from exec.audit_log where broker_account_id = ? order by id",
                String.class, a.id())).contains("DESK_ORDER");
    }

    @Test
    void itRunsThroughThePaperBookThenTheGroupExitSellsIt() {
        Group g = group();
        BrokerAccount a = follower(g, "PAPER-R1", BrokerType.PAPER, 200_000, true);
        DeskOrders.Placed placed = desk().place(spec(DeskOrders.Sizing.PCT_EQUITY, 5), List.of(a.id()), "satya", "key-r",
                DAY, GROUP_OF);
        paper().run(a, g.group(), DAY);
        Map<String, Object> position = jdbc.queryForMap("select status, quantity_open, strategy_id from exec.position where "
                + "broker_account_id = ?", a.id());
        assertThat(position).containsEntry("status", "OPEN").containsEntry("strategy_id", "desk");
        assertThat(((Number) position.get("quantity_open")).longValue()).isEqualTo(130);
        assertThat(desk().orders(DAY).getFirst()).containsEntry("state", "OPEN").containsEntry("open", 1);

        List<String> lines = desk().exit(placed.orderId(), "satya", "owner: take it off", DAY, GROUP_OF);
        assertThat(lines).singleElement().asString().startsWith("PAPER-R1: exit sent at bid 70.0");
        assertThat(desk().exit(placed.orderId(), "satya", "again", DAY, GROUP_OF)).as("its exit waits for its turn")
                .singleElement().asString().contains("already waiting");
        paper().run(a, g.group(), DAY);
        assertThat(jdbc.queryForObject("select status from exec.position where broker_account_id = ?", String.class, a.id()))
                .isEqualTo("CLOSED");
        assertThat(desk().orders(DAY).getFirst()).containsEntry("state", "CLOSED").containsEntry("open", 0);
        assertThat(desk().watch(DAY, GROUP_OF)).doesNotContain(placed.orderId());
    }

    @Test
    void theStopOnTheCapturedBidExitsEveryHolder() {
        Group g = group();
        BrokerAccount a = follower(g, "PAPER-W1", BrokerType.PAPER, 200_000, true);
        DeskOrders.Placed placed = desk().place(spec(DeskOrders.Sizing.PCT_EQUITY, 5), List.of(a.id()), "satya", "key-w",
                DAY, GROUP_OF);
        paper().run(a, g.group(), DAY);
        assertThat(desk().watch(DAY, GROUP_OF)).as("70.00 is above the stop 52.65").doesNotContain(placed.orderId());
        quote(23150, "PE", MORNING.instant().minusSeconds(1), 52.00, 52.40);
        assertThat(desk().watch(DAY, GROUP_OF)).as("other tests' orders on the contract go too").contains(placed.orderId());
        assertThat(jdbc.queryForObject("select exit_reason from exec.desk_order where id = ?", String.class,
                placed.orderId())).startsWith("DESK_STOP: bid 52.00");
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ? and strategy_id = "
                + "'desk' and action = 'EXIT' and state = 'CREATED'", Integer.class, a.id())).isEqualTo(1);
        paper().run(a, g.group(), DAY);
        assertThat(jdbc.queryForObject("select status from exec.position where broker_account_id = ?", String.class, a.id()))
                .isEqualTo("CLOSED");
    }

    @Test
    void aTwinTakesItOnALiveDayAndNothingGoesToTheRealAccount() {
        Group g = group();
        AccountStore accounts = new AccountStore(dataSource);
        BrokerAccount real = follower(g, "KITE-L", BrokerType.ZERODHA, 200_000, true);
        jdbc.update("update acct.tenant set trading_control = 'DAILY_SWITCH' where id = ?", g.tenant());
        long twin = accounts.ensurePaperTwin(real.id());
        accounts.syncPaperTwin(real.id());
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'USER_PRIVATE', 'app-desk-l', 'env:K', 'env:S') "
                + "returning id", Long.class, g.tenant());
        jdbc.update("update acct.broker_account set credential_profile_id = ?, external_broker_user_id = 'DK0001', trade_mode "
                + "= 'LIVE', live_trading_enabled = true, connection_status = 'ACTIVE' where id = ?", profile, real.id());
        jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status, created_at) "
                + "values (?, 'session-file:x', now() - interval '1 hour', now() + interval '1 day', 'ACTIVE', "
                + "now() - interval '1 hour')", real.id());
        new TradingDay(dataSource).append(g.tenant(), real.id(), TradingDay.Mode.LIVE, "IT", null);
        assertThat(new TradingDay(dataSource).entryTargetNow(g.tenant())).as("the day is LIVE on the broker")
                .isEqualTo(real.id());

        DeskOrders.Placed placed = desk().place(spec(DeskOrders.Sizing.PCT_EQUITY, 5), List.of(real.id()), "satya", "key-l",
                DAY, GROUP_OF);
        long intent = jdbc.queryForObject("select trade_intent_id from exec.desk_order_target where desk_order_id = ?",
                Long.class, placed.orderId());
        paper().run(accounts.byId(twin).orElseThrow(), g.group(), DAY);
        assertThat(intentState(intent)).as("a desk PAPER buy is not refused for the day's switch").isEqualTo("EXECUTED");
        assertThat(jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ? and strategy_id = "
                + "'desk'", Integer.class, real.id())).isZero();
    }

    @Test
    void outsideTheBuyingHoursNothingIsWritten() {
        Group g = group();
        BrokerAccount a = follower(g, "PAPER-T", BrokerType.PAPER, 200_000, true);
        Clock early = Clock.fixed(DAY.atTime(8, 30).atZone(IST).toInstant(), IST);
        assertThatThrownBy(() -> new DeskOrders(dataSource).withClock(early).place(spec(DeskOrders.Sizing.PCT_EQUITY, 5),
                List.of(a.id()), "satya", "key-t", DAY, GROUP_OF)).hasMessageContaining("desk orders only from 09:15");
        assertThat(jdbc.queryForObject("select count(*) from exec.desk_order where idempotency_key = 'key-t'",
                Integer.class)).isZero();
    }
}
