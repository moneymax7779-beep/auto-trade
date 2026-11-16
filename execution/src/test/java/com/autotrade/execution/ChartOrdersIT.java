package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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
import com.autotrade.config.ThresholdConfig;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Chart orders end to end on a PAPER follower (14 Nov 2026): the trader arms entry, stop and target lines; the engine,
 * fed index ticks, buys the best ATM through the manual path when the entry line is crossed and sells when the stop or
 * target is; the worker's turn runs both; the engine's sync follows the outcome. Also: the line rules refuse bad
 * arms; one working order per account and index; a line moved after the engine read it wins; the stop first or a
 * gap past the entry voids it; a kill switch refuses the buy; Exit now ends it; valid-until expires it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChartOrdersIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    private static final String V3 = "../config/risk/follower-risk.v3.yaml";
    private static final int LOT = 65;
    private static final double[] STRIKES = {23050, 23100, 23150, 23200, 23250};
    private static final double SPOT = 23140;
    /** Now while that is a trading minute, else 10:00 today (entries 09:15 to 14:45 by default). */
    private static final Clock MORNING = morning();
    private static final QuoteSource NONE = (d, u, t, at) -> Double.NaN;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CostModel costs;
    private static int seq;

    private static Clock morning() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        Instant at = !now.toLocalTime().isBefore(LocalTime.of(9, 16)) && now.toLocalTime().isBefore(LocalTime.of(14, 30))
                ? now.toInstant() : now.toLocalDate().atTime(10, 0).atZone(IST).toInstant();
        return Clock.fixed(at, IST);
    }

    private static String key(double strike, String side) {
        return "NSE_FO|" + (48000 + (int) (strike - 23000) / 50 * 2 + (side.equals("CE") ? 0 : 1));
    }

    private static long token(double strike, String side) {
        return InstrumentKeys.token(key(strike, side));
    }

    private static String symbol(double strike, String side) {
        return "NIFTY " + (int) strike + " " + side;
    }

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        // each loop serves every account the earlier tests made and holds one lease connection per account
        config.setMaximumPoolSize(32);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v2.yaml")));
        long snapshot = jdbc.queryForObject("insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, "
                + "instruments) values (?, 'UPSTOX:NSE-live.json.gz', 'chart-it', 10) returning id", Long.class, DAY);
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
    void market() {
        jdbc.update("delete from md.index_tick");
        jdbc.update("delete from md.option_tick");
        Instant now = MORNING.instant();
        jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, price) values "
                + "(1, ?, 'NIFTY', ?, 1, ?)", DAY, Timestamp.from(now.minusSeconds(2)), SPOT);
        for (double strike : STRIKES) {
            quote(strike, "CE", now.minusSeconds(3), 100.00, 100.20);
            quote(strike, "PE", now.minusSeconds(3), 90.00, 90.20);
        }
    }

    private static void quote(double strike, String side, Instant at, double bid, double ask) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, lot_size, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, "
                + "ask_orders, analytics_complete, depth_complete) values (1, ?, 'NIFTY', ?, 1, ?, ?, ?, ?, ?, ?, ?, "
                + "array[?]::float8[], '{6500}', '{3}', array[?]::float8[], '{6500}', '{3}', false, true)", DAY,
                Timestamp.from(at), token(strike, side), symbol(strike, side), DAY.plusDays(2), strike, side, LOT,
                (bid + ask) / 2, bid, ask);
    }

    private record Setup(long tenant, long group, BrokerAccount account) {
    }

    private Setup setup(TradeMode mode) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("chart-" + n);
        long owner = accounts.ensureUser("kc-chart-" + n, "owner " + n);
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-C" + n, 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "CHART-" + n, signal);
        long id = accounts.ensureFollowerAccount(tenant, owner, "CHT-" + n,
                mode == TradeMode.PAPER ? BrokerType.PAPER : BrokerType.ZERODHA, mode, 200_000,
                EquityMode.FIXED, DAY, V3, 100);
        new FanOut(dataSource).subscribe(group, id, List.of("ma-cross"), 1.0);
        return new Setup(tenant, group, accounts.byId(id).orElseThrow());
    }

    /** The trader's side, with the day's engine running (its heartbeat fresh, as the engine's sync writes it). */
    private static ChartOrders orders() {
        beat(0);
        return new ChartOrders(dataSource).withClock(MORNING);
    }

    /** The engine's heartbeat for the day, {@code secondsAgo} old. */
    private static void beat(int secondsAgo) {
        jdbc.update("insert into exec.chart_engine_beat (session_date, beat_at) values (?, now() - make_interval(secs => ?)) "
                + "on conflict (session_date) do update set beat_at = excluded.beat_at", java.sql.Date.valueOf(DAY),
                (double) secondsAgo);
    }

    private static ChartOrderEngine engine() {
        return new ChartOrderEngine(dataSource, DAY).withClock(MORNING).inline();
    }

    private static ExecutionLoop loop(String worker) {
        return new ExecutionLoop(dataSource, "ignored", "*", worker, costs, NONE, 1e9).manualClock(MORNING);
    }

    private static ChartOrders.Arm arm(String side, double entry, double stop, double target) {
        return new ChartOrders.Arm("NIFTY", side, entry, stop, target, 2, null, null, null);
    }

    private static String status(long id) {
        return jdbc.queryForObject("select status || coalesce(': ' || status_reason, '') from exec.chart_order where id = ?",
                String.class, id);
    }

    private static List<String> events(long id) {
        return jdbc.queryForList("select kind from exec.chart_order_event where chart_order_id = ? order by id", String.class,
                id);
    }

    private static Map<String, Object> position(BrokerAccount a) {
        return jdbc.queryForMap("select id, strategy_id, symbol, quantity_open, status, exit_reason from exec.position "
                + "where broker_account_id = ? order by id desc limit 1", a.id());
    }

    private static void tick(ChartOrderEngine engine, double price) {
        engine.onIndexTick("NIFTY", price, MORNING.instant());
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    void aBreakoutCallIsBoughtAtTheEntryLineAndSoldAtTheStopLine() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-1")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("CE", 23_170, 23_120, 23_260), "kc-trader", DAY);
            assertThat(o.status()).isEqualTo("ARMED");
            assertThat(o.kind()).isEqualTo(ChartLines.Kind.BREAKOUT);
            ChartOrderEngine engine = engine();
            assertThat(engine.watching()).containsEntry("NIFTY", 1);

            tick(engine, 23_160);
            tick(engine, 23_171);                                         // a poke inside the buffer
            assertThat(status(o.id())).isEqualTo("ARMED");
            tick(engine, 23_173);
            assertThat(status(o.id())).isEqualTo("TRIGGERED");
            // the buy: two lots of the best ATM, with the broker stop from the lines (54 points of index, ask 100.20)
            Map<String, Object> decision = jdbc.queryForMap("select d.symbol, d.lots, (d.context->>'stop_pct')::float8 stop_pct, "
                    + "d.reason from exec.signal_decision d where d.idempotency_key = ?", "CHART:" + o.id() + ":ENTER");
            assertThat(decision.get("lots")).isEqualTo(2);
            assertThat((Double) decision.get("stop_pct")).isEqualTo(ChartLines.safetyStopPct(o.lines(), 100.20));
            assertThat((String) decision.get("reason")).startsWith("chart order #" + o.id() + ": breakout CE at index 23173");

            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");
            assertThat(position(s.account())).satisfies(p -> {
                assertThat(p.get("strategy_id")).isEqualTo("manual");
                assertThat(p.get("quantity_open")).isEqualTo(2L * LOT);
                assertThat(p.get("status")).isEqualTo("OPEN");
            });
            assertThatThrownBy(() -> orders().move(s.account(), o.id(), orders().byId(o.id()).orElseThrow().version(),
                    23_165.0, null, null, "kc-trader", DAY)).hasMessageContaining("the entry of an open position");

            tick(engine, 23_200);
            tick(engine, 23_119);                                         // a poke through the stop
            assertThat(status(o.id())).isEqualTo("OPEN");
            tick(engine, 23_117);
            assertThat(status(o.id())).isEqualTo("EXITING");
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(position(s.account())).satisfies(p -> {
                assertThat(p.get("status")).isEqualTo("CLOSED");
                assertThat(p.get("exit_reason")).isEqualTo("CHART_STOP");
            });
            assertThat(status(o.id())).isEqualTo("CLOSED: closed as CHART_STOP");
            assertThat(events(o.id())).containsExactly("ARMED", "TRIGGERED", "BUY_SENT", "FILLED", "STOP", "CLOSED");
            assertThat(engine.watching()).isEmpty();
        }
    }

    @Test
    void aBreakdownDrawnWithItsStopBetweenItAndTheIndexIsStoredAndNotVoidedOnTheWayDown() {
        // 15 Nov 2026: the index (23,140) above a put's stop line (23,130): refused before, now a far breakout
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-far")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("PE", 23_100, 23_130, 23_040), "kc-trader", DAY);
            assertThat(o.kind()).isEqualTo(ChartLines.Kind.FAR_BREAKOUT);
            assertThat(orders().byId(o.id()).orElseThrow().kind()).isEqualTo(ChartLines.Kind.FAR_BREAKOUT);
            ChartOrderEngine engine = engine();
            tick(engine, 23_120);                                         // through the stop line on the way down
            tick(engine, 23_135);                                         // and back above it
            assertThat(status(o.id())).isEqualTo("ARMED");
            tick(engine, 23_097);
            assertThat(status(o.id())).isEqualTo("TRIGGERED");
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");
            tick(engine, 23_131);                                         // once bought, its stop is an ordinary stop
            assertThat(status(o.id())).isEqualTo("OPEN");
            tick(engine, 23_133);
            assertThat(status(o.id())).isEqualTo("EXITING");
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("CLOSED: closed as CHART_STOP");
            assertThat(engine.watching()).isEmpty();
        }
    }

    @Test
    void aPutBoughtOnARallyToResistanceIsSoldAtTheTargetAfterTheStopIsTrailed() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-2")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("PE", 23_160, 23_200, 23_080), "kc-trader", DAY);
            assertThat(o.kind()).isEqualTo(ChartLines.Kind.PULLBACK);
            ChartOrderEngine engine = engine();
            tick(engine, 23_159);
            assertThat(status(o.id())).isEqualTo("ARMED");
            tick(engine, 23_160);
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");

            // the trader trails the stop to 23,150 (below the entry: locks in profit) once the index is at 23,120
            jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, price) values "
                    + "(1, ?, 'NIFTY', ?, 2, 23120)", DAY, Timestamp.from(MORNING.instant().minusSeconds(1)));
            ChartOrders.Order now = orders().byId(o.id()).orElseThrow();
            ChartOrders.Order moved = orders().move(s.account(), o.id(), now.version(), null, 23_150.0, null, "kc-trader", DAY);
            assertThat(moved.stop()).isEqualTo(23_150);
            assertThatThrownBy(() -> orders().move(s.account(), o.id(), now.version(), null, 23_140.0, null, "kc-trader", DAY))
                    .as("a stale version").hasMessageContaining("changed meanwhile");
            assertThatThrownBy(() -> orders().move(s.account(), o.id(), moved.version(), null, 23_121.0, null, "kc-trader", DAY))
                    .as("past the index").hasMessageContaining("it would sell at once");
            engine.sync();

            tick(engine, 23_100);
            assertThat(status(o.id())).isEqualTo("OPEN");
            tick(engine, 23_080);
            assertThat(status(o.id())).isEqualTo("EXITING");
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(position(s.account()).get("exit_reason")).isEqualTo("CHART_TARGET");
            assertThat(status(o.id())).isEqualTo("CLOSED: closed as CHART_TARGET");
        }
    }

    @Test
    void badLinesAndASecondOrderOnTheIndexAreRefusedAndOnlyPaperAccountsArm() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-3")) {
            long group = loop.manualGroup(s.account().id());
            assertThatThrownBy(() -> orders().arm(s.account(), group, arm("CE", 23_170, 23_180, 23_260), "kc", DAY))
                    .hasMessage("a CE's stop must be below its entry and its target above it");
            assertThatThrownBy(() -> orders().arm(s.account(), group, arm("CE", 23_142, 23_100, 23_260), "kc", DAY))
                    .hasMessageContaining("it would fire at once");
            assertThatThrownBy(() -> orders().arm(s.account(), group, new ChartOrders.Arm("NIFTY", "CE", 23_170, 23_120,
                    23_260, 11, null, null, null), "kc", DAY)).hasMessage("lots: 1 to 10");
            assertThatThrownBy(() -> orders().arm(s.account(), group, new ChartOrders.Arm("NIFTY", "CE", 23_170, 23_120,
                    23_260, 1, LocalTime.of(15, 15), null, null), "kc", DAY)).hasMessageContaining("entries end at");
            ChartOrders.Order first = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            assertThatThrownBy(() -> orders().arm(s.account(), group, arm("PE", 23_110, 23_160, 23_000), "kc", DAY))
                    .hasMessageContaining("already has a working NIFTY chart order");
            orders().cancel(s.account(), first.id(), "kc");
            assertThat(status(first.id())).isEqualTo("CANCELLED: cancelled by the trader");
            assertThatThrownBy(() -> orders().cancel(s.account(), first.id(), "kc")).hasMessageContaining("nothing to cancel");

            Setup dry = setup(TradeMode.DRY_RUN);
            assertThatThrownBy(() -> orders().arm(dry.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY))
                    .hasMessageContaining("PAPER only");
            jdbc.update("delete from md.index_tick");
            assertThatThrownBy(() -> orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY))
                    .hasMessageContaining("the market feed is not running");
        }
    }

    @Test
    void theStopFirstOrAGapPastTheEntryVoidsItAndAMovedLineWins() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-4")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Order a = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_120);
            assertThat(status(a.id())).startsWith("VOID: the index reached the stop line");

            ChartOrders.Order b = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            engine.sync();
            tick(engine, 23_185);                                         // 15 past: beyond the 8-point no-chase limit
            assertThat(status(b.id())).contains("VOID: the first tick past the entry was 23185.0");

            ChartOrders.Order c = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            engine.sync();                                                // the engine reads entry 23,170
            orders().move(s.account(), c.id(), c.version(), 23_190.0, null, null, "kc", DAY);
            tick(engine, 23_173);                                         // past the old line: the move wins
            assertThat(status(c.id())).isEqualTo("ARMED");
            engine.sync();
            tick(engine, 23_175);
            assertThat(status(c.id())).isEqualTo("ARMED");
            tick(engine, 23_192);
            assertThat(status(c.id())).isEqualTo("TRIGGERED");
            assertThat(events(c.id())).containsSubsequence("ARMED", "MOVED", "TRIGGERED", "BUY_SENT");
        }
    }

    @Test
    void aKillSwitchRefusesTheBuyAndExitNowEndsAnOpenOrder() throws Exception {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-5")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Order o = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            new KillSwitches(dataSource).engageAccount(s.account(), "test", "kc");
            tick(engine, 23_173);
            assertThat(status(o.id())).startsWith("REFUSED: kill switch engaged");
            jdbc.queryForList("select id from exec.kill_switch where broker_account_id = ? and released_at is null", Long.class,
                    s.account().id()).forEach(k -> new KillSwitches(dataSource).release(k, null, "kc"));

            ChartOrders.Order p = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            engine.sync();
            tick(engine, 23_173);
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(p.id())).isEqualTo("OPEN");
            assertThat(orders().exitNow(s.account(), p.id(), "kc")).isFalse();
            List<String> result = loop.manualExit(s.account().id(), "NIFTY", "kc", DAY);
            orders().exitRan(s.account(), p.id(), "kc", result);
            assertThat(status(p.id())).as("the engine closes it once it sees the position closed").isEqualTo("OPEN");
            engine.sync();
            assertThat(status(p.id())).isEqualTo("CLOSED: closed as TRADER_EXIT");
            assertThat(events(p.id())).contains("EXIT_NOW", "CLOSED");
        }
    }

    @Test
    void anOrderExpiresAfterItsValidUntil() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-6")) {
            LocalTime now = LocalTime.ofInstant(MORNING.instant(), IST);
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()), new ChartOrders.Arm("NIFTY",
                    "CE", 23_170, 23_120, 23_260, 1, now.plusMinutes(5), null, null), "kc", DAY);
            Clock later = Clock.offset(MORNING, java.time.Duration.ofMinutes(6));
            ChartOrderEngine engine = new ChartOrderEngine(dataSource, DAY).withClock(later).inline();
            assertThat(status(o.id())).startsWith("EXPIRED: valid until");
            assertThat(engine.watching()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------------- code review 15 Nov

    private static void indexAt(double price) {
        jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, price) values "
                + "(1, ?, 'NIFTY', ?, 3, ?)", DAY, Timestamp.from(MORNING.instant().minusSeconds(1)), price);
    }

    private static long intents(BrokerAccount a, String action) {
        return jdbc.queryForObject("select count(*) from exec.trade_intent where broker_account_id = ? and action = ?",
                Long.class, a.id(), action);
    }

    @Test
    void anExitNowThatSoldNothingLeavesTheOrderOpenWithItsStopWatched() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-7")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Order o = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");

            // Exit now whose sell did not happen (no broker route, a queued lane): the order was left EXITING, unwatched
            assertThat(orders().exitNow(s.account(), o.id(), "kc")).isFalse();
            orders().exitRan(s.account(), o.id(), "kc", List.of("not exited: no broker route"));
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");
            assertThat(engine.watching()).containsEntry("NIFTY", 1);
            tick(engine, 23_117);
            assertThat(status(o.id())).isEqualTo("EXITING");
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("CLOSED: closed as CHART_STOP");
            assertThatThrownBy(() -> orders().exitNow(s.account(), o.id(), "kc")).hasMessageContaining("nothing to exit");
        }
    }

    @Test
    void anExitNowBetweenTheTriggerAndTheFillSellsWhatTheBuyBoughtAndCancelsAWaitingOrder() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-8")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Order waiting = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            assertThat(orders().exitNow(s.account(), waiting.id(), "kc")).isTrue();
            assertThat(status(waiting.id())).isEqualTo("CANCELLED: Exit now before the entry");

            ChartOrders.Order o = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            assertThat(status(o.id())).isEqualTo("TRIGGERED");
            // the trader's Exit now came before the turn took the buy, and found nothing to cancel or sell
            assertThat(orders().exitNow(s.account(), o.id(), "kc")).isFalse();
            loop.tick(DAY, 20_000);                                       // the buy fills
            engine.sync();                                                // seen filled: sold at once
            assertThat(status(o.id())).isEqualTo("EXITING");
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("CLOSED: closed as TRADER_EXIT");
            assertThat(events(o.id())).containsSubsequence("TRIGGERED", "BUY_SENT", "FILLED", "TRADER_EXIT", "CLOSED");
        }
    }

    @Test
    void anExitNowWhoseSaleFailedIsTriedAgainAfterAPause() {
        // review 16 Nov 2026 (D9): the engine's TRADER_EXIT sale was not written (no bid): back to OPEN, and only its lines
        // were watched; the trader's Exit now was never tried again
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-15")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Order o = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                    new java.util.concurrent.atomic.AtomicReference<>(MORNING.instant());
            Clock moving = new Clock() {
                public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
                public Clock withZone(java.time.ZoneId zone) { return this; }
                public java.time.Instant instant() { return now.get(); }
            };
            ChartOrderEngine engine = new ChartOrderEngine(dataSource, DAY).withClock(moving).inline();
            engine.onIndexTick("NIFTY", 23_173, now.get());
            assertThat(orders().exitNow(s.account(), o.id(), "kc")).isFalse();      // marked: sold once bought
            loop.tick(DAY, 20_000);                                       // the buy fills
            jdbc.update("delete from md.option_tick");                    // the capture stopped: no bid to sell at
            engine.sync();                                                // filled: the trader's sale, not written
            assertThat(status(o.id())).startsWith("OPEN: exit not written");
            market();                                                     // quotes again
            engine.sync();
            assertThat(status(o.id())).as("not before the pause").startsWith("OPEN");
            now.set(now.get().plus(ChartOrderEngine.EXIT_RETRY));
            engine.sync();
            assertThat(status(o.id())).as("tried again, without a tick").isEqualTo("EXITING");
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("CLOSED: closed as TRADER_EXIT");
        }
    }

    @Test
    void aChartStopSellsItsOwnPositionNeverAPanelBuyOfTheSameIndex() {
        // review 16 Nov 2026 (D9): the chart exit sold the oldest manual position of the index; its own closed and a panel
        // buy opened in the second before the engine's next sync, so a stop seen then sold the panel's position
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-16")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Order o = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");
            long own = ((Number) position(s.account()).get("id")).longValue();
            jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, exit_reason = 'MANUAL', closed_at = "
                    + "now() where id = ?", own);                         // sold by hand, booked
            ManualOrders panel = new ManualOrders(dataSource).withClock(MORNING);
            panel.buy(s.account(), group, "NIFTY", "PE", panel.pick("NIFTY", "PE", DAY).chosen().symbol(), "kc", DAY);
            loop.tick(DAY, 20_000);
            long other = ((Number) position(s.account()).get("id")).longValue();
            assertThat(other).isNotEqualTo(own);

            tick(engine, 23_117);                                         // the engine has not synced since: still OPEN
            assertThat(status(o.id())).startsWith("OPEN: exit not written").contains("#" + own + " is not open");
            loop.tick(DAY, 20_000);
            assertThat(jdbc.queryForObject("select status from exec.position where id = ?", String.class, other))
                    .as("the panel's position is not the chart order's to sell").isEqualTo("OPEN");
            engine.sync();
            assertThat(status(o.id())).startsWith("CLOSED");
            jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now() where id = ?",
                    other);                                               // the other tests' engines watch every order
        }
    }

    @Test
    void aStopMovedWhileTheIndexIsNearTheEntryIsTakenAndKeepsTheKind() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-9")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            indexAt(23_167);                                              // 3 points under the entry
            ChartOrders.Order moved = orders().move(s.account(), o.id(), o.version(), null, 23_135.0, 23_280.0, "kc", DAY);
            assertThat(moved.stop()).isEqualTo(23_135);
            assertThat(moved.target()).isEqualTo(23_280);
            assertThat(moved.kind()).isEqualTo(ChartLines.Kind.BREAKOUT);
            assertThatThrownBy(() -> orders().move(s.account(), o.id(), moved.version(), 23_168.0, null, null, "kc", DAY))
                    .as("a moved entry is checked as a new one").hasMessageContaining("it would fire at once");
        }
    }

    @Test
    void aBuyOrASellWhoseRecordWasLostIsFoundOrRepaired() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-10")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            // the buy was written but its record on the order was lost (a crash after the buy's commit)
            jdbc.update("update exec.chart_order set entry_intent_id = null, updated_at = now() - interval '1 minute' "
                    + "where id = ?", o.id());
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).as("found by its key, not refused").isEqualTo("OPEN");

            // EXITING with no sell written (a crash between the status and the intent): its lines are watched again
            jdbc.update("update exec.chart_order set status = 'EXITING', exit_trigger = 'STOP', version = version + 1, "
                    + "updated_at = now() - interval '1 minute' where id = ?", o.id());
            engine.sync();
            assertThat(status(o.id())).startsWith("OPEN: the exit was not written");
            assertThat(engine.watching()).containsEntry("NIFTY", 1);
            assertThat(intents(s.account(), "EXIT")).isZero();
        }
    }

    @Test
    void aStopWhoseWriteFailedFiresAgainAfterAPause() {
        // review 16 Nov 2026: the engine marked the order acted on before the exit ran; a failed write (OPEN -> EXITING)
        // left it marked at that version, so its chart stop never fired again that day
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-12")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");

            jdbc.execute("create or replace function exec.it_fail_exiting() returns trigger language plpgsql as $$ begin "
                    + "if old.status = 'OPEN' and new.status = 'EXITING' then raise exception 'it: database hiccup'; end if; "
                    + "return new; end $$");
            jdbc.execute("create trigger it_fail_exiting before update on exec.chart_order for each row "
                    + "execute function exec.it_fail_exiting()");
            try {
                tick(engine, 23_117);
                assertThat(status(o.id())).as("the write failed").startsWith("OPEN");
            } finally {
                jdbc.execute("drop trigger it_fail_exiting on exec.chart_order");
                jdbc.execute("drop function exec.it_fail_exiting()");
            }
            tick(engine, 23_116);
            assertThat(status(o.id())).as("not again on the next tick").startsWith("OPEN");
            engine.onIndexTick("NIFTY", 23_116, MORNING.instant().plus(ChartOrderEngine.EXIT_RETRY));
            assertThat(status(o.id())).isEqualTo("EXITING");
        }
    }

    @Test
    void nothingIsArmedOrMovedWhileNoEngineWatchesTheDaysOrders() {
        // review 16 Nov 2026 (D6): with the engine off, or its session failed or restarting, an order was armed (or an
        // open position's stop moved) that nothing would ever fire
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-13")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Order o = orders().arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            beat(30);                                                     // the engine's last sync, 30 s ago
            ChartOrders stale = new ChartOrders(dataSource).withClock(MORNING);
            assertThat(stale.engine(DAY).running()).isFalse();
            assertThatThrownBy(() -> stale.move(s.account(), o.id(), o.version(), 23_175.0, null, null, "kc", DAY))
                    .hasMessageContaining("the chart-order engine is not running").hasMessageContaining("not moved");
            stale.cancel(s.account(), o.id(), "kc");                     // a cancel needs no engine
            assertThatThrownBy(() -> stale.arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY))
                    .hasMessageContaining("the chart-order engine is not running").hasMessageContaining("nothing armed");
            jdbc.update("delete from exec.chart_engine_beat where session_date = ?", java.sql.Date.valueOf(DAY));
            assertThat(stale.engine(DAY).lastSeen()).isNull();
            assertThatThrownBy(() -> stale.arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY))
                    .hasMessageContaining("not seen today");
            engine();                                                     // its first sync beats
            assertThat(stale.engine(DAY).running()).isTrue();
            ChartOrders.Order armed = stale.arm(s.account(), group, arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            assertThat(armed.status()).isEqualTo("ARMED");
            stale.cancel(s.account(), armed.id(), "kc");                 // the other tests' engines watch every order
        }
    }

    @Test
    void anOpenPositionsStopIsMovedEvenWhileTheEngineIsDownAndAClosedEngineLeavesNoHeartbeat() {
        // second review 16 Nov 2026: refusing it kept the old, wider stop for the engine that starts again (it reads its
        // lines from the database); and a closed engine's heartbeat said "running" for 10 s more
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-17")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");
            engine.close();
            ChartOrders down = new ChartOrders(dataSource).withClock(MORNING);
            assertThat(down.engine(DAY).lastSeen()).as("cleared on close").isNull();
            ChartOrders.Order open = down.byId(o.id()).orElseThrow();
            assertThat(down.move(s.account(), o.id(), open.version(), null, 23_125.0, null, "kc", DAY).stop())
                    .as("a tighter stop, engine down").isEqualTo(23_125.0);
            jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now() where "
                    + "broker_account_id = ?", s.account().id());              // the other tests' engines watch every order
            engine().sync();
        }
    }

    @Test
    void aChartOrdersExitNowSellsItsOwnPositionOnly() throws Exception {
        // second review 16 Nov 2026: the web's Exit now on a chart order ran the index's whole manual EXIT
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-18")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            loop.tick(DAY, 20_000);
            engine.sync();
            long entry = orders().byId(o.id()).orElseThrow().entryIntentId();
            assertThat(loop.manualExit(s.account().id(), "NIFTY", "kc", DAY, entry + 1_000_000))
                    .as("another buy's exit").singleElement().asString().startsWith("no manual NIFTY position");
            assertThat(position(s.account()).get("status")).isEqualTo("OPEN");
            assertThat(loop.manualExit(s.account().id(), "NIFTY", "kc", DAY, entry)).isNotEmpty();
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(position(s.account()).get("status")).isEqualTo("CLOSED");
        }
    }

    @Test
    void anOrderIsNotArmedAsAnotherKindThanTheTicketShowed() {
        // review 16 Nov 2026 (D7): the ticket said breakout from a quote seconds old; the index had run through the entry,
        // so the server armed a pullback (buy on the way back down): another trade than the one confirmed
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-14")) {
            long group = loop.manualGroup(s.account().id());
            ChartOrders.Arm pullbackNow = new ChartOrders.Arm("NIFTY", "CE", 23_100, 23_050, 23_200, 2, null, null, null,
                    "BREAKOUT");                                          // the index is above 23,100: a pullback now
            assertThatThrownBy(() -> orders().arm(s.account(), group, pullbackNow, "kc", DAY))
                    .hasMessageContaining("these lines arm as a pullback, not the breakout the ticket showed");
            ChartOrders.Order o = orders().arm(s.account(), group, new ChartOrders.Arm("NIFTY", "CE", 23_100, 23_050, 23_200,
                    2, null, null, null, "PULLBACK"), "kc", DAY);
            assertThat(o.kind()).isEqualTo(ChartLines.Kind.PULLBACK);
            orders().cancel(s.account(), o.id(), "kc");                  // the other tests' engines watch every order
        }
    }

    @Test
    void aPaperExitWithNoBidIsNotWrittenAndFiresAgainOnlyAfterAPause() {
        Setup s = setup(TradeMode.PAPER);
        try (ExecutionLoop loop = loop("worker-chart-11")) {
            ChartOrders.Order o = orders().arm(s.account(), loop.manualGroup(s.account().id()),
                    arm("CE", 23_170, 23_120, 23_260), "kc", DAY);
            ChartOrderEngine engine = engine();
            tick(engine, 23_173);
            loop.tick(DAY, 20_000);
            engine.sync();
            assertThat(status(o.id())).isEqualTo("OPEN");

            jdbc.update("delete from md.option_tick");                    // the capture stopped: no bid to sell at
            tick(engine, 23_117);
            assertThat(status(o.id())).startsWith("OPEN: exit not written: no bid for");
            assertThat(intents(s.account(), "EXIT")).as("nothing written that the turn must reject").isZero();

            market();                                                     // quotes again
            engine.sync();
            tick(engine, 23_116);
            assertThat(status(o.id())).as("not again on the next tick").startsWith("OPEN");
            engine.onIndexTick("NIFTY", 23_116, MORNING.instant().plus(ChartOrderEngine.EXIT_RETRY));
            assertThat(status(o.id())).isEqualTo("EXITING");
            assertThat(events(o.id())).containsSubsequence("FILLED", "EXIT_NOT_WRITTEN", "STOP");
        }
    }
}
