package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.config.ThresholdConfig;
import com.autotrade.kotak.KotakSimulator;
import com.autotrade.sim.CostModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The manual first target on a LIVE Kotak Neo account (user 24 Nov 2026: "also do it for real Kotak orders"), end to end
 * against the Kotak simulator (no real Kotak order: every URL is the local simulator's). A LIVE manual buy places the entry
 * and its SL stop at Kotak; the target is armed by our captured bid and fires when the bid is back at or below it. With the
 * day's Kotak session expired the sale is refused, nothing is sent, and the target is SELLING (triggered; not a try)
 * saying to log in; after a new login the next try sells through {@link ExecutionLoop#exitAtTarget}, whatever the bid: the stop is cancelled first, one SELL LIMIT in
 * product MIS under our tag, Kotak's net quantity 0 (never short), the position CLOSED as MANUAL_TARGET.
 */
@Testcontainers(disabledWithoutDocker = true)
class KotakTargetLiveIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    private static final String V13 = "../config/risk/follower-risk.v13.yaml";
    private static final BrokerRules RULES = BrokerRules.load(Path.of("..", "config", "brokers", "broker-rules.v1.yaml"));
    private static final int LOT = 65;
    private static final double[] STRIKES = {23050, 23100, 23150, 23200, 23250};
    private static final double SPOT = 23140;
    /** The buy's clock: now while that is a trading minute, else 10:00 today (LIVE entries 09:15-15:10). */
    private static final Clock MORNING = morning();

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    private static Clock morning() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        Instant at = !now.toLocalTime().isBefore(LocalTime.of(9, 16)) && now.toLocalTime().isBefore(LocalTime.of(15, 9))
                ? now.toInstant() : now.toLocalDate().atTime(10, 0).atZone(IST).toInstant();
        return Clock.fixed(at, IST);
    }

    private static String key(double strike) {
        return "NSE_FO|" + (47000 + (int) (strike - 23000) / 50 * 2);
    }

    private static long token(double strike) {
        return InstrumentKeys.token(key(strike));
    }

    private static String symbol(double strike) {
        return "NIFTY " + (int) strike + " CE";
    }

    private static String kotakSymbol(double strike) {
        return "NIFTY26X03" + (int) strike + "CE";
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
        long snapshot = jdbc.queryForObject("insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, "
                + "instruments) values (?, 'UPSTOX:NSE-it.json.gz', 'kotak-target-it', 5) returning id", Long.class, DAY);
        for (double strike : STRIKES) {
            jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                    + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values (?, ?, "
                    + "?, 'NSE_FO', 'NSE', 'CE', 'NIFTY', ?, ?, ?, ?, 0.05, 1755, true)", snapshot, key(strike),
                    key(strike).substring(7), symbol(strike), DAY.plusDays(2), strike, LOT);
        }
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    /** Our own capture's quote of a strike's call at {@code at}. */
    private static void quote(double strike, Instant at, double bid, double ask) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, lot_size, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, "
                + "ask_orders, analytics_complete, depth_complete) values (1, ?, 'NIFTY', ?, 1, ?, ?, ?, ?, 'CE', ?, ?, "
                + "array[?]::float8[], '{6500}', '{3}', array[?]::float8[], '{6500}', '{3}', false, true)", DAY,
                Timestamp.from(at), token(strike), symbol(strike), DAY.plusDays(2), strike, LOT, (bid + ask) / 2, bid, ask);
    }

    private static Map<String, Object> position(long account) {
        return jdbc.queryForMap("select id, quantity_open, status, exit_reason, average_entry from exec.position where "
                + "broker_account_id = ? order by id desc limit 1", account);
    }

    private static Map<String, Object> target(long position) {
        return jdbc.queryForMap("select status, tries, last_result from exec.position_target where position_id = ?", position);
    }

    @Test
    void aTargetOnALiveKotakAccountSellsAtKotakAndWaitsForTheLoginWhenTheSessionHasExpired() throws Exception {
        Instant now = MORNING.instant();
        jdbc.update("insert into md.index_tick (manifest_id, session_date, underlying, recv_ts, src_seq, price) values "
                + "(1, ?, 'NIFTY', ?, 1, ?)", DAY, Timestamp.from(now.minusSeconds(2)), SPOT);
        quote(23050, now.minusSeconds(3), 120.00, 120.05);
        quote(23100, now.minusSeconds(3), 100.00, 100.10);               // the tightest: the manual buy's pick
        quote(23150, now.minusSeconds(3), 80.00, 80.40);
        quote(23200, now.minusSeconds(3), 61.00, 62.00);
        quote(23250, now.minusSeconds(3), 45.00, 45.05);

        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("t-kotak-target");
        long owner = accounts.ensureUser("kc-kotak-target", "owner");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-KT", 500_000, DAY,
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "KOTAK-TARGET", signal);
        long id = accounts.ensureFollowerAccount(tenant, owner, "KOTAK-LIVE-1", BrokerType.KOTAK_NEO, TradeMode.DRY_RUN,
                200_000, EquityMode.FIXED, DAY, V13, 0);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, 'KOTAK_NEO', 'kotak-app-kt', 'store:key', 'store:secret') "
                + "returning id", Long.class, tenant);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = 'LIVE', live_trading_enabled = "
                + "true, external_broker_user_id = 'UCC-KT' where id = ?", profile, id);
        new FanOut(dataSource).subscribe(group, id, List.of("ma-cross"), 1.0);
        BrokerAccount account = accounts.byId(id).orElseThrow();
        AccountEgress loopback = AccountEgress.parse(id + "=127.0.0.1", null);   // the account's address: on every host

        try (KotakSimulator kotak = new KotakSimulator(0, "member-key", 1_500)) {
            for (double strike : STRIKES) {
                kotak.list(new KotakSimulator.Listing(Long.parseLong(key(strike).substring(7)), "nse_fo", kotakSymbol(strike), "NIFTY",
                        DAY.plusDays(2), strike, "CE", LOT, 0.05));
            }
            kotak.quote(key(23100), 100.00, 6500, 100.10, 6500, 100.05);
            kotak.funds(800_000);
            kotak.strictSessions();
            KotakSimulator.Session first = kotak.login();
            AtomicReference<Optional<BrokerSessions.Access>> session = new AtomicReference<>(Optional.of(
                    new BrokerSessions.Access("member-key", first.token(), Map.of(KotakDryRunAdapter.SID, first.sid(),
                            KotakDryRunAdapter.BASE_URL, kotak.baseUri().toString()))));
            KotakDryRunAdapter adapter = new KotakDryRunAdapter(dataSource, 1e9, a -> session.get(), kotak::book,
                    ReadLiveProbe.Settings.OFF, loopback);
            LiveBrokers liveBrokers = LiveBrokers.of("ZERODHA,KOTAK_NEO", List.of(adapter));
            CostModel costs = CostModel.from(ThresholdConfig.load(Path.of("..", "config", "costs",
                    "india-index-options-costs.v2.yaml")));

            // the LIVE manual buy at Kotak: the entry and its SL stop
            ManualOrders manual = new ManualOrders(dataSource).withClock(MORNING);
            String best = manual.pick("NIFTY", "CE", DAY).chosen().symbol();
            assertThat(best).isEqualTo(symbol(23100));
            ManualOrders.Placed placed = manual.buy(account, group, "NIFTY", "CE", best, "kc-kotak-target", DAY);
            assertThat(placed.mode()).isEqualTo("LIVE");
            try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, List.of(adapter))) {
                registry.liveBrokers(liveBrokers::live);
                FollowerRisk risk = FollowerRisk.load(Path.of(account.riskFile()));
                BrokerConnections.Route route = registry.route(account, risk);
                assertThat(route.venue()).as(route.description()).isNotNull();
                assertThat(route.description()).contains("LIVE: Kotak Neo").contains("product MIS");
                BrokerVenue venue = new BrokerVenue(dataSource, route.connection(), risk.orders().fast(), (k, u) -> 1755L,
                        o -> { });
                new FollowerSimulator(dataSource, costs, risk, venue, null).withBrokerRules(RULES).withClock(MORNING)
                        .run(account, group, DAY);
            }
            Map<String, Object> opened = position(id);
            long positionId = ((Number) opened.get("id")).longValue();
            assertThat(opened).containsEntry("status", "OPEN").containsEntry("quantity_open", (long) LOT);
            assertThat(kotak.netQuantities()).containsEntry(kotakSymbol(23100), (long) LOT);
            int placesAfterBuy = kotak.requests("place");
            assertThat(placesAfterBuy).as("the entry and its resting stop reached Kotak").isEqualTo(2);

            // review 25 Nov 2026, the backstop: a position whose entry is not LIVE on this LIVE route (it cannot happen
            // through the app: every mode change needs the account flat) is never sent to Kotak, its stop untouched
            long intent = jdbc.queryForObject("select entry_trade_intent_id from exec.position where id = ?", Long.class,
                    positionId);
            jdbc.update("update exec.trade_intent set execution_mode = 'DRY_RUN' where id = ?", intent);
            int requestsBefore = kotak.requests();
            try (ExecutionLoop loop = new ExecutionLoop(dataSource, "t-kotak-target", "KOTAK-TARGET", "worker-KT", RULES,
                    (d, u, t, at) -> Double.NaN, 1e9, List.of(adapter)).liveBrokers(liveBrokers)) {
                assertThat(loop.exitPosition(id, positionId, "kc-kotak-target", DAY)).singleElement().asString()
                        .contains("NOT exited").contains("entered DRY_RUN but this account trades LIVE now");
                assertThat(loop.exitPositionOutcome(id, positionId, "kc-kotak-target", DAY, false, Map.of()).outcome())
                        .isEqualTo(ExitOutcome.REFUSED);
            }
            assertThat(kotak.requests("place")).as("nothing placed").isEqualTo(placesAfterBuy);
            assertThat(kotak.requests("cancel")).as("its stop never cancelled").isZero();
            assertThat(kotak.netQuantities()).containsEntry(kotakSymbol(23100), (long) LOT);
            assertThat(position(id)).containsEntry("status", "OPEN");
            assertThat(kotak.requests()).as("only reads").isGreaterThanOrEqualTo(requestsBefore);
            jdbc.update("update exec.trade_intent set execution_mode = 'LIVE' where id = ?", intent);

            // the trader's target: 10 % above the entry, armed by our captured bid
            double entry = ((Number) opened.get("average_entry")).doubleValue();
            // the market-hours gate at 10:00 IST (bids and retries go by the database's clock); sales on this thread
            PositionTargets targets = new PositionTargets(dataSource).withSales(Runnable::run)
                    .withClock(Clock.fixed(DAY.atTime(10, 0).atZone(IST).toInstant(), IST));
            double price = targets.set(account, positionId, 10, "kc-kotak-target").price();
            assertThat(price).isEqualTo(PositionTargets.priceOf(entry, 10));
            try (ExecutionLoop loop = new ExecutionLoop(dataSource, "t-kotak-target", "KOTAK-TARGET", "worker-KT", RULES,
                    (d, u, t, at) -> Double.NaN, 1e9, List.of(adapter)).liveBrokers(liveBrokers)) {
                PositionTargets.Exiter engine = (a, p, audit) -> loop.exitAtTarget(a, p, DAY, audit);
                quote(23100, Instant.now(), price + 2, price + 2.1);
                assertThat(targets.watch(engine)).singleElement().asString().contains("reached");

                // the day's Kotak session has expired: nothing sent; triggered (SELLING), not a try, saying to log in
                session.set(Optional.empty());
                kotak.quote(key(23100), price - 1, 6500, price - 0.9, 6500, price - 0.95);
                quote(23100, Instant.now(), price - 1, price - 0.9);
                assertThat(targets.watch(engine)).singleElement().asString().contains("selling");
                assertThat(target(positionId)).containsEntry("status", "SELLING").containsEntry("tries", 0);
                assertThat((String) target(positionId).get("last_result")).contains("log in to the broker again")
                        .contains("session");
                assertThat(kotak.requests("place")).as("nothing placed without a session").isEqualTo(placesAfterBuy);
                assertThat(position(id)).containsEntry("status", "OPEN");
                assertThat(targets.watch(engine)).as("not again before SESSION_RETRY").isEmpty();

                // the trader logs in again: the next look after SESSION_RETRY sells at Kotak
                KotakSimulator.Session second = kotak.login();
                session.set(Optional.of(new BrokerSessions.Access("member-key", second.token(), Map.of(
                        KotakDryRunAdapter.SID, second.sid(), KotakDryRunAdapter.BASE_URL, kotak.baseUri().toString()))));
                // the next try is due (SESSION_RETRY passed); the bid has recovered above the target: the rest goes anyway
                jdbc.update("update exec.position_target set next_try_at = now() - interval '1 second' where position_id = ?",
                        positionId);
                kotak.quote(key(23100), price + 2, 6500, price + 2.1, 6500, price + 2.05);
                quote(23100, Instant.now(), price + 2, price + 2.1);
                assertThat(targets.watch(engine)).singleElement().asString().contains("the rest of the triggered target");
            }
            assertThat(position(id)).containsEntry("status", "CLOSED").containsEntry("exit_reason", "MANUAL_TARGET")
                    .containsEntry("quantity_open", 0L);
            assertThat(target(positionId)).containsEntry("status", "DONE");
            assertThat(kotak.netQuantities()).as("sold at Kotak, never short").containsEntry(kotakSymbol(23100), 0L);
            String exitTag = jdbc.queryForObject("select client_tag from exec.order_execution where broker_account_id = ? "
                    + "and role = 'EMERGENCY_EXIT' order by id desc limit 1", String.class, id);
            String stopTag = jdbc.queryForObject("select client_tag from exec.order_execution where broker_account_id = ? "
                    + "and role = 'STOP' order by id desc limit 1", String.class, id);
            try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, List.of(adapter))) {
                registry.liveBrokers(liveBrokers::live);
                List<BrokerOrder> book = registry.route(account, FollowerRisk.load(Path.of(account.riskFile())))
                        .connection().orders();
                assertThat(book).filteredOn(o -> o.side() == OrderSide.SELL && exitTag.equals(o.clientTag()))
                        .singleElement().satisfies(o -> {
                            assertThat(o.product()).isEqualTo("MIS");
                            assertThat(o.status()).isEqualTo(OrderStatus.FILLED);
                            assertThat(o.filledQuantity()).isEqualTo(LOT);
                        });
                assertThat(book).filteredOn(o -> stopTag.equals(o.clientTag())).singleElement()
                        .satisfies(o -> assertThat(o.status()).as("the stop was cancelled before the sale")
                                .isEqualTo(OrderStatus.CANCELLED));
            }
            assertThat(jdbc.queryForObject("select detail ->> 'actor' from exec.audit_log where broker_account_id = ? and "
                    + "action = 'POSITION_EXIT' order by id desc limit 1", String.class, id)).isEqualTo(PositionTargets.ACTOR);
        }
    }

    @Test
    void aLostSessionAnywhereInTheCauseChainIsNotATry() {
        com.autotrade.broker.BrokerException lost = new com.autotrade.broker.BrokerException(
                com.autotrade.broker.BrokerException.Kind.SESSION, "session expired");
        assertThat(PositionTargets.sessionLost(new RuntimeException("lane", lost))).isTrue();
        assertThat(PositionTargets.sessionLost(new com.autotrade.broker.BrokerException(
                com.autotrade.broker.BrokerException.Kind.TERMINAL, "rejected"))).isFalse();
        assertThat(PositionTargets.sessionLost(new IllegalStateException("x"))).isFalse();
        assertThat(Duration.ofSeconds(30)).isEqualTo(PositionTargets.SESSION_RETRY);
    }
}
