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
import com.autotrade.zerodha.KiteSimulator;
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

    /**
     * Holds the session lookup of the threads whose name starts with {@code holder} (they hold the connection's session
     * lock there, about to build its gateway) until {@link #addListenerWhileTheGatewayIsBuilt} releases it.
     */
    private static final class HeldSessions implements BrokerSessions {
        final java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        private final String holder;
        private final BrokerSessions.Access access;

        HeldSessions(String holder, BrokerSessions.Access access) {
            this.holder = holder;
            this.access = access;
        }

        @Override
        public Optional<BrokerSessions.Access> access(BrokerAccount account) {
            if (Thread.currentThread().getName().startsWith(holder)) {
                inside.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Optional.of(access);
        }
    }

    /**
     * {@code onUpdate} on another thread while the holder of {@code held} is inside the session lock, about to build the
     * gateway: once that thread waits on the connection's own monitor ({@code monitor}, its class name), the holder is let
     * go, builds the gateway and hands it the listeners in the list; then {@code onUpdate} goes on.
     */
    private static void addListenerWhileTheGatewayIsBuilt(BrokerConnection connection, HeldSessions held, String monitor,
                                                          java.util.function.Consumer<OrderUpdate> listener)
            throws InterruptedException {
        try {
            assertThat(held.inside.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("the holder is inside the session "
                    + "lock").isTrue();
            Thread adding = Thread.ofPlatform().daemon().name("adds-listener").start(() -> connection.onUpdate(listener));
            java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
            java.lang.management.ThreadInfo info = null;
            for (long until = System.nanoTime() + 10_000_000_000L; System.nanoTime() < until && adding.isAlive(); ) {
                info = threads.getThreadInfo(adding.threadId());
                if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockName() != null
                        && info.getLockName().contains(monitor)) {
                    break;
                }
                Thread.sleep(1);
            }
            assertThat(info).isNotNull();
            assertThat(info.getLockName()).as("onUpdate waits on the session's lock").contains(monitor);
            held.release.countDown();
            adding.join(10_000);
            assertThat(adding.isAlive()).isFalse();
        } finally {
            held.release.countDown();                           // never leave the holder parked
        }
    }

    /**
     * The race behind the Kotak case's 1-in-3 failure (16 Nov 2026): {@link #live} starts the scrip-master warm-up on its
     * own thread, and that thread's first session call builds the gateway and hands it every listener added so far. A
     * listener added at that moment (put in the list, then the gateway checked under the lock) was handed to the gateway
     * twice, so every order report reached it twice: the same FILLED, with its fill quantity, delivered two times. Here the
     * warm-up is held inside the session call while {@code onUpdate} runs, every time.
     */
    @Test
    void kotakListenerAddedWhileTheWarmUpBuildsTheGatewayHearsEachReportOnce() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.KOTAK_NEO, "UCC3");
        try (KotakSimulator kotak = new KotakSimulator(0, "member-key", 1_500)) {
            kotak.list(new KotakSimulator.Listing(47168, "nse_fo", "NIFTY26X1023100PE", "NIFTY", DAY.plusDays(2), 23100,
                    "PE", LOT, 0.05));
            kotak.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
            KotakSimulator.Session first = kotak.login();
            HeldSessions held = new HeldSessions("kotak-live-warmup-", new BrokerSessions.Access("member-key",
                    first.token(), Map.of(KotakDryRunAdapter.SID, first.sid(), KotakDryRunAdapter.BASE_URL,
                            kotak.baseUri().toString())));
            BrokerAdapter.Live live = new KotakDryRunAdapter(dataSource, 1e9, held, kotak::book,
                    ReadLiveProbe.Settings.OFF, loopbackFor(account)).live(account, ONE_LOT);
            assertThat(live.isRefused()).as(live.description()).isFalse();
            List<OrderUpdate> updates = new java.util.concurrent.CopyOnWriteArrayList<>();
            addListenerWhileTheGatewayIsBuilt(live.connection(), held, "KotakSession", updates::add);

            live.connection().place(buy("F3E1", LOT));
            assertThat(live.connection().poll()).isEqualTo(1);
            assertThat(updates).singleElement().satisfies(u -> {
                assertThat(u.clientOrderId()).isEqualTo("F3E1");
                assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
                assertThat(u.lastFillQuantity()).isEqualTo(LOT);
            });
        }
    }

    /** The same race on the Kite connection (same pattern; it has no warm-up thread, so another call builds the gateway). */
    @Test
    void kiteListenerAddedWhileAnotherCallBuildsTheGatewayHearsEachReportOnce() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.ZERODHA, "AB4321");
        try (KiteSimulator kite = new KiteSimulator(0, "real-key", "real-secret", 1_500)) {
            kite.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26X0323100PE", "NIFTY", DAY.plusDays(2).toString(),
                    23100, "PE", LOT, 0.05));
            kite.depth(KEY, List.of(new Quote.Level(99.5, 6500)), List.of(new Quote.Level(100.0, 6500)), 99.8);
            kite.funds(800_000);
            HeldSessions held = new HeldSessions("reads-the-book", new BrokerSessions.Access("real-key", "day-token"));
            BrokerAdapter.Live live = new ZerodhaDryRunAdapter(dataSource, 1e9, held, kite.baseUri(),
                    loopbackFor(account)).live(account, ONE_LOT);
            assertThat(live.isRefused()).as(live.description()).isFalse();
            Thread reads = Thread.ofPlatform().daemon().name("reads-the-book").start(() -> live.connection().orders());
            List<OrderUpdate> updates = new java.util.concurrent.CopyOnWriteArrayList<>();
            addListenerWhileTheGatewayIsBuilt(live.connection(), held, "KiteReads", updates::add);
            reads.join(10_000);

            live.connection().place(buy("F4E1", LOT));
            assertThat(live.connection().poll()).isEqualTo(1);
            assertThat(updates).singleElement().satisfies(u -> {
                assertThat(u.clientOrderId()).isEqualTo("F4E1");
                assertThat(u.status()).isEqualTo(OrderStatus.FILLED);
                assertThat(u.lastFillQuantity()).isEqualTo(LOT);
            });
        }
    }

    /** Review 16 Nov 2026 (D1): what the account carried in reaches the LIVE guard through every layer of the Kite route. */
    @Test
    void kiteLiveConnectionPassesWhatWasCarriedIntoTodayToTheGuard() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.ZERODHA, "AB4322");
        try (KiteSimulator kite = new KiteSimulator(0, "real-key", "real-secret", 1_500)) {
            kite.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26X0323100PE", "NIFTY", DAY.plusDays(2).toString(),
                    23100, "PE", LOT, 0.05));
            kite.carried(KEY, LOT);
            BrokerAdapter.Live live = new ZerodhaDryRunAdapter(dataSource, 1e9,
                    a -> Optional.of(new BrokerSessions.Access("real-key", "day-token")), kite.baseUri(),
                    loopbackFor(account)).live(account, ONE_LOT);
            assertThat(live.connection()).isInstanceOf(LiveGuard.class);
            assertThat(live.connection().carriedIn()).containsExactlyEntriesOf(Map.of(KEY, (long) LOT));
        }
    }

    /** Review 16 Nov 2026 (D1b): Kotak's carry (cfBuyQty - cfSellQty, any product) reaches the LIVE guard. */
    @Test
    void kotakLiveConnectionPassesWhatWasCarriedIntoTodayToTheGuard() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.KOTAK_NEO, "UCC4");
        try (KotakSimulator kotak = new KotakSimulator(0, "member-key", 1_500)) {
            kotak.list(new KotakSimulator.Listing(47168, "nse_fo", "NIFTY26X1023100PE", "NIFTY", DAY.plusDays(2), 23100,
                    "PE", LOT, 0.05));
            kotak.carried(KEY, -LOT);
            KotakSimulator.Session first = kotak.login();
            BrokerSessions.Access access = new BrokerSessions.Access("member-key", first.token(), Map.of(
                    KotakDryRunAdapter.SID, first.sid(), KotakDryRunAdapter.BASE_URL, kotak.baseUri().toString()));
            BrokerAdapter.Live live = new KotakDryRunAdapter(dataSource, 1e9, a -> Optional.of(access), kotak::book,
                    ReadLiveProbe.Settings.OFF, loopbackFor(account)).live(account, ONE_LOT);
            assertThat(live.connection()).isInstanceOf(LiveGuard.class);
            assertThat(live.connection().carriedIn()).as("a carried short").containsExactlyEntriesOf(Map.of(KEY, (long) -LOT));
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

    /** Review 16 Nov 2026 (D1b): Upstox's carry (a D-product overnight row) reaches the LIVE guard. */
    @Test
    void upstoxLiveConnectionPassesWhatWasCarriedIntoTodayToTheGuard() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.UPSTOX, "AB4323");
        try (UpstoxSimulator upstox = new UpstoxSimulator(0, 1_500)) {
            upstox.list(new UpstoxSimulator.Listing(KEY, SYMBOL, "NIFTY", DAY.plusDays(2), 23100, "PE", LOT, 0.05));
            upstox.carried(KEY, LOT);
            String token = upstox.login().accessToken();
            BrokerAdapter.Live live = new UpstoxDryRunAdapter(dataSource, 1e9,
                    a -> Optional.of(new BrokerSessions.Access("member-api-key", token)), UpstoxConfig.at(upstox.baseUri()),
                    upstox::book, ReadLiveProbe.Settings.OFF, UpstoxConfig.liveSimulator(upstox.baseUri()),
                    loopbackFor(account), List.of()).live(account, ONE_LOT);
            assertThat(live.connection()).isInstanceOf(LiveGuard.class);
            assertThat(live.connection().carriedIn()).containsExactlyEntriesOf(Map.of(KEY, (long) LOT));
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
                // one enabled account per broker client (V50): the next case pins the same client
                jdbc.update("update acct.broker_account set enabled = false where id = ?", account.id());
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

    /**
     * User 25 Nov 2026: zt-tiger-v2's Upstox user is LIVE for a listed account only; the same client on an unlisted
     * account, or another denied user on a listed one, stays refused.
     */
    @Test
    void ztTigersUpstoxUserIsLiveOnlyForAListedAccount() throws Exception {
        try (UpstoxSimulator upstox = new UpstoxSimulator(0, 1_500)) {
            upstox.list(new UpstoxSimulator.Listing(KEY, SYMBOL, "NIFTY", DAY.plusDays(2), 23100, "PE", LOT, 0.05));
            String token = upstox.login().accessToken();
            BrokerAccount listed = liveAccount(BrokerType.UPSTOX, "BM6462");
            BrokerAdapter.Live live = new UpstoxDryRunAdapter(dataSource, 1e9,
                    a -> Optional.of(new BrokerSessions.Access("member-api-key", token)), UpstoxConfig.at(upstox.baseUri()),
                    upstox::book, ReadLiveProbe.Settings.OFF, UpstoxConfig.liveSimulator(upstox.baseUri()),
                    loopbackFor(listed), List.of("zz9999"), Set.of(listed.id())).live(listed, ONE_LOT);
            assertThat(live.isRefused()).as(live.description()).isFalse();
            jdbc.update("update acct.broker_account set enabled = false where id = ?", listed.id());

            // user 25 Nov 2026 (review): a LIVE connection prices only from a quote at most 3 s old
            java.util.concurrent.atomic.AtomicReference<java.time.Instant> at =
                    new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now().minusMillis(3_500));
            BrokerAccount priced = liveAccount(BrokerType.UPSTOX, "AB7777");
            BrokerAdapter.Live pricedLive = new UpstoxDryRunAdapter(dataSource, 1e9,
                    a -> Optional.of(new BrokerSessions.Access("member-api-key", token)), UpstoxConfig.at(upstox.baseUri()),
                    k -> new com.autotrade.broker.Quote(k, 99.5, 65, 100.0, 65, 99.8, at.get(), List.of(), List.of()),
                    ReadLiveProbe.Settings.OFF, UpstoxConfig.liveSimulator(upstox.baseUri()), loopbackFor(priced),
                    List.of()).live(priced, ONE_LOT);
            assertThatThrownBy(() -> pricedLive.connection().quote(KEY)).isInstanceOf(BrokerException.class)
                    .hasMessageContaining("s old")
                    .satisfies(e -> assertThat(((BrokerException) e).kind()).isEqualTo(BrokerException.Kind.RETRYABLE));
            at.set(java.time.Instant.now().minusMillis(500));
            assertThat(pricedLive.connection().quote(KEY).ask()).isEqualTo(100.0);
            jdbc.update("update acct.broker_account set enabled = false where id = ?", priced.id());

            BrokerAccount other = liveAccount(BrokerType.UPSTOX, "BM6462");
            assertThat(new UpstoxDryRunAdapter(dataSource, 1e9, a -> Optional.of(new BrokerSessions.Access("k", "t")),
                    UpstoxConfig.at(upstox.baseUri()), upstox::book, ReadLiveProbe.Settings.OFF,
                    UpstoxConfig.liveSimulator(upstox.baseUri()), loopbackFor(other), List.of(), Set.of(listed.id()))
                    .live(other, ONE_LOT).description()).as("not listed").contains("never traded LIVE here");
            jdbc.update("update acct.broker_account set enabled = false where id = ?", other.id());

            BrokerAccount denied = liveAccount(BrokerType.UPSTOX, "ZZ9999");
            assertThat(new UpstoxDryRunAdapter(dataSource, 1e9, a -> Optional.of(new BrokerSessions.Access("k", "t")),
                    UpstoxConfig.at(upstox.baseUri()), upstox::book, ReadLiveProbe.Settings.OFF,
                    UpstoxConfig.liveSimulator(upstox.baseUri()), loopbackFor(denied), List.of("zz9999"),
                    Set.of(denied.id())).live(denied, ONE_LOT).description())
                    .as("listing never frees another denied user").contains("never traded LIVE here");
            jdbc.update("update acct.broker_account set enabled = false where id = ?", denied.id());
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

    /** Review 16 Nov 2026 (D1b): Groww's carry (carry_forward credit - debit, any product) reaches the LIVE guard. */
    @Test
    void growwLiveConnectionPassesWhatWasCarriedIntoTodayToTheGuard() throws Exception {
        BrokerAccount account = liveAccount(BrokerType.GROWW, "GW4324");
        try (GrowwSimulator groww = new GrowwSimulator(0, 1_500)) {
            groww.list(new GrowwSimulator.Listing(KEY, SYMBOL, "NIFTY", DAY.plusDays(2), 23100, "PE", LOT, 0.05));
            groww.carried(KEY, LOT);
            String token = groww.login().accessToken();
            BrokerAdapter.Live live = new GrowwDryRunAdapter(dataSource, 1e9,
                    a -> Optional.of(new BrokerSessions.Access(null, token)), GrowwConfig.at(groww.baseUri()), groww::book,
                    ReadLiveProbe.Settings.OFF, GrowwConfig.liveSimulator(groww.baseUri()), loopbackFor(account))
                    .live(account, ONE_LOT);
            assertThat(live.connection()).isInstanceOf(LiveGuard.class);
            assertThat(live.connection().carriedIn()).containsExactlyEntriesOf(Map.of(KEY, (long) LOT));
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
                // Kotak step 4 (18 Nov 2026): Kotak offers LIVE; a worker that does not list it LIVE opens no route
                registry.liveBrokers(b -> b == BrokerType.ZERODHA);
                BrokerConnections.Route route = registry.route(k, v7);
                assertThat(route.venue()).as("Kotak is not configured LIVE here: no route").isNull();
                assertThat(route.description()).isEqualTo("LIVE refused: KOTAK_NEO is not configured LIVE on this worker");
            }
            assertThat(kotak.requests() + upstox.requests() + groww.requests()).as("nothing reached a broker").isZero();
        }
    }

    @Test
    void aLiveAccountWithAPositionRefusedItsRouteIsKilledAndAlerted() throws Exception {
        // review 16 Nov 2026: only the egress refusal engaged the kill switch and alerted; the broker taken out of the
        // LIVE setting (and every other refusal) left a LIVE account holding a position with no exits and no word
        BrokerAccount kite = liveAccount(BrokerType.ZERODHA, "AB7777");
        long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, source, idempotency_key, status) values "
                + "(?, 'MANUAL', ?, 'DONE') returning id", Long.class, kite.tenantId(), "stranded-" + kite.id());
        long intent = jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, state, "
                + "idempotency_key) values (?, ?, ?, 'ZERODHA', 'LIVE', 'ma-cross', 1, 'NIFTY', 'ENTER', 'PE', "
                + "'EXECUTED', ?) returning id", Long.class, kite.tenantId(), batch, kite.id(), "stranded-" + kite.id());
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                + "option_side, instrument_token, instrument_key, symbol, quantity_open, average_entry, opened_at, status) "
                + "values (?, ?, ?, 'ma-cross', 'NIFTY', 'PE', 47168, ?, ?, 65, 100, now(), 'OPEN')", kite.tenantId(),
                kite.id(), intent, KEY, SYMBOL);
        List<BrokerAdapter> adapters = List.of(new ZerodhaDryRunAdapter(dataSource, 1e9));
        try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, adapters)) {
            registry.liveBrokers(LiveBrokers.of("", adapters)::live);
            BrokerConnections.Route route = registry.route(kite, FollowerRisk.load(Path.of(V7)));
            assertThat(route.venue()).isNull();
        }
        assertThat(new KillSwitches(dataSource).engaged(kite, "ma-cross", KEY)).startsWith("ACCOUNT: LIVE ROUTE: ")
                .contains("ZERODHA is not configured LIVE on this worker");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'LIVE_ROUTE_REFUSED'", Integer.class, kite.id())).isEqualTo(1);
    }

    @Test
    void aRefusedRouteIsOpenedAgainAfterAPauseWithoutARestart() throws Exception {
        // review 16 Nov 2026 (D5): a refused route was kept until a restart, so an account whose cause went away (its
        // egress address came up a few seconds after the worker started, the LIVE setting put right) had no exits all day
        BrokerAccount kite = liveAccount(BrokerType.ZERODHA, "AB6666");
        List<BrokerAdapter> adapters = List.of(new ZerodhaDryRunAdapter(dataSource, 1e9));
        FollowerRisk v7 = FollowerRisk.load(Path.of(V7));
        java.util.concurrent.atomic.AtomicBoolean configured = new java.util.concurrent.atomic.AtomicBoolean(false);
        try (BrokerConnections registry = new BrokerConnections(dataSource, 1e9, adapters)) {
            registry.liveBrokers(b -> configured.get() && b == BrokerType.ZERODHA);
            assertThat(registry.route(kite, v7).venue()).isNull();
            configured.set(true);
            registry.retryRefusedAfter(java.time.Duration.ofHours(1));
            assertThat(registry.route(kite, v7).venue()).as("within the pause: the refusal stands").isNull();
            registry.retryRefusedAfter(java.time.Duration.ZERO);
            assertThat(registry.route(kite, v7).venue()).as("after it: opened again").isNotNull();
            assertThat(registry.route(kite, v7)).as("an open route is kept").isSameAs(registry.route(kite, v7));
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
