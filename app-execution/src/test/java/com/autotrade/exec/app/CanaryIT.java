package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.execution.BrokerAdapter;
import com.autotrade.execution.Canary;
import com.autotrade.execution.KillSwitches;
import com.autotrade.execution.LiveGuard;
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteGateway;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteInstruments;
import com.autotrade.zerodha.KiteSimulator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canary endpoint (16 Nov 2026, D1): the platform admin only, the phrase typed exactly, a trading day from 09:20 to
 * 15:00 IST, a real-broker account with today's session and live limits, no kill switch, no position or working order of
 * ours in the contract, and the adapter's LIVE connection (here a Kite simulator standing for api.kite.trade, behind its
 * LIVE guard). A run writes a CANARY_STEP row per step, a CANARY_RESULT row and a JSON report.
 * <p>
 * Review 16 Nov 2026: the admin also needs the authority to switch the account LIVE (SUPPORT without the member's grant
 * and an ADMIN of another tenant are refused); the request starts the run (202, a run id) and a GET reads it; no
 * position or working order of ours in any contract; something left at the broker engages the account's kill switch
 * and is alerted.
 */
@Testcontainers(disabledWithoutDocker = true)
class CanaryIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);            // a Wednesday, not a holiday
    private static final String KEY = "NSE_FO|47168";
    private static final String V6 = "../config/risk/follower-risk.v6.yaml";
    private static final String V9 = "../config/risk/follower-risk.v9.yaml";
    private static final String EXCHANGE = "../config/exchange/nse-bse-sessions.v3.yaml";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static long tenant;
    private static long owner;
    /** A member's workspace: kc-admin is SUPPORT there; other-admin (ADMIN of its own tenant) a TRADER. */
    private static long member;
    private static long memberOwner;
    private static long kcAdmin;
    private static int seq;

    private KiteSimulator kite;
    private final AtomicInteger opened = new AtomicInteger();

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
        AccountStore accounts = new AccountStore(dataSource);
        tenant = accounts.ensureTenant("owner");
        owner = accounts.ensureUser("local-owner", "me");
        accounts.ensureMembership(tenant, owner, "OWNER");
        kcAdmin = accounts.ensureUser("kc-admin", "admin");
        accounts.ensureMembership(tenant, kcAdmin, "ADMIN");
        member = accounts.ensureTenant("member");
        memberOwner = accounts.ensureUser("member-owner", "member");
        accounts.ensureMembership(member, memberOwner, "OWNER");
        accounts.ensureMembership(member, kcAdmin, "SUPPORT");
        long otherAdmin = accounts.ensureUser("other-admin", "other admin");
        accounts.ensureMembership(accounts.ensureTenant("other"), otherAdmin, "ADMIN");
        accounts.ensureMembership(member, otherAdmin, "TRADER");
        long snapshot = jdbc.queryForObject("insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, "
                + "instruments) values (?, 'UPSTOX:NSE-live.json.gz', 'canary', 1) returning id", Long.class, DAY);
        jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values "
                + "(?, ?, '47168', 'NSE_FO', 'NSE', 'PE', 'NIFTY', 'NIFTY 23100 PE', ?, 23100, 65, 0.05, 1755, true)",
                snapshot, KEY, DAY.plusDays(2));
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void realKite() throws Exception {
        kite = new KiteSimulator(0, "sim-api-key", "sim-secret", 1_500);
        kite.list(new KiteSimulator.Listing(47168, "NFO", "NIFTY26N2023100PE", "NIFTY", DAY.plusDays(2).toString(), 23100,
                "PE", 65, 0.05));
        kite.clock(() -> clock(10, 0).instant());
        kite.quote(KEY, 99.5, 6500, 100.0, 6500, 99.8);
    }

    @AfterEach
    void closeKite() {
        kite.close();
    }

    private static Clock clock(int hour, int minute) {
        return clock(DAY, hour, minute);
    }

    private static Clock clock(LocalDate day, int hour, int minute) {
        return Clock.fixed(LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(IST).toInstant(), IST);
    }

    /** The account's LIVE connection: the Kite adapter to "the real Kite" behind its LIVE guard. */
    private BrokerAdapter.Live live(BrokerAccount account, com.autotrade.execution.FollowerRisk.LiveLimits limits) {
        opened.incrementAndGet();
        try {
            KiteConfig config = KiteConfig.simulator(kite.baseUri());
            KiteHttp http = new KiteHttp(config);
            KiteInstruments instruments = KiteInstruments.load(http);
            http.accessToken("day-token");
            return new BrokerAdapter.Live(new LiveGuard(new KiteGateway(config, http, instruments), limits,
                    k -> instruments.byInstrumentKey(k).map(KiteInstruments.Contract::lotSize).orElse(0)),
                    "LIVE: Kite simulator");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private CanaryController api(Clock clock, Path dir, CanaryController.LiveOpener opener) {
        return new CanaryController(new Access(dataSource), dataSource, new TradingDays(EXCHANGE), opener, dir, clock,
                new Canary.Settings(Duration.ofMillis(20), 15, Duration.ofMinutes(2)));
    }

    private CanaryController api(Clock clock, Path dir) {
        return api(clock, dir, this::live);
    }

    /** A DRY_RUN follower (PAPER for a paper account) on {@code riskFile}; {@code session}: today's session is ACTIVE. */
    private static BrokerAccount account(BrokerType broker, String riskFile, boolean session) {
        return account(tenant, owner, broker, riskFile, session);
    }

    private static BrokerAccount account(long tenantId, long userId, BrokerType broker, String riskFile, boolean session) {
        String name = "CANARY-" + (++seq);
        long id = new AccountStore(dataSource).ensureFollowerAccount(tenantId, userId, name, broker,
                broker == BrokerType.PAPER ? TradeMode.PAPER : TradeMode.DRY_RUN, 200_000, EquityMode.FIXED, DAY, riskFile, 0);
        if (session) {
            jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status) "
                    + "values (?, 'session-file:it', now(), now() + interval '8 hours', 'ACTIVE')", id);
        }
        return new AccountStore(dataSource).byId(id).orElseThrow();
    }

    private static String phrase(BrokerAccount a) {
        return "CANARY " + a.displayName() + " " + KEY;
    }

    /** Starts a run (202 and its id), then reads it until it is done. */
    private static Map<String, Object> run(CanaryController api, String subject, long account,
                                           CanaryController.Request request) throws InterruptedException {
        ResponseEntity<Map<String, Object>> started = api.canary(subject, account, request);
        assertThat(started.getStatusCode().value()).isEqualTo(202);
        String runId = (String) started.getBody().get("runId");
        assertThat(started.getHeaders().getLocation()).hasToString("/api/execution/accounts/" + account + "/canary/"
                + runId);
        return await(api, subject, account, runId);
    }

    private static Map<String, Object> await(CanaryController api, String subject, long account, String runId)
            throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            Map<String, Object> view = api.run(subject, account, runId);
            if ("DONE".equals(view.get("status"))) {
                return view;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("canary run " + runId + " did not finish");
    }

    private static String refusal(Runnable call) {
        try {
            call.run();
            return "allowed";
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value() + " " + e.getReason();
        }
    }

    @Test
    void theAdminsCanaryRunsEveryStepAuditsEachAndWritesTheReport(@TempDir Path dir) throws Exception {
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        Map<String, Object> out = run(api(clock(10, 0), dir), "kc-admin", a.id(),
                new CanaryController.Request(KEY, phrase(a)));

        assertThat(out.get("result")).as(String.valueOf(out.get("message"))).isEqualTo("PASS");
        assertThat(out.get("tag")).isEqualTo("F0C261118100000000");
        assertThat(out.get("buyPrice")).isEqualTo(49.75);
        assertThat(kite.requests("place")).isEqualTo(1);
        assertThat(kite.requests("cancel")).isEqualTo(1);
        List<Map<String, Object>> all = jdbc.queryForList("select action, actor, detail::text detail from exec.audit_log "
                + "where broker_account_id = ? order by id", a.id());
        // review 16 Nov 2026 (D3): the account's entries are held for the run (its switch), and released after it
        assertThat(all.getFirst().get("action")).isEqualTo("KILL_ACCOUNT");
        assertThat(all.getLast().get("action")).isEqualTo("KILL_RELEASED");
        List<Map<String, Object>> rows = all.stream().filter(r -> ((String) r.get("action")).startsWith("CANARY_")).toList();
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("CANARY_STEP", "CANARY_STEP", "CANARY_STEP",
                "CANARY_STEP", "CANARY_STEP", "CANARY_STEP", "CANARY_STEP", "CANARY_STEP", "CANARY_STEP", "CANARY_STEP",
                "CANARY_RESULT");
        assertThat(rows).allMatch(r -> "kc-admin".equals(r.get("actor")));
        JsonNode cancelled = JSON.readTree((String) rows.get(8).get("detail"));
        assertThat(cancelled.path("step").asString()).isEqualTo("CONFIRM_CANCELLED");
        assertThat(cancelled.path("rawStatus").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("millis").isNumber()).isTrue();
        JsonNode result = JSON.readTree((String) rows.getLast().get("detail"));
        assertThat(result.path("result").asString()).isEqualTo("PASS");
        Path report = Path.of(result.path("report").asString());
        assertThat(report).isEqualTo(dir.resolve(Long.toString(a.id())).resolve("2026-11-18")
                .resolve("F0C261118100000000-pass.json"));
        JsonNode file = JSON.readTree(Files.readString(report));
        assertThat(file.path("report").path("steps")).hasSize(10);
        assertThat(file.path("report").path("steps").get(4).path("rawStatus").asString()).isEqualTo("OPEN");
        assertThat(out.get("reportFile")).isEqualTo(report.toString());
        assertThat(file.path("runId").asString()).isEqualTo(out.get("runId"));
        assertThat(rows).allMatch(r -> JSON.readTree((String) r.get("detail")).path("runId").asString()
                .equals(out.get("runId")));
        assertThat(jdbc.queryForObject("select count(*) from exec.kill_switch where broker_account_id = ? and released_at "
                + "is null", Integer.class, a.id())).as("a clean pass leaves nothing engaged").isZero();
    }

    @Test
    void aKillEngagedWhileTheCanaryHoldsEntriesOutlivesTheHold(@TempDir Path dir) throws Exception {
        // second review 16 Nov 2026: the hold was an ACCOUNT switch, so an automatic kill engaged during the run (an order
        // of unknown outcome, a Flatten) found it engaged and added nothing; the hold's release then lifted it in silence
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        CanaryController api = api(clock(10, 0), dir, (acct, limits) -> {
            BrokerAdapter.Live real = live(acct, limits);
            BrokerConnection guard = real.connection();
            BrokerConnection hooked = (BrokerConnection) java.lang.reflect.Proxy.newProxyInstance(
                    BrokerConnection.class.getClassLoader(), new Class<?>[] {BrokerConnection.class}, (p, m, args) -> {
                        if (m.getName().equals("place")) {
                            new KillSwitches(dataSource).engageAccount(acct, "ORDER_UNKNOWN: F12E1 (it)", "worker-it");
                        }
                        try {
                            return m.invoke(guard, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            return new BrokerAdapter.Live(hooked, real.description());
        });
        Map<String, Object> out = run(api, "kc-admin", a.id(), new CanaryController.Request(KEY, phrase(a)));

        assertThat(out.get("result")).as(String.valueOf(out.get("message"))).isEqualTo("PASS");
        assertThat(new KillSwitches(dataSource).engaged(a, "ma-cross", KEY)).as("the kill is still engaged")
                .startsWith("ACCOUNT: ORDER_UNKNOWN");
        assertThat(jdbc.queryForList("select reason from exec.kill_switch where broker_account_id = ? and released_at is "
                + "null", String.class, a.id())).singleElement().asString().startsWith("ORDER_UNKNOWN");
    }

    @Test
    void noStrategyEntryCanStartOnTheAccountWhileTheCanaryRuns(@TempDir Path dir) throws Exception {
        // review 16 Nov 2026 (D3): a strategy's buy of the contract during the run was taken for the canary's fill and sold
        // back; now the account's switch holds new entries for the run (exits go on), released when it ends
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        java.util.concurrent.atomic.AtomicReference<String> atPlace = new java.util.concurrent.atomic.AtomicReference<>();
        CanaryController api = api(clock(10, 0), dir, (acct, limits) -> {
            BrokerAdapter.Live real = live(acct, limits);
            BrokerConnection guard = real.connection();
            BrokerConnection hooked = (BrokerConnection) java.lang.reflect.Proxy.newProxyInstance(
                    BrokerConnection.class.getClassLoader(), new Class<?>[] {BrokerConnection.class}, (p, m, args) -> {
                        if (m.getName().equals("place")) {
                            atPlace.set(new KillSwitches(dataSource).engaged(acct, "ma-cross", KEY));
                        }
                        try {
                            return m.invoke(guard, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            return new BrokerAdapter.Live(hooked, real.description());
        });
        Map<String, Object> out = run(api, "kc-admin", a.id(), new CanaryController.Request(KEY, phrase(a)));

        assertThat(out.get("result")).as(String.valueOf(out.get("message"))).isEqualTo("PASS");
        assertThat(atPlace.get()).as("an entry would be refused during the run")
                .startsWith("ACCOUNT: " + CanaryController.FENCE);
        assertThat(new KillSwitches(dataSource).engaged(a, "ma-cross", KEY)).as("released after it").isNull();
    }

    @Test
    void onlyTheAdminWithTheTypedPhraseInMarketHoursOnAnIdleContractRunsIt(@TempDir Path dir) throws Exception {
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        CanaryController.Request ok = new CanaryController.Request(KEY, phrase(a));
        CanaryController api = api(clock(10, 0), dir);

        assertThat(refusal(() -> api.canary("local-owner", a.id(), ok))).startsWith("403 only the platform admin");
        assertThat(refusal(() -> api.canary("kc-admin", a.id(), new CanaryController.Request(KEY, "yes"))))
                .startsWith("400 type \"" + phrase(a) + "\"");
        assertThat(refusal(() -> api.canary("kc-admin", a.id(), new CanaryController.Request("NSE_FO|1", "CANARY "
                + a.displayName() + " NSE_FO|1")))).startsWith("400 NSE_FO|1 is not in the contract master");
        // only from 09:20 to 15:00 IST on a trading day
        assertThat(refusal(() -> api(clock(9, 19), dir).canary("kc-admin", a.id(), ok))).startsWith("409 a canary runs");
        assertThat(refusal(() -> api(clock(15, 0), dir).canary("kc-admin", a.id(), ok))).startsWith("409 a canary runs");
        assertThat(refusal(() -> api(clock(LocalDate.of(2026, 11, 21), 10, 0), dir).canary("kc-admin", a.id(), ok)))
                .as("a Saturday").startsWith("409 a canary runs");
        assertThat(refusal(() -> api(clock(LocalDate.of(2026, 11, 24), 10, 0), dir).canary("kc-admin", a.id(), ok)))
                .as("a holiday").startsWith("409 a canary runs");
        // a risk file without live limits, no session, a paper account
        BrokerAccount v6 = account(BrokerType.ZERODHA, V6, true);
        assertThat(refusal(() -> api.canary("kc-admin", v6.id(), new CanaryController.Request(KEY, phrase(v6)))))
                .contains("has no live limits");
        BrokerAccount noSession = account(BrokerType.ZERODHA, V9, false);
        assertThat(refusal(() -> api.canary("kc-admin", noSession.id(), new CanaryController.Request(KEY,
                phrase(noSession))))).contains("no ZERODHA session today");
        BrokerAccount paper = account(BrokerType.PAPER, V9, true);
        assertThat(refusal(() -> api.canary("kc-admin", paper.id(), new CanaryController.Request(KEY, phrase(paper)))))
                .contains("is a paper account");
        // a broker whose adapter has no LIVE connection yet: found by the run (opening it is part of the run)
        Map<String, Object> none = run(api(clock(10, 0), dir, (acct, limits) -> null), "kc-admin", a.id(), ok);
        assertThat(none.get("result")).isEqualTo("REFUSED");
        assertThat((String) none.get("message")).contains("no LIVE connection for ZERODHA yet");
        // a kill switch
        long ks = jdbc.queryForObject("insert into exec.kill_switch (scope, reason, engaged_by) values ('GLOBAL_LIVE', "
                + "'drill', 'it') returning id", Long.class);
        assertThat(refusal(() -> api.canary("kc-admin", a.id(), ok))).contains("GLOBAL_LIVE is engaged");
        jdbc.update("update exec.kill_switch set released_at = now() where id = ?", ks);
        assertThat(kite.requests("place")).as("nothing sent so far").isZero();
        assertThat(opened.get()).as("no LIVE connection opened by a refusal").isEqualTo(0);
    }

    @Test
    void anOpenPositionOrAWorkingOrderOfOursInAnyContractRefusesTheCanary(@TempDir Path dir) {
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, source, idempotency_key, status) values "
                + "(?, 'MANUAL', ?, 'DONE') returning id", Long.class, tenant, "canary-" + a.id());
        long intent = jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, state, "
                + "idempotency_key) values (?, ?, ?, 'ZERODHA', 'DRY_RUN', 'ma-cross', 1, 'NIFTY', 'ENTER', 'PE', "
                + "'EXECUTED', ?) returning id", Long.class, tenant, batch, a.id(), "canary-" + a.id());
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                + "option_side, instrument_token, instrument_key, symbol, quantity_open, average_entry, opened_at, status) "
                + "values (?, ?, ?, 'ma-cross', 'NIFTY', 'CE', 3000050001, 'NSE_FO|50001', 'NIFTY 23200 CE', 65, 100, now(), "
                + "'OPEN')", tenant, a.id(), intent);
        CanaryController.Request ok = new CanaryController.Request(KEY, phrase(a));
        assertThat(refusal(() -> api(clock(10, 0), dir).canary("kc-admin", a.id(), ok)))
                .as("a position in another contract").contains("holds 1 position(s) of ours");
        jdbc.update("update exec.position set status = 'CLOSED' where broker_account_id = ?", a.id());
        // an entry still working at the broker in another contract (no position row yet)
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                + "client_tag, attempt_no, order_side, order_type, quantity_requested, quantity_filled, status) values "
                + "(?, ?, ?, 'ZERODHA', 'ENTRY', ?, 1, 'BUY', 'LIMIT', 65, 0, 'ACKNOWLEDGED')", tenant, intent, a.id(),
                "F" + intent + "E1");
        assertThat(refusal(() -> api(clock(10, 0), dir).canary("kc-admin", a.id(), ok)))
                .contains("has 1 order(s) of ours working");
        assertThat(kite.requests("place")).isZero();
        assertThat(opened.get()).isZero();
    }

    @Test
    void theBrokersPageFindsTheContractAndThePhraseWithTheRunsAuthority(@TempDir Path dir) {
        BrokerAccount a = account(BrokerType.KOTAK_NEO, V9, true);
        CanaryController api = api(clock(10, 0), dir);
        Map<String, Object> found = api.contract("kc-admin", a.id(), "nifty", 23100, "pe");
        assertThat(found).containsEntry("instrumentKey", KEY).containsEntry("symbol", "NIFTY 23100 PE")
                .containsEntry("lotSize", 65).containsEntry("expiry", DAY.plusDays(2).toString())
                .containsEntry("phrase", phrase(a));
        assertThat(refusal(() -> api.contract("kc-admin", a.id(), "NIFTY", 23150, "PE")))
                .isEqualTo("404 no NIFTY 23150 PE expiring from " + DAY + " in the contract master");
        assertThat(refusal(() -> api.contract("kc-admin", a.id(), "NIFTY", 23100, "XX"))).startsWith("400 side");
        assertThat(refusal(() -> api.contract("kc-admin", a.id(), "TCS", 23100, "PE"))).startsWith("400 index");
        assertThat(refusal(() -> api(clock(DAY.plusDays(3), 10, 0), dir).contract("kc-admin", a.id(), "NIFTY", 23100,
                "PE"))).as("expired").startsWith("404");
        BrokerAccount m = account(member, memberOwner, BrokerType.KOTAK_NEO, V9, true);
        assertThat(refusal(() -> api.contract("kc-admin", m.id(), "NIFTY", 23100, "PE")))
                .as("SUPPORT without the member's grant, as for the run").startsWith("403 a canary sends a real order");
        assertThat(refusal(() -> api.contract("member-owner", m.id(), "NIFTY", 23100, "PE")))
                .startsWith("403 only the platform admin");
    }

    @Test
    void aMembersAccountNeedsTheAuthorityToSwitchItLive(@TempDir Path dir) throws Exception {
        BrokerAccount m = account(member, memberOwner, BrokerType.ZERODHA, V9, true);
        CanaryController.Request ok = new CanaryController.Request(KEY, phrase(m));
        CanaryController api = api(clock(10, 0), dir);

        assertThat(refusal(() -> api.canary("kc-admin", m.id(), ok))).as("SUPPORT without the member's grant")
                .startsWith("403 a canary sends a real order into " + m.displayName());
        assertThat(refusal(() -> api.canary("other-admin", m.id(), ok))).as("an ADMIN of another tenant, TRADER here")
                .startsWith("403 a canary sends a real order");
        assertThat(refusal(() -> api.canary("member-owner", m.id(), ok))).as("the member: not the platform admin")
                .startsWith("403 only the platform admin");
        assertThat(kite.requests("place")).isZero();

        // with the member's grant, SUPPORT may (as for the member's LIVE switch)
        jdbc.update("insert into acct.support_grant (tenant_id, granted_to, granted_by, expires_at) values (?, ?, ?, "
                + "now() + interval '1 hour')", member, kcAdmin, memberOwner);
        Map<String, Object> out = run(api, "kc-admin", m.id(), ok);
        assertThat(out.get("result")).as(String.valueOf(out.get("message"))).isEqualTo("PASS");
        // and the run is read only with the same authority
        assertThat(refusal(() -> api.run("other-admin", m.id(), (String) out.get("runId"))))
                .startsWith("403 a canary sends a real order");
    }

    @Test
    void theRequestStartsTheRunAndOneRunsAtATimeUntilItEnds(@TempDir Path dir) throws Exception {
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        CanaryController.Request ok = new CanaryController.Request(KEY, phrase(a));
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CanaryController api = api(clock(10, 0), dir, (acct, limits) -> {
            opening.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);       // the instrument download, a slow broker
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return live(acct, limits);
        });

        ResponseEntity<Map<String, Object>> started = api.canary("kc-admin", a.id(), ok);
        assertThat(started.getStatusCode().value()).isEqualTo(202);
        assertThat(started.getBody().get("status")).isEqualTo("RUNNING");
        String runId = (String) started.getBody().get("runId");
        assertThat(opening.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(api.run("kc-admin", a.id(), runId).get("status")).isEqualTo("RUNNING");
        assertThat(refusal(() -> api.canary("kc-admin", a.id(), ok))).as("the account is locked while it runs")
                .startsWith("409 a canary is already running on " + a.displayName());
        release.countDown();
        Map<String, Object> done = await(api, "kc-admin", a.id(), runId);
        assertThat(done.get("result")).as(String.valueOf(done.get("message"))).isEqualTo("PASS");
        assertThat((List<?>) done.get("steps")).hasSize(10);

        // after a restart (a new controller) the run is read back from its audit rows
        Map<String, Object> again = api(clock(10, 0), dir).run("kc-admin", a.id(), runId);
        assertThat(again.get("status")).isEqualTo("DONE");
        assertThat(again.get("result")).isEqualTo("PASS");
        assertThat((List<?>) again.get("steps")).hasSize(10);
        assertThat(again.get("reportFile")).isEqualTo(done.get("reportFile"));
        assertThat(refusal(() -> api.run("kc-admin", a.id(), "no-such-run"))).startsWith("404");

        // the lock is gone: another run starts (a minute later: the fixed test clock would repeat the canary's tag)
        CanaryController later = api(clock(10, 1), dir, this::live);
        assertThat(run(later, "kc-admin", a.id(), ok).get("result")).isEqualTo("PASS");
    }

    @Test
    void neverStartedFromASchedulerThread(@TempDir Path dir) throws Exception {
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(refusal(() -> api(clock(10, 0), dir).canary("kc-admin", a.id(),
                new CanaryController.Request(KEY, phrase(a))))), "scheduling-1");
        t.start();
        t.join();
        assertThat(out.get()).startsWith("409 a canary is never run by a scheduler");
        assertThat(opened.get()).isZero();
        assertThat(kite.requests("place")).isZero();
    }

    @Test
    void anOrderLeftWorkingEngagesTheAccountsKillSwitchAndIsAlerted(@TempDir Path dir) throws Exception {
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        // a real-broker account still trading PAPER (a member's account before its LIVE eligibility): its canary's
        // leftover is alerted all the same
        jdbc.update("update acct.broker_account set trade_mode = 'PAPER' where id = ?", a.id());
        ExecutionAlerts alerts = new ExecutionAlerts(dataSource, null);
        alerts.collect();                                     // the first look starts from now
        // the session ends just after the modify: the cancel cannot be sent, the order may still be working
        CanaryController api = api(clock(10, 0), dir, (acct, limits) -> {
            BrokerAdapter.Live real = live(acct, limits);
            BrokerConnection guard = real.connection();
            BrokerConnection hooked = (BrokerConnection) java.lang.reflect.Proxy.newProxyInstance(
                    BrokerConnection.class.getClassLoader(), new Class<?>[] {BrokerConnection.class}, (p, m, args) -> {
                        Object r;
                        try {
                            r = m.invoke(guard, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                        if (m.getName().equals("modify")) {
                            kite.expireToken();
                        }
                        return r;
                    });
            return new BrokerAdapter.Live(hooked, real.description());
        });
        Map<String, Object> out = run(api, "kc-admin", a.id(), new CanaryController.Request(KEY, phrase(a)));

        assertThat(out.get("result")).isEqualTo("FAIL");
        assertThat(out.get("leftWorking")).isEqualTo(true);
        assertThat(out.get("leftover")).isEqualTo(true);
        List<String> reasons = jdbc.queryForList("select reason from exec.kill_switch where scope = 'ACCOUNT' and "
                + "broker_account_id = ? and released_at is null", String.class, a.id());
        assertThat(reasons).singleElement().satisfies(r -> assertThat(r).startsWith("CANARY:").contains("may still be "
                + "working"));
        List<String> actions = jdbc.queryForList("select action from exec.audit_log where broker_account_id = ? "
                + "order by id", String.class, a.id());
        assertThat(actions).contains("KILL_ACCOUNT", "CANARY_LEFTOVER");
        assertThat(actions.indexOf("CANARY_LEFTOVER")).as("before the result").isLessThan(actions.indexOf("CANARY_RESULT"));
        assertThat(alerts.collect()).extracting(ExecutionAlerts.Alert::text)
                .anySatisfy(t -> assertThat(t).contains(a.displayName()).contains("CANARY_LEFTOVER"));
        // a further canary on the account is refused by its own switch
        assertThat(refusal(() -> api(clock(10, 0), dir).canary("kc-admin", a.id(), new CanaryController.Request(KEY,
                phrase(a))))).contains("a kill switch is engaged: ACCOUNT: CANARY:");
    }
}
