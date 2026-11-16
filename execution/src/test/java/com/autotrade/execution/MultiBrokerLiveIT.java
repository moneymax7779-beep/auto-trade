package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.broker.Quote;
import com.autotrade.groww.GrowwConfig;
import com.autotrade.groww.GrowwSimulator;
import com.autotrade.kotak.KotakSimulator;
import com.autotrade.upstox.orders.UpstoxConfig;
import com.autotrade.upstox.orders.UpstoxSimulator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The LIVE connections of Kotak Neo, Upstox and Groww end to end against each broker's simulator (multi-broker production
 * gaps B1, B2 and C1, 16 Nov 2026), through the LIVE-against-simulator configs: the account's session read on every
 * call, every call from the account's egress address (127.0.0.1 here, which this host has), today's instrument list
 * loaded and checked against our contract master and its lot size given to the LIVE guard. A buy fills; the guard
 * refuses a buy over the lot cap and a sell of more than is held with nothing sent; a login during the day (a new token,
 * and for Kotak a new base URL and consumer key) keeps the listener, the placed tags and the last reports, so the stop
 * placed before it is heard filling once and a tag already placed is not placed again. Without a verified egress address
 * the static-IP gate refuses LIVE, zt-tiger-v2's Upstox user is never LIVE, and none of these brokers is reachable through
 * the registry (they are not in LiveBrokers).
 */
