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
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import com.autotrade.execution.BrokerAdapter;
import com.autotrade.execution.Canary;
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
        accounts.ensureMembership(tenant, accounts.ensureUser("kc-admin", "admin"), "ADMIN");
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
        String name = "CANARY-" + (++seq);
        long id = new AccountStore(dataSource).ensureFollowerAccount(tenant, owner, name, broker,
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
        Map<String, Object> out = api(clock(10, 0), dir).canary("kc-admin", a.id(),
                new CanaryController.Request(KEY, phrase(a)));

        assertThat(out.get("result")).as(String.valueOf(out.get("message"))).isEqualTo("PASS");
        assertThat(out.get("tag")).isEqualTo("F0C261118100000000");
        assertThat(out.get("buyPrice")).isEqualTo(49.75);
        assertThat(kite.requests("place")).isEqualTo(1);
        assertThat(kite.requests("cancel")).isEqualTo(1);
        List<Map<String, Object>> rows = jdbc.queryForList("select action, actor, detail::text detail from exec.audit_log "
                + "where broker_account_id = ? order by id", a.id());
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
    }

    @Test
    void onlyTheAdminWithTheTypedPhraseInMarketHoursOnAnIdleContractRunsIt(@TempDir Path dir) {
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
        // a broker whose adapter has no LIVE connection yet
        assertThat(refusal(() -> api(clock(10, 0), dir, (acct, limits) -> null).canary("kc-admin", a.id(), ok)))
                .contains("no LIVE connection for ZERODHA yet");
        // a kill switch
        long ks = jdbc.queryForObject("insert into exec.kill_switch (scope, reason, engaged_by) values ('GLOBAL_LIVE', "
                + "'drill', 'it') returning id", Long.class);
        assertThat(refusal(() -> api.canary("kc-admin", a.id(), ok))).contains("GLOBAL_LIVE is engaged");
        jdbc.update("update exec.kill_switch set released_at = now() where id = ?", ks);
        assertThat(kite.requests("place")).as("nothing sent so far").isZero();
        assertThat(opened.get()).as("no LIVE connection opened by a refusal").isEqualTo(0);
    }

    @Test
    void anOpenPositionInTheContractRefusesTheCanary(@TempDir Path dir) {
        BrokerAccount a = account(BrokerType.ZERODHA, V9, true);
        long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, source, idempotency_key, status) values "
                + "(?, 'MANUAL', ?, 'DONE') returning id", Long.class, tenant, "canary-" + a.id());
        long intent = jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, state, "
                + "idempotency_key) values (?, ?, ?, 'ZERODHA', 'DRY_RUN', 'ma-cross', 1, 'NIFTY', 'ENTER', 'PE', "
                + "'EXECUTED', ?) returning id", Long.class, tenant, batch, a.id(), "canary-" + a.id());
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                + "option_side, instrument_token, instrument_key, symbol, quantity_open, average_entry, opened_at, status) "
                + "values (?, ?, ?, 'ma-cross', 'NIFTY', 'PE', 3000047168, ?, 'NIFTY 23100 PE', 65, 100, now(), 'OPEN')",
                tenant, a.id(), intent, KEY);
        assertThat(refusal(() -> api(clock(10, 0), dir).canary("kc-admin", a.id(), new CanaryController.Request(KEY,
                phrase(a))))).contains("holds a position in NIFTY 23100 PE");
        assertThat(kite.requests("place")).isZero();
    }
}