@Testcontainers(disabledWithoutDocker = true)
class MultiBrokerLiveIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.now(IST);
    private static final String KEY = "NSE_FO|47168";
    private static final String SYMBOL = "NIFTY 23100 PE";
    private static final int LOT = 65;
    private static final String V7 = "../config/risk/follower-risk.v7.yaml";
    private static final FollowerRisk.LiveLimits ONE_LOT = new FollowerRisk.LiveLimits(1, 2, Set.of("ma-cross"), null);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static int seq;

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
        long snapshot = jdbc.queryForObject("insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, "
                + "instruments) values (?, 'UPSTOX:NSE-it.json.gz', 'it', 1) returning id", Long.class, DAY);
        jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values "
                + "(?, ?, '47168', 'NSE_FO', 'NSE', 'PE', 'NIFTY', ?, ?, 23100, ?, 0.05, 1755, true)", snapshot, KEY, SYMBOL,
                DAY.plusDays(2), LOT);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    /** A follower of {@code broker} switched LIVE, with {@code client} pinned as its broker client. */
    private static BrokerAccount liveAccount(BrokerType broker, String client) {
        int n = ++seq;
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("mb-owner", "owner");
        long id = accounts.ensureFollowerAccount(tenant, owner, broker + "-L" + n, broker, TradeMode.DRY_RUN, 500_000,
                EquityMode.FIXED, DAY, V7, 0);
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, name, "
                + "api_key_ref, api_secret_ref) values (?, ?, ?, 'store:key', 'store:secret') returning id", Long.class,
                tenant, broker.name(), "mb-app-" + n);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, trade_mode = 'LIVE', "
                + "live_trading_enabled = true, external_broker_user_id = ? where id = ?", profile, client, id);
        return accounts.byId(id).orElseThrow();
    }

    /** The account's address is 127.0.0.1 (on every host): the gate passes and every client binds to it. */
    private static AccountEgress loopbackFor(BrokerAccount account) {
        return AccountEgress.parse(account.id() + "=127.0.0.1", null);
    }

    private static OrderRequest buy(String tag, long quantity) {
        return new OrderRequest(tag, 47168, KEY, SYMBOL, "NSE_FO", OrderSide.BUY, OrderType.LIMIT, quantity, 100.2, 0, tag);
    }

    private static OrderRequest stop(String tag, long quantity) {
        return new OrderRequest(tag, 47168, KEY, SYMBOL, "NSE_FO", OrderSide.SELL, OrderType.STOP_LIMIT, quantity, 71.0,
                75.0, tag);
    }

    private static OrderRequest sell(String tag, long quantity) {
        return new OrderRequest(tag, 47168, KEY, SYMBOL, "NSE_FO", OrderSide.SELL, OrderType.LIMIT, quantity, 99.5, 0, tag);
    }

    /** What one broker's simulator offers this test. */
    private interface Sim {
        int orderCount();

        int places();

        /** The market falls through the stop's trigger. */
        void fall();
    }

    /**
     * The common LIVE scenario: a 1-lot buy and its stop; the guard's refusals with nothing sent; {@code relogin} (the
     * day's second login, changing the session the adapter reads); the stop fills after it and is heard once by the
     * listener added before it; the entry's tag is not placed again.
     */
    private static void scenario(BrokerConnection live, Sim sim, Runnable relogin) {
        assertThat(live).isInstanceOf(LiveGuard.class);
        List<OrderUpdate> updates = new ArrayList<>();
        live.onUpdate(updates::add);
        live.place(buy("F1E1", LOT));
        live.place(stop("F1S1", LOT));
        live.poll();
        assertThat(updates).filteredOn(u -> u.clientOrderId().equals("F1E1")).last()
                .satisfies(u -> assertThat(u.status()).isEqualTo(OrderStatus.FILLED));
        assertThat(live.orderByTag("F1S1").orElseThrow().status()).isEqualTo(OrderStatus.TRIGGER_PENDING);

        int places = sim.places();
        assertThatThrownBy(() -> live.place(buy("F2E1", 2L * LOT))).isInstanceOf(LiveGuard.Refusal.class)
                .hasMessageContaining("over 1 lot(s) of 65");
        assertThatThrownBy(() -> live.place(sell("F1X1", LOT))).as("the stop already sells what is held")
                .isInstanceOf(LiveGuard.Refusal.class).hasMessageContaining("never short");
        assertThat(sim.places()).as("the guard sent nothing").isEqualTo(places);

        relogin.run();
        int heard = updates.size();
        assertThat(live.poll()).as("reports from before the login are remembered").isZero();
        sim.fall();
        assertThat(live.poll()).isEqualTo(1);
        assertThat(updates.subList(heard, updates.size())).singleElement().satisfies(u -> {
            assertThat(u.clientOrderId()).isEqualTo("F1S1");
            assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
        });
        live.place(buy("F1E1", LOT));
        assertThat(sim.orderCount()).as("a tag placed before the login is never placed again").isEqualTo(2);
        assertThat(live.poll()).isZero();
    }

    // ------------------------------------------------------------------ Kotak Neo

    @Test
    void kotakLiveTradesFromTheAccountsAddressAndKeepsItsOrdersAcrossANewLoginOnAnotherBaseUrl() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.KOTAK_NEO, "UCC1");
        try (KotakSimulator kotak = new KotakSimulator(0, "member-key", 1_500)) {
            kotak.list(new KotakSimulator.Listing(47168, "nse_fo", "NIFTY26X1023100PE", "NIFTY", DAY.plusDays(2), 23100,
                    "PE", LOT, 0.05));
            kotak.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
            kotak.strictSessions();
            KotakSimulator.Session first = kotak.login();
            AtomicReference<Optional<BrokerSessions.Access>> session = new AtomicReference<>(Optional.of(
                    new BrokerSessions.Access("member-key", first.token(), Map.of(KotakDryRunAdapter.SID, first.sid(),
                            KotakDryRunAdapter.BASE_URL, kotak.baseUri().toString()))));
            KotakDryRunAdapter adapter = new KotakDryRunAdapter(dataSource, 1e9, a -> session.get(), kotak::book,
                    ReadLiveProbe.Settings.OFF, loopbackFor(account));
            BrokerAdapter.Live live = adapter.live(account, ONE_LOT);
            assertThat(live.isRefused()).as(live.description()).isFalse();
            assertThat(live.description()).contains("from 127.0.0.1").contains("product MIS");
            scenario(live.connection(), new Sim() {
                public int orderCount() {
                    return kotak.orderCount();
                }

                public int places() {
                    return kotak.requests("place");
                }

                public void fall() {
                    kotak.quote(KEY, 74.0, 6500, 74.5, 6500, 74.9);
                }
            }, () -> {
                KotakSimulator.Session second = kotak.login();          // the first token is no longer valid
                URI otherName = URI.create("http://localhost:" + kotak.baseUri().getPort());
                session.set(Optional.of(new BrokerSessions.Access("member-key-2", second.token(), Map.of(
                        KotakDryRunAdapter.SID, second.sid(), KotakDryRunAdapter.BASE_URL, otherName.toString()))));
            });
            assertThat(live.connection().product()).isEqualTo("MIS");
            session.set(Optional.empty());
            assertThatThrownBy(() -> live.connection().orders()).isInstanceOf(BrokerException.class)
                    .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(BrokerException.Kind.SESSION));
        }
    }

    // ------------------------------------------------------------------ Upstox

    @Test
    void upstoxLiveTradesFromTheAccountsAddressAndKeepsItsOrdersAcrossANewToken() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.UPSTOX, "AB1234");
        try (UpstoxSimulator upstox = new UpstoxSimulator(0, 1_500)) {
            upstox.list(new UpstoxSimulator.Listing(KEY, SYMBOL, "NIFTY", DAY.plusDays(2), 23100, "PE", LOT, 0.05));
            upstox.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
            upstox.strictSessions();
            AtomicReference<Optional<BrokerSessions.Access>> session = new AtomicReference<>(Optional.of(
                    new BrokerSessions.Access("member-api-key", upstox.login().accessToken())));
            UpstoxDryRunAdapter adapter = new UpstoxDryRunAdapter(dataSource, 1e9, a -> session.get(),
                    UpstoxConfig.at(upstox.baseUri()), upstox::book, ReadLiveProbe.Settings.OFF,
                    UpstoxConfig.liveSimulator(upstox.baseUri()), loopbackFor(account), List.of());
            BrokerAdapter.Live live = adapter.live(account, ONE_LOT);
            assertThat(live.isRefused()).as(live.description()).isFalse();
            assertThat(live.description()).contains("from 127.0.0.1").contains("product I");
            scenario(live.connection(), new Sim() {
                public int orderCount() {
                    return upstox.orderCount();
                }

                public int places() {
                    return upstox.requests("place");
                }

                public void fall() {
                    upstox.quote(KEY, 74.0, 6500, 74.5, 6500, 74.9);
                }
            }, () -> session.set(Optional.of(new BrokerSessions.Access("member-api-key",
                    upstox.login().accessToken()))));
            assertThat(upstox.tokensSentToAssets()).as("the instrument file never sees the token").isZero();
        }
    }

    @Test
    void ztTigersUpstoxUserAndAConfiguredDeniedUserAreNeverLive() throws Exception {
        try (UpstoxSimulator upstox = new UpstoxSimulator(0, 1_500)) {
            for (String user : new String[] {"BM6462", "bm6462", "ZZ9999"}) {
                BrokerAccount account = liveAccount(BrokerType.UPSTOX, user);
                UpstoxDryRunAdapter adapter = new UpstoxDryRunAdapter(dataSource, 1e9,
                        a -> Optional.of(new BrokerSessions.Access("k", "t")), UpstoxConfig.at(upstox.baseUri()),
                        upstox::book, ReadLiveProbe.Settings.OFF, UpstoxConfig.liveSimulator(upstox.baseUri()),
                        loopbackFor(account), List.of("zz9999"));
                BrokerAdapter.Live live = adapter.live(account, ONE_LOT);
                assertThat(live.isRefused()).as(user).isTrue();
                assertThat(live.description()).contains("never traded LIVE here");
            }
            BrokerAccount unpinned = liveAccount(BrokerType.UPSTOX, null);
            assertThat(new UpstoxDryRunAdapter(dataSource, 1e9, BrokerSessions.NONE, UpstoxConfig.at(upstox.baseUri()),
                    upstox::book, ReadLiveProbe.Settings.OFF, UpstoxConfig.liveSimulator(upstox.baseUri()),
                    loopbackFor(unpinned), List.of()).live(unpinned, ONE_LOT).description())
                    .contains("no Upstox client pinned");
            assertThat(upstox.requests()).as("nothing reached Upstox").isZero();
            assertThatThrownBy(() -> new UpstoxDryRunAdapter(dataSource, 1e9, BrokerSessions.NONE,
                    UpstoxConfig.at(upstox.baseUri()), upstox::book, ReadLiveProbe.Settings.OFF,
                    UpstoxConfig.at(upstox.baseUri()), AccountEgress.NONE, List.of()))
                    .as("a read config is never the LIVE one").hasMessageContaining("LIVE config");
            assertThatThrownBy(() -> new UpstoxDryRunAdapter(dataSource, 1e9, BrokerSessions.NONE,
                    UpstoxConfig.simulator(upstox.baseUri()), upstox::book, ReadLiveProbe.Settings.OFF,
                    UpstoxConfig.live(), AccountEgress.NONE, List.of()))
                    .as("the READ_LIVE reads never take an order-capable config").hasMessageContaining("read-only");
        }
    }

    // ------------------------------------------------------------------ Groww

    @Test
    void growwLiveTradesFromTheAccountsAddressAndKeepsItsOrdersAcrossANewToken() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.GROWW, "GW1234");
        try (GrowwSimulator groww = new GrowwSimulator(0, 1_500)) {
            groww.list(new GrowwSimulator.Listing(KEY, SYMBOL, "NIFTY", DAY.plusDays(2), 23100, "PE", LOT, 0.05));
            groww.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
            groww.strictSessions();
            AtomicReference<Optional<BrokerSessions.Access>> session = new AtomicReference<>(Optional.of(
                    new BrokerSessions.Access(null, groww.login().accessToken())));
            GrowwDryRunAdapter adapter = new GrowwDryRunAdapter(dataSource, 1e9, a -> session.get(),
                    GrowwConfig.at(groww.baseUri()), groww::book, ReadLiveProbe.Settings.OFF,
                    GrowwConfig.liveSimulator(groww.baseUri()), loopbackFor(account));
            BrokerAdapter.Live live = adapter.live(account, ONE_LOT);
            assertThat(live.isRefused()).as(live.description()).isFalse();
            assertThat(live.description()).contains("from 127.0.0.1").contains("product MIS");
            scenario(live.connection(), new Sim() {
                public int orderCount() {
                    return groww.orderCount();
                }

                public int places() {
                    return groww.requests("place");
                }

                public void fall() {
                    groww.quote(KEY, 74.0, 6500, 74.5, 6500, 74.9);
                }
            }, () -> session.set(Optional.of(new BrokerSessions.Access(null, groww.login().accessToken()))));
            assertThat(groww.tokensSentToAssets()).as("the instrument CSV never sees the token").isZero();
        }
    }

    // ------------------------------------------------------------------ the static-IP gate and reachability

    @Test
    void withoutAVerifiedEgressAddressLiveIsRefusedAndNothingIsSent() throws Exception {
        try (KotakSimulator kotak = new KotakSimulator(0, "k", 1_500);
             UpstoxSimulator upstox = new UpstoxSimulator(0, 1_500);
             GrowwSimulator groww = new GrowwSimulator(0, 1_500)) {
            Function<String, Quote> noQuotes = k -> null;
            BrokerSessions any = a -> Optional.of(new BrokerSessions.Access("k", "t", Map.of(KotakDryRunAdapter.SID, "s",
                    KotakDryRunAdapter.BASE_URL, kotak.baseUri().toString())));
            BrokerAccount k = liveAccount(BrokerType.KOTAK_NEO, "UCC2");
            BrokerAccount u = liveAccount(BrokerType.UPSTOX, "AB5678");
            BrokerAccount g = liveAccount(BrokerType.GROWW, "GW5678");
            for (AccountEgress egress : new AccountEgress[] {AccountEgress.NONE,
                    AccountEgress.parse(k.id() + "=192.0.2.77," + u.id() + "=192.0.2.78," + g.id() + "=192.0.2.79", null)}) {
                List<BrokerAdapter.Live> lives = List.of(
                        new KotakDryRunAdapter(dataSource, 1e9, any, noQuotes, ReadLiveProbe.Settings.OFF, egress)
                                .live(k, ONE_LOT),
                        new UpstoxDryRunAdapter(dataSource, 1e9, any, UpstoxConfig.at(upstox.baseUri()), noQuotes,
                                ReadLiveProbe.Settings.OFF, UpstoxConfig.liveSimulator(upstox.baseUri()), egress,
                                List.of()).live(u, ONE_LOT),
                        new GrowwDryRunAdapter(dataSource, 1e9, any, GrowwConfig.at(groww.baseUri()), noQuotes,
                                ReadLiveProbe.Settings.OFF, GrowwConfig.liveSimulator(groww.baseUri()), egress)
                                .live(g, ONE_LOT));
                for (BrokerAdapter.Live live : lives) {
                    assertThat(live.isRefused()).isTrue();
                    assertThat(live.description()).containsAnyOf("registered static IP", "not on this host");
                }
            }
            KotakDryRunAdapter unassigned = new KotakDryRunAdapter(dataSource, 1e9, any, noQuotes,
                    ReadLiveProbe.Settings.OFF, AccountEgress.parse(k.id() + "=192.0.2.77", null));
            assertThat(unassigned.egressRefusal(k)).contains("192.0.2.77");
            FollowerRisk v7 = FollowerRisk.load(Path.of(V7));
            BrokerAccount dryRun = new BrokerAccount(k.id(), k.tenantId(), k.appUserId(), k.brokerType(), k.displayName(),
                    k.legacyLabel(), k.role(), TradeMode.DRY_RUN, false, k.allocatedCapital(), k.equityMode(),
                    k.equityFrom(), "../config/risk/follower-risk.v6.yaml", k.sizeStepPct(), true);
            try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, List.of(unassigned))) {
                BrokerConnections.Route route = registry.route(dryRun, FollowerRisk.load(Path.of(dryRun.riskFile())));
                assertThat(route.venue()).as("a READ_LIVE route from an address this host lacks").isNull();
                assertThat(route.description()).contains("not on this host");
            }
            try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, List.of(
                    new KotakDryRunAdapter(dataSource, 1e9, any, noQuotes, ReadLiveProbe.Settings.OFF, loopbackFor(k))))) {
                BrokerConnections.Route route = registry.route(k, v7);
                assertThat(route.venue()).as("Kotak is not a LIVE broker: its connection is unreachable").isNull();
                assertThat(route.description()).isEqualTo("LIVE refused: KOTAK_NEO is not a LIVE broker yet");
            }
            assertThat(kotak.requests() + upstox.requests() + groww.requests()).as("nothing reached a broker").isZero();
        }
    }

    @Test
    void aLiveAccountOfABrokerTakenOutOfTheLiveSettingGetsNoRoute() throws Exception {
        // review 16 Nov 2026: the routes checked only the adapter; removing ZERODHA from autotrade.execution.live-brokers
        // and restarting left a LIVE account trading while the pages said it could not
        BrokerAccount kite = liveAccount(BrokerType.ZERODHA, "AB1234");
        List<BrokerAdapter> adapters = List.of(new ZerodhaDryRunAdapter(dataSource, 1e9));
        FollowerRisk v7 = FollowerRisk.load(Path.of(V7));
        try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, adapters)) {
            registry.liveBrokers(LiveBrokers.of("", adapters)::live);
            BrokerConnections.Route route = registry.route(kite, v7);
            assertThat(route.venue()).isNull();
            assertThat(route.description()).isEqualTo("LIVE refused: ZERODHA is not configured LIVE on this worker");
        }
        try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, adapters)) {
            registry.liveBrokers(LiveBrokers.of(LiveBrokers.DEFAULT, adapters)::live);
            assertThat(registry.route(kite, v7).venue()).as("configured: the Kite LIVE route opens").isNotNull();
        }
    }
}
