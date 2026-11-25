package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.autotrade.execution.ExitOutcome;
import com.autotrade.execution.FanOut;
import com.autotrade.execution.SignalDecision;
import com.autotrade.execution.SignalDecisionStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The admin exit desk (user 20 Nov 2026; plan 2026-11-20-admin-exit-positions-plan.md, phases A, B, D): only the platform
 * admin reads and uses it, in every tenant (a member's workspace they support, and one they are not a member of); a batch
 * needs the count in its phrase, a reason and a key, runs LIVE first (largest premium at risk first) then PAPER, paces LIVE
 * starts per broker app, answers each position (already closed, mirror, refused, partly), is idempotent by its key, engages
 * the kill switches after by default when a LIVE position is in it, writes by_admin, the reason and the member's consent
 * into the audit and tells the members. A non-owner's single Exit needs a reason; the owner's does not.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminDeskIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static long platform, aliceTenant, bobTenant;
    private static long kite1, kite2, alicePaper, bobPaper, demo, adminPaper, profile;
    private static long aliceGroup, bobGroup, adminGroup, session;
    private static int seq;

    /** What the members were told (Telegram + push go through AdminExits.exited). */
    private static final List<String> told = Collections.synchronizedList(new ArrayList<>());

    /** One exit the runner was asked for. */
    private record Ran(long account, long position, boolean byAdmin, Map<String, Object> audit, Long quantity) {

        Ran(long account, long position, boolean byAdmin, Map<String, Object> audit) {
            this(account, position, byAdmin, audit, null);
        }
    }

    private static final List<Ran> ran = Collections.synchronizedList(new ArrayList<>());
    private static final Set<Long> refuse = ConcurrentHashMap.newKeySet();
    private static final Set<Long> partly = ConcurrentHashMap.newKeySet();

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
        platform = accounts.ensureTenant("t-platform");
        aliceTenant = accounts.ensureTenant("t-alice");
        bobTenant = accounts.ensureTenant("t-bob");
        long admin = accounts.ensureUser("kc-admin", "admin"), alice = accounts.ensureUser("kc-alice", "alice");
        long bob = accounts.ensureUser("kc-bob", "bob"), viewer = accounts.ensureUser("kc-viewer", "viewer");
        accounts.ensureMembership(platform, admin, "ADMIN");
        accounts.ensureMembership(aliceTenant, alice, "OWNER");
        accounts.ensureMembership(aliceTenant, admin, "SUPPORT");           // the admin supports alice's workspace...
        accounts.ensureMembership(aliceTenant, viewer, "VIEWER");
        accounts.ensureMembership(bobTenant, bob, "OWNER");                 // ... and is not a member of bob's at all
        LocalDate today = LocalDate.now();
        kite1 = accounts.ensureFollowerAccount(aliceTenant, alice, "ALICE-KITE", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, today, "../config/risk/follower-risk.v3.yaml", 100);
        kite2 = accounts.ensureFollowerAccount(aliceTenant, alice, "ALICE-KITE-2", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, today, "../config/risk/follower-risk.v3.yaml", 100);
        alicePaper = accounts.ensureFollowerAccount(aliceTenant, alice, "ALICE-PAPER", BrokerType.PAPER, TradeMode.PAPER,
                500_000, EquityMode.FIXED, today, "../config/risk/follower-risk.v3.yaml", 100);
        bobPaper = accounts.ensureFollowerAccount(bobTenant, bob, "BOB-PAPER", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, today, "../config/risk/follower-risk.v3.yaml", 100);
        demo = accounts.ensureFollowerAccount(bobTenant, bob, "DEMO-1", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, today, "../config/risk/follower-risk.v3.yaml", 100);
        adminPaper = accounts.ensureFollowerAccount(platform, admin, "ADMIN-PAPER", BrokerType.PAPER, TradeMode.PAPER,
                500_000, EquityMode.FIXED, today, "../config/risk/follower-risk.v3.yaml", 100);
        // both of alice's Kite accounts on one broker app: their LIVE exits are paced together
        profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'TENANT_SHARED', 'kite-app', 'k', 's') returning id",
                Long.class, aliceTenant);
        jdbc.update("update acct.broker_account set credential_profile_id = ? where id in (?, ?)", profile, kite1, kite2);
        aliceGroup = group(accounts, aliceTenant, alice, "A", kite1, kite2, alicePaper);
        bobGroup = group(accounts, bobTenant, bob, "B", bobPaper);
        adminGroup = group(accounts, platform, admin, "P", adminPaper);
        session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('SIG-A', 'PAPER_LIVE', current_date, 'it', 'live-set', "
                + "'sha256:it', '{}'::jsonb, 'it', 'RUNNING') returning id", Long.class);
        jdbc.update("update acct.broker_account set mirror_of = 'PAPER-1' where id = ?", demo);
    }

    private static long group(AccountStore accounts, long tenant, long owner, String name, long... followers) {
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-" + name, 500_000, LocalDate.now(),
                "config/risk/signal-source.v2.yaml");
        long g = accounts.ensureCopyGroup(tenant, "G-" + name, signal);
        for (long f : followers) {
            new FanOut(dataSource).subscribe(g, f, List.of("oi-burst-cross"), 1.0);
        }
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?", g);
        return g;
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void clean() {
        ran.clear();
        told.clear();
        refuse.clear();
        partly.clear();
        closedFirst.clear();
        throwAfterOrder.clear();
        throwBefore.clear();
        killedWhenRun.clear();
        jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now() where status <> 'CLOSED'");
        jdbc.update("update exec.kill_switch set released_at = now(), released_by = 'it' where released_at is null");
        jdbc.update("delete from acct.broker_session");                    // nobody logged in
        jdbc.update("delete from exec.account_lease");
        jdbc.update("update acct.broker_account set connection_status = 'PENDING_CONNECT'");
    }

    /** An open position of {@code account} (2 lots of 65) entered at {@code entry}, with an entry intent of {@code mode}. */
    private static long position(long tenant, long account, long group, String mode, double entry) {
        int n = ++seq;
        long token = 3_000_050_000L + n;
        new SignalDecisionStore(dataSource).insert(new SignalDecision(group, session, LocalDate.now(), Instant.now().minusSeconds(360),   // before the open (-5 min)
                "oi-burst-cross", 2, "NIFTY", SignalDecision.Action.ENTER, "CE", null, "CONFIRMED", "IT", token,
                "NSE_FO|" + token, "NIFTY " + (24000 + n) + " CE 26 NOV 26", 65, entry, 9_500, 500_000, 0, "D:" + n,
                Map.of()));
        new FanOut(dataSource).fanOut(group, LocalDate.now());
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ? order by id desc "
                + "limit 1", Long.class, account);
        jdbc.update("update exec.trade_intent set execution_mode = ? where id = ?", mode, intent);
        return jdbc.queryForObject("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, "
                + "strategy_id, underlying, option_side, instrument_token, instrument_key, symbol, quantity_open, average_entry, "
                + "opened_at, status) values (?, ?, ?, 'oi-burst-cross', 'NIFTY', 'CE', ?, ?, ?, 130, ?, now() - interval "
                + "'5 minutes', 'OPEN') returning id", Long.class, tenant, account, intent, token, "NSE_FO|" + token,
                "NIFTY " + (24000 + n) + " CE 26 NOV 26", entry);
    }

    /** Positions closed by their strategy just before the admin's exit got its turn on the lane. */
    private static final Set<Long> closedFirst = ConcurrentHashMap.newKeySet();
    /** Positions whose exit throws after writing an exit order (the outcome is not known). */
    private static final Set<Long> throwAfterOrder = ConcurrentHashMap.newKeySet();
    /** Positions whose exit throws before any order. */
    private static final Set<Long> throwBefore = ConcurrentHashMap.newKeySet();
    /** Per position: whether its account's kill switch was already engaged when its exit ran. */
    private static final Map<Long, Boolean> killedWhenRun = new ConcurrentHashMap<>();

    /**
     * The exits' stand-in for the account's lane: records what it was asked and whether the kill switch was already on,
     * reads the position as the lane would, and sells all, half or nothing in the database.
     */
    private static AdminDeskController.BatchRunner runner() {
        return (account, position, quantity, actor, day, byAdmin, audit) -> {
            ran.add(new Ran(account, position, byAdmin, audit, quantity));
            killedWhenRun.put(position, jdbc.queryForObject("select exists (select 1 from exec.kill_switch where scope = "
                    + "'ACCOUNT' and broker_account_id = ? and released_at is null)", Boolean.class, account));
            if (closedFirst.contains(position)) {
                jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now(), "
                        + "exit_reason = 'STOP' where id = ?", position);
                return new ExitOutcome(ExitOutcome.ALREADY_CLOSED, 0L, 0L, List.of(), "closed before the exit got its turn");
            }
            if (throwBefore.contains(position)) {
                throw new IllegalStateException("lane broken");
            }
            if (throwAfterOrder.contains(position)) {
                Map<String, Object> p = jdbc.queryForMap("select tenant_id, entry_trade_intent_id from exec.position "
                        + "where id = ?", position);
                jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, "
                        + "role, client_tag, attempt_no, order_side, order_type, quantity_requested, status) values (?, ?, ?, "
                        + "'ZERODHA', 'EMERGENCY_EXIT', ?, 1, 'SELL', 'LIMIT', 130, 'SUBMITTED')", p.get("tenant_id"),
                        p.get("entry_trade_intent_id"), account, "X" + position);
                throw new IllegalStateException("broker timeout");
            }
            long before = jdbc.queryForObject("select quantity_open from exec.position where id = ?", Long.class, position);
            if (refuse.contains(position)) {
                return ExitOutcome.classify(before, before, false, List.of("NIFTY: NOT exited, no bid today and no signal since the entry"),
                        List.of(), null);
            }
            if (quantity != null) {
                // a part of it, as the lane sells one (ExecutionLoop#exitPositionOutcome): exactly what was asked is SOLD
                jdbc.update("update exec.position set quantity_open = quantity_open - ? where id = ?", quantity, position);
                return new ExitOutcome(ExitOutcome.SOLD, before, before - quantity, List.of("NIFTY: " + quantity + " of "
                        + before + " sold at 101.00; " + (before - quantity) + " stay open"), quantity + " of " + before
                        + " sold as asked, " + (before - quantity) + " stay open");
            }
            if (partly.contains(position)) {
                jdbc.update("update exec.position set quantity_open = 65 where id = ?", position);
                return ExitOutcome.classify(before, 65L, false, List.of("NIFTY: 65 of 130 sold at 101.00; 65 stay open"),
                        List.of("FILLED"), null);
            }
            jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now(), "
                    + "exit_reason = 'ADMIN_EXIT' where id = ?", position);
            return ExitOutcome.classify(before, 0L, true, List.of("NIFTY: 130 sold at 101.00"), List.of("FILLED"), null);
        };
    }

    /** Starts a batch and waits for it to finish (it runs in the background), returning its last view. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> runBatch(AdminDeskController desk, AdminDeskController.Batch b) throws Exception {
        org.springframework.http.ResponseEntity<Map<String, Object>> started = desk.exitBatch("kc-admin", b);
        assertThat(started.getStatusCode().value()).isEqualTo(202);
        String id = (String) started.getBody().get("batchId");
        for (int i = 0; i < 200; i++) {
            Map<String, Object> view = desk.batch("kc-admin", id);
            if ("DONE".equals(view.get("status"))) {
                return view;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("batch " + id + " did not finish");
    }

    /** AdminExits that records the members' message instead of sending it. */
    private static AdminExits recordingExits() {
        return new AdminExits(dataSource, null) {
            @Override
            void exited(BrokerAccount account, String actor, String reason, List<String> lines) {
                told.add(account.displayName() + "|" + reason + "|" + String.join(";", lines));
            }
        };
    }

    /** Recorded pacing waits (the clock never moves: every wait is the pacer's own). */
    private static final List<Long> waits = Collections.synchronizedList(new ArrayList<>());

    private static AdminDeskController desk() {
        ExecutionService service = new ExecutionService(dataSource, new ExecutionProperties(null, "*", 0, null, 0, "it",
                null));
        return new AdminDeskController(new Access(dataSource), dataSource, service, recordingExits()).withRunner(runner())
                .withPacer(new OrderPacer(8, () -> 0L, waits::add));
    }

    private interface Call {
        Object run() throws Exception;
    }

    private static int code(Call call) {
        try {
            call.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdminDeskController.Batch batch(String key, String confirm, Boolean kill, long[]... positions) {
        return batch(key, confirm, kill, null, null, positions);
    }

    private static AdminDeskController.Batch batch(String key, String confirm, Boolean kill, Boolean killPaper,
                                                   Boolean globalLive, long[]... positions) {
        List<AdminDeskController.BatchPosition> list = new ArrayList<>();
        for (long[] p : positions) {
            list.add(new AdminDeskController.BatchPosition(p[0], p[1]));
        }
        return new AdminDeskController.Batch(list, "risk check", kill, key, confirm, killPaper, globalLive);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyThePlatformAdminReadsTheDeskAndSeesEveryTenant() {
        long a = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        long b = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        AdminDeskController desk = desk();
        Map<String, Object> all = desk.openPositions("kc-admin");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) all.get("positions");
        assertThat(rows).extracting(r -> r.get("positionId")).containsExactly(a, b);   // LIVE first
        assertThat(rows.getFirst()).containsEntry("group", "LIVE").containsEntry("account", "ALICE-KITE")
                .containsEntry("tenant", "t-alice").containsEntry("broker", "ZERODHA").containsEntry("loggedIn", false)
                .containsEntry("brokerApp", "kite-app").containsEntry("source", "SIG-A").containsEntry("lots", 2L)
                .containsEntry("quantity", 130L).containsEntry("quoteStale", true).containsEntry("premiumAtRisk", 15_600.0);
        assertThat(rows.get(1)).as("bob's: a tenant the admin is not a member of").containsEntry("group", "PAPER")
                .containsEntry("tenant", "t-bob").containsEntry("loggedIn", null);
        assertThat((List<Map<String, Object>>) all.get("mirrors")).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("account", "DEMO-1").containsEntry("mirror_of", "PAPER-1"));
        // nobody else: a tenant's OWNER, a VIEWER, another tenant's OWNER
        for (String who : List.of("kc-alice", "kc-viewer", "kc-bob")) {
            assertThat(code(() -> desk.openPositions(who))).as(who).isEqualTo(403);
            assertThat(code(() -> desk.exitBatch(who, batch("k-" + who, "EXIT 1 POSITIONS", null, new long[] {kite1, a}))))
                    .as(who).isEqualTo(403);
        }
        assertThat(code(() -> desk.openPositions(null))).isEqualTo(401);
        assertThat(ran).isEmpty();
    }

    /**
     * Plan 2026-11-23 decision 3 ("Exit everything"): by default only the accounts with a LIVE position are stopped; the
     * paper ones keep trading; GLOBAL_LIVE goes on first when asked, once (a second batch finds it on).
     */
    @Test
    @SuppressWarnings("unchecked")
    void exitEverythingStopsTheLiveAccountsAndGlobalLiveFirstAndLeavesPaperTrading() throws Exception {
        long live = position(aliceTenant, kite1, aliceGroup, "LIVE", 100);
        long paper = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        AdminDeskController desk = desk();
        Map<String, Object> out = runBatch(desk, batch("everything-1", "EXIT 2 POSITIONS", null, null, true,
                new long[] {kite1, live}, new long[] {bobPaper, paper}));
        assertThat(out).containsEntry("killAfter", true).containsEntry("globalLive", "ENGAGED");
        assertThat((List<String>) out.get("killed")).containsExactly("ALICE-KITE");
        assertThat(jdbc.queryForList("select coalesce(broker_account_id::text, scope) from exec.kill_switch where "
                + "released_at is null and (scope = 'GLOBAL_LIVE' or broker_account_id in (?, ?))", String.class, kite1,
                bobPaper)).containsExactlyInAnyOrder("GLOBAL_LIVE", String.valueOf(kite1));
        assertThat(killedWhenRun).containsEntry(live, true).containsEntry(paper, false);
        assertThat((List<Map<String, Object>>) out.get("results")).extracting(r -> r.get("outcome"))
                .containsExactly("SOLD", "SOLD");
        long again = position(aliceTenant, kite2, aliceGroup, "LIVE", 50);
        Map<String, Object> second = runBatch(desk, batch("everything-2", "EXIT 1 POSITIONS", null, null, true,
                new long[] {kite2, again}));
        assertThat(second).containsEntry("globalLive", "ALREADY");
        assertThat(jdbc.queryForObject("select count(*) from exec.kill_switch where scope = 'GLOBAL_LIVE' and released_at "
                + "is null", Integer.class)).isEqualTo(1);
    }

    @Test
    void aBatchNeedsTheCountInItsPhraseAReasonAndAKey() {
        long a = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        long b = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        AdminDeskController desk = desk();
        assertThat(code(() -> desk.exitBatch("kc-admin", batch("k1", "EXIT 1 POSITIONS", null, new long[] {kite1, a},
                new long[] {bobPaper, b})))).as("a stale screen: the count differs").isEqualTo(403);
        assertThat(code(() -> desk.exitBatch("kc-admin", batch("k1", "EXIT", null, new long[] {kite1, a})))).isEqualTo(403);
        assertThat(code(() -> desk.exitBatch("kc-admin", new AdminDeskController.Batch(List.of(
                new AdminDeskController.BatchPosition(kite1, a)), " ", null, "k1", "EXIT 1 POSITIONS", null, null)))).isEqualTo(400);
        assertThat(code(() -> desk.exitBatch("kc-admin", batch(null, "EXIT 1 POSITIONS", null, new long[] {kite1, a}))))
                .isEqualTo(400);
        assertThat(code(() -> desk.exitBatch("kc-admin", batch("k1", "EXIT 2 POSITIONS", null, new long[] {kite1, a},
                new long[] {kite1, a})))).as("listed twice").isEqualTo(400);
        assertThat(ran).as("nothing ran").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveGoesFirstLargestRiskFirstPacedPerBrokerAppThenPaperAndEachPositionIsAnswered() throws Exception {
        long small = position(aliceTenant, kite1, aliceGroup, "LIVE", 100);
        long big = position(aliceTenant, kite2, aliceGroup, "LIVE", 300);
        long paper = position(bobTenant, bobPaper, bobGroup, "PAPER", 500);   // the largest, but paper: last
        long gone = position(aliceTenant, alicePaper, aliceGroup, "PAPER", 90);
        jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now() where id = ?", gone);
        waits.clear();
        AdminDeskController desk = desk();
        // the paper accounts' switches too (killPaper, 23 Nov 2026: by default only the accounts with a LIVE position)
        AdminDeskController.Batch b1 = batch("batch-1", "EXIT 5 POSITIONS", null, true, null, new long[] {kite1, small},
                new long[] {bobPaper, paper}, new long[] {kite2, big}, new long[] {alicePaper, gone},
                new long[] {demo, 999_999});
        Map<String, Object> out = runBatch(desk, b1);

        // planned and started in this order (the two LIVE accounts then run side by side); paper only once LIVE answered
        assertThat((List<Long>) out.get("order")).containsExactly(big, small, paper);
        assertThat(ran).extracting(Ran::position).containsExactlyInAnyOrder(big, small, paper);
        assertThat(ran.getLast().position()).isEqualTo(paper);
        assertThat(waits).as("the second LIVE start on the same Kite app waits 1/8 s; paper is not paced")
                .containsExactly(125_000_000L);
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).extracting(r -> r.get("outcome"))
                .containsExactly("SOLD", "SOLD", "SOLD", "ALREADY_CLOSED", "REFUSED");
        assertThat(results.get(4)).containsEntry("mirrorOf", "PAPER-1").extractingByKey("detail").asString()
                .contains("exit at the source (PAPER-1)");
        // a LIVE position in it: the accounts' kill switches by default (decision 2), on BEFORE any exit ran; never the mirror
        assertThat(out).containsEntry("killAfter", true);
        assertThat(killedWhenRun).containsOnlyKeys(big, small, paper).allSatisfy((id, on) -> assertThat(on).isTrue());
        assertThat(jdbc.queryForList("select broker_account_id from exec.kill_switch where scope = 'ACCOUNT' and "
                + "released_at is null order by broker_account_id", Long.class))
                .containsExactlyInAnyOrder(kite1, kite2, bobPaper, alicePaper);
        // audited as the admin's, with the reason, the role and the consent; the members told, per account, only of what
        // the exit did (ALICE-PAPER's position was already closed: nobody is told the admin sold it)
        assertThat(ran).allSatisfy(r -> {
            assertThat(r.byAdmin()).isTrue();
            assertThat(r.audit()).containsEntry("by_admin", true).containsEntry("admin_reason", "risk check")
                    .containsEntry("batch", "batch-1").containsEntry("consent", "not given");
        });
        assertThat(ran.stream().filter(r -> r.account() == bobPaper).findFirst().orElseThrow().audit())
                .containsEntry("actor_role", "PLATFORM_ADMIN");
        assertThat(ran.stream().filter(r -> r.account() == kite1).findFirst().orElseThrow().audit())
                .containsEntry("actor_role", "SUPPORT");
        // second review 20 Nov 2026: what was sold with its price, and that entries are stopped (the kill's own alert goes
        // to the admin chat only: by_admin on its audit); ALICE-PAPER (already closed) hears only that it was stopped
        assertThat(told).extracting(t -> t.substring(0, t.indexOf('|')))
                .containsExactlyInAnyOrder("ALICE-KITE", "ALICE-KITE-2", "BOB-PAPER", "ALICE-PAPER");
        assertThat(told).filteredOn(t -> t.startsWith("ALICE-KITE|")).singleElement().asString()
                .contains("NIFTY: 130 sold at 101.00").endsWith(AdminExits.STOPPED);
        assertThat(told).filteredOn(t -> t.startsWith("ALICE-PAPER|")).singleElement()
                .isEqualTo("ALICE-PAPER|risk check|" + AdminExits.STOPPED);
        assertThat(jdbc.queryForList("select distinct broker_account_id from exec.audit_log where action = 'KILL_ACCOUNT' "
                + "and detail ->> 'reason' like 'ADMIN EXIT BATCH by kc-admin%' and detail ->> 'by_admin' = 'true'",
                Long.class)).contains(kite1, kite2, bobPaper, alicePaper);
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'KILL_ACCOUNT' and detail ->> "
                + "'reason' like 'ADMIN EXIT BATCH by kc-admin%' and detail ->> 'by_admin' is null", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'ADMIN_EXIT_BATCH' "
                + "and detail ->> 'key' = 'batch-1'", Integer.class)).isEqualTo(4);

        // the same confirmation again: the same batch, nothing sold again
        org.springframework.http.ResponseEntity<Map<String, Object>> again = desk.exitBatch("kc-admin", b1);
        assertThat(again.getBody()).containsEntry("repeat", true).containsEntry("batchId", out.get("batchId"))
                .containsEntry("results", results);
        assertThat(ran).hasSize(3);
        assertThat(code(() -> desk.batch("kc-admin", "no-such-batch"))).isEqualTo(404);
        assertThat(code(() -> desk.batch("kc-alice", (String) out.get("batchId")))).isEqualTo(403);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPaperOnlyBatchKeepsTheAccountTradingAndARefusalOrPartSaleIsSaidPerPosition() throws Exception {
        long refused = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        long half = position(aliceTenant, alicePaper, aliceGroup, "PAPER", 70);
        long own = position(platform, adminPaper, adminGroup, "PAPER", 60);
        refuse.add(refused);
        partly.add(half);
        Map<String, Object> out = runBatch(desk(), batch("batch-2", "EXIT 3 POSITIONS", null,
                new long[] {bobPaper, refused}, new long[] {alicePaper, half}, new long[] {adminPaper, own}));
        assertThat(out).containsEntry("killAfter", false);
        assertThat(jdbc.queryForObject("select count(*) from exec.kill_switch where released_at is null", Integer.class))
                .isZero();
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results.get(0)).containsEntry("outcome", "REFUSED").extractingByKey("detail").asString()
                .contains("no bid today and no signal since the entry");
        assertThat(results.get(1)).containsEntry("outcome", "PARTLY").containsEntry("detail", "65 of 130 sold, 65 open");
        assertThat(results.get(2)).containsEntry("outcome", "SOLD");
        // the admin's own account: not an admin exit (no reason needed there, nobody else to tell)
        assertThat(ran.stream().filter(r -> r.account() == adminPaper).findFirst().orElseThrow().byAdmin()).isFalse();
        // only what the admin sold is told (BOB-PAPER's exit was refused: nothing to tell; it is in the audit)
        assertThat(told).extracting(t -> t.substring(0, t.indexOf('|'))).containsExactly("ALICE-PAPER");
        // asked explicitly: the kill switches even for paper
        long more = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        assertThat(runBatch(desk(), batch("batch-3", "EXIT 1 POSITIONS", true, new long[] {bobPaper, more})))
                .containsEntry("killAfter", true).containsEntry("killed", List.of("BOB-PAPER"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMembersConsentIsTheOwnersOwnWordShownOnTheDeskAndWrittenIntoTheAudit() throws Exception {
        AdminDeskController desk = desk();
        assertThat(desk.consent("kc-alice", aliceTenant)).containsEntry("given", false).containsEntry("canChange", true);
        assertThat(desk.consent("kc-admin", aliceTenant)).containsEntry("canChange", false);
        assertThat(code(() -> desk.setConsent("kc-admin", aliceTenant, new AdminDeskController.Consent(true))))
                .as("SUPPORT never consents for the member").isEqualTo(403);
        assertThat(code(() -> desk.setConsent("kc-viewer", aliceTenant, new AdminDeskController.Consent(true))))
                .isEqualTo(403);
        assertThat(code(() -> desk.setConsent("kc-bob", aliceTenant, new AdminDeskController.Consent(true))))
                .isEqualTo(404);
        Map<String, Object> given = desk.setConsent("kc-alice", aliceTenant, new AdminDeskController.Consent(true));
        assertThat(given).containsEntry("given", true).containsEntry("by", "kc-alice");
        assertThat((String) given.get("at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where tenant_id = ? and action = "
                + "'ADMIN_EXIT_CONSENT_ON' and actor = 'kc-alice'", Integer.class, aliceTenant)).isEqualTo(1);

        long a = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) desk.openPositions("kc-admin").get("positions");
        assertThat((Map<String, Object>) rows.getFirst().get("consent")).containsEntry("given", true);
        runBatch(desk, batch("batch-4", "EXIT 1 POSITIONS", false, new long[] {kite1, a}));
        assertThat((String) ran.getFirst().audit().get("consent")).startsWith("given ").endsWith(" by kc-alice");

        assertThat(desk.setConsent("kc-alice", aliceTenant, new AdminDeskController.Consent(false)))
                .containsEntry("given", false);
    }

    @Test
    void aNonOwnersSingleExitNeedsAReasonIsAuditedAsTheAdminsAndTellsTheMembers() throws Exception {
        long a = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        long b = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        List<Ran> single = Collections.synchronizedList(new ArrayList<>());
        AccountsController api = new AccountsController(new Access(dataSource), dataSource, null)
                .withAdminExits(recordingExits())
                .withExitRunner((AccountsController.AuditedExitRunner) (account, position, actor, day, quantity, byAdmin,
                                                                       audit) -> {
                    single.add(new Ran(account, position, byAdmin, audit));
                    return List.of("NIFTY: 130 sold at 101.00");
                });
        // SUPPORT (the admin in alice's workspace) and the admin in bob's (not a member): a reason is required
        assertThat(code(() -> api.exitPosition("kc-admin", kite1, a, new AccountsController.PositionExit("EXIT", null, null))))
                .isEqualTo(400);
        assertThat(code(() -> api.exitPosition("kc-admin", bobPaper, b, new AccountsController.PositionExit("EXIT", null,
                "k", " ")))).isEqualTo(400);
        assertThat(single).isEmpty();
        assertThat(api.exitPosition("kc-admin", kite1, a, new AccountsController.PositionExit("EXIT", null, "k1",
                "member unreachable"))).containsEntry("result", List.of("NIFTY: 130 sold at 101.00"));
        assertThat(api.exitPosition("kc-admin", bobPaper, b, new AccountsController.PositionExit("EXIT", null, "k2",
                "drill"))).containsKey("result");
        assertThat(single).hasSize(2).allSatisfy(r -> assertThat(r.byAdmin()).isTrue());
        assertThat(single.getFirst().audit()).containsEntry("by_admin", true).containsEntry("admin_reason",
                "member unreachable").containsEntry("actor_role", "SUPPORT").containsKey("consent");
        assertThat(told).containsExactly("ALICE-KITE|member unreachable|NIFTY: 130 sold at 101.00",
                "BOB-PAPER|drill|NIFTY: 130 sold at 101.00");

        // the owner exits their own as before: no reason, not an admin exit, nobody told
        told.clear();
        assertThat(api.exitPosition("kc-alice", kite1, a, new AccountsController.PositionExit("EXIT", null, null)))
                .containsKey("result");
        assertThat(single.getLast().byAdmin()).isFalse();
        assertThat(told).isEmpty();
        // a VIEWER never; another tenant's OWNER does not even see it; a mirror points at its source
        assertThat(code(() -> api.exitPosition("kc-viewer", kite1, a, new AccountsController.PositionExit("EXIT", null,
                null, "x")))).isEqualTo(403);
        assertThat(code(() -> api.exitPosition("kc-bob", kite1, a, new AccountsController.PositionExit("EXIT", null, null,
                "x")))).isEqualTo(404);
        assertThat(code(() -> api.exitPosition("kc-admin", demo, 1, new AccountsController.PositionExit("EXIT", null, null,
                "x")))).isEqualTo(409);
        // an admin's flatten of a member's account says why too (the worker is not started here: nothing sold)
        assertThat(code(() -> api.flatten("kc-admin", bobPaper, new AccountsController.Flatten("FLATTEN", null))))
                .isEqualTo(400);
    }

    /**
     * Review 20 Nov 2026: a position its strategy closed just before the admin's exit got its turn is ALREADY_CLOSED and
     * nobody is told the admin sold it; an exit that throws after writing an order is UNKNOWN (never REFUSED) with the
     * advice not to sell by hand; one that throws before any order is REFUSED; a consent that cannot be read never stops
     * the exit (consent "unknown"); one account's failed message skips nobody else and the batch still finishes.
     */
    @Test
    @SuppressWarnings("unchecked")
    void eachPositionIsJudgedOnItsLaneAndNoFailureHidesAnUnknownOrStopsTheBatch() throws Exception {
        long first = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        long unknown = position(aliceTenant, kite2, aliceGroup, "LIVE", 110);
        long broken = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        long sold = position(aliceTenant, alicePaper, aliceGroup, "PAPER", 70);
        closedFirst.add(first);
        throwAfterOrder.add(unknown);
        throwBefore.add(broken);
        AdminExits failing = new AdminExits(dataSource, null) {
            @Override
            Map<String, Object> consent(long tenantId) {
                throw new IllegalStateException("consent table unreadable");
            }

            @Override
            void exited(BrokerAccount account, String actor, String reason, List<String> lines) {
                if (account.id() == kite2) {
                    throw new IllegalStateException("telegram down");
                }
                told.add(account.displayName() + "|" + reason + "|" + String.join(";", lines));
            }
        };
        AdminDeskController desk = new AdminDeskController(new Access(dataSource), dataSource, null, failing)
                .withRunner(runner()).withPacer(new OrderPacer(8, () -> 0L, waits::add));
        Map<String, Object> out = runBatch(desk, batch("batch-5", "EXIT 4 POSITIONS", false, new long[] {kite1, first},
                new long[] {kite2, unknown}, new long[] {bobPaper, broken}, new long[] {alicePaper, sold}));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).extracting(r -> r.get("outcome"))
                .containsExactly("ALREADY_CLOSED", "UNKNOWN", "REFUSED", "SOLD");
        assertThat((String) results.get(1).get("detail")).contains("do not sell by hand in the broker app")
                .contains("check the broker's order book first");
        assertThat((String) results.get(2).get("detail")).contains("before any order was sent");
        assertThat(ran).allSatisfy(r -> assertThat((String) r.audit().get("consent")).startsWith("unknown ("));
        // ALICE-KITE (closed first) and BOB-PAPER (refused): nothing the admin sold, no message; ALICE-KITE-2's message
        // failed (it comes before ALICE-PAPER's): the rest still go
        assertThat(told).singleElement().asString().startsWith("ALICE-PAPER|risk check|").contains("130 sold at 101.00");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'ADMIN_EXIT_BATCH' "
                + "and detail ->> 'key' = 'batch-5'", Integer.class)).isEqualTo(4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flattenAllCoversEveryTenantForTheAdminAndNeverAMirror() throws Exception {
        ExecutionService idle = new ExecutionService(dataSource, new ExecutionProperties(null, "*", 0, null, 0, "it", null));
        AccountsController api = new AccountsController(new Access(dataSource), dataSource, idle)
                .withAdminExits(recordingExits());
        Map<String, Object> admin = api.flattenAll("kc-admin", new AccountsController.Flatten("FLATTEN ALL", "drill"));
        assertThat(admin.keySet()).contains("ALICE-KITE", "ALICE-PAPER", "BOB-PAPER", "ADMIN-PAPER").doesNotContain("DEMO-1");
        assertThat(admin.get("BOB-PAPER")).isEqualTo(List.of("the worker has not started: kill switch on, nothing sold"));
        assertThat(api.flattenAll("kc-alice", new AccountsController.Flatten("FLATTEN ALL", "drill")).keySet())
                .contains("ALICE-KITE", "ALICE-PAPER").doesNotContain("BOB-PAPER", "DEMO-1");
        // review 20 Nov 2026: the admin (and SUPPORT) says why; the owner may leave it blank as before
        assertThat(code(() -> api.flattenAll("kc-admin", new AccountsController.Flatten("FLATTEN ALL", " ")))).isEqualTo(400);
        assertThat(api.flattenAll("kc-alice", new AccountsController.Flatten("FLATTEN ALL", null))).isNotEmpty();
        // one account that fails is reported FAILED and the rest are still flattened, with the reason in their audit
        List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        AccountsController failing = new AccountsController(new Access(dataSource), dataSource, idle)
                .withAdminExits(recordingExits()).withFlattener((id, actor, why, day, audit) -> {
                    if (id == kite1) {
                        throw new IllegalStateException("broker down");
                    }
                    reasons.add(why + "|" + audit.get("admin_reason"));
                    return List.of("flattened");
                });
        told.clear();
        Map<String, Object> out = failing.flattenAll("kc-admin", new AccountsController.Flatten("FLATTEN ALL", "storm"));
        assertThat(out.get("ALICE-KITE")).isEqualTo(List.of("FAILED: broker down"));
        assertThat(out.get("BOB-PAPER")).isEqualTo(List.of("flattened"));
        assertThat(out.get("ALICE-KITE-2")).isEqualTo(List.of("flattened"));
        assertThat(reasons).contains("storm|storm");
        assertThat(told).anySatisfy(t -> assertThat(t).startsWith("BOB-PAPER|storm|"));
    }

    /**
     * Phase D2: the exit sheet's LIVE preconditions (broker session, static IP, lease, minutes to the broker's MIS
     * square-off), and when the session is down, what to do instead; a paper exit with no fresh quote says it is refused.
     * The admin previews any tenant's position and is told a reason is needed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theExitPreviewShowsTheLivePreconditionsAndAPaperRefusal() {
        long a = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        long b = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        jdbc.update("insert into exec.account_lease (broker_account_id, owner, epoch, acquired_at, heartbeat_at) values "
                + "(?, 'other-worker', 1, now(), now()) on conflict (broker_account_id) do update set owner = excluded.owner, "
                + "heartbeat_at = now()", kite1);
        LiveBookController api = new LiveBookController(new Access(dataSource), dataSource,
                com.autotrade.execution.BrokerRules.load(java.nio.file.Path.of("..", "config", "brokers",
                        "broker-rules.v1.yaml"))).withLive(com.autotrade.execution.AccountEgress.NONE, "it");
        Map<String, Object> live = api.exitPreview("kc-admin", kite1, a, null);
        assertThat(live).containsEntry("reasonRequired", true).containsKey("brokerDown");
        Map<String, Object> pre = (Map<String, Object>) live.get("preconditions");
        assertThat((Map<String, Object>) pre.get("session")).containsEntry("ok", false);
        assertThat((Map<String, Object>) pre.get("staticIp")).containsEntry("ok", null);      // Zerodha: no own IP needed
        assertThat((Map<String, Object>) pre.get("lease")).containsEntry("ok", false).extractingByKey("text").asString()
                .contains("other-worker");
        assertThat((Map<String, Object>) pre.get("squareOff")).containsEntry("at", "15:26").containsEntry("ours", "15:20")
                .containsKey("minutesLeft");

        // logged in, nobody holding the lease: all clear
        jdbc.update("delete from exec.account_lease where broker_account_id = ?", kite1);
        jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status) values "
                + "(?, 'it', now(), now() + interval '6 hours', 'ACTIVE')", kite1);
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", kite1);
        Map<String, Object> ok = api.exitPreview("kc-alice", kite1, a, null);
        assertThat(ok).containsEntry("reasonRequired", false).doesNotContainKey("brokerDown");
        assertThat((Map<String, Object>) ((Map<String, Object>) ok.get("preconditions")).get("session"))
                .containsEntry("ok", true);
        assertThat((Map<String, Object>) ((Map<String, Object>) ok.get("preconditions")).get("lease"))
                .containsEntry("ok", true);

        // bob's paper position (a tenant the admin is no member of): no bid today and only its own entry signal, so the
        // exit would be refused (never the entry price, principal review 20 Nov 2026); a later signal of the contract (an
        // EXIT) gives the price
        Map<String, Object> paper = api.exitPreview("kc-admin", bobPaper, b, null);
        assertThat(paper).containsEntry("mode", "PAPER").containsEntry("reasonRequired", true)
                .doesNotContainKey("preconditions");
        assertThat((String) paper.get("refusal")).contains("no bid today and no signal since the entry");
        jdbc.update("insert into exec.signal_decision (copy_group_id, signal_session_id, session_date, decided_at, "
                + "strategy_id, priority, underlying, action, option_side, reason, instrument_token, instrument_key, symbol, "
                + "lot_size, reference_price, idempotency_key) select d.copy_group_id, d.signal_session_id, d.session_date, "
                + "now(), d.strategy_id, d.priority, d.underlying, 'EXIT', d.option_side, 'test exit', d.instrument_token, "
                + "d.instrument_key, d.symbol, d.lot_size, 91.0, d.idempotency_key || '-exit' from exec.signal_decision d "
                + "where d.instrument_token = (select instrument_token from exec.position where id = ?) order by d.id limit 1", b);
        Map<String, Object> later = api.exitPreview("kc-admin", bobPaper, b, null);
        assertThat(later).containsEntry("priceFrom", "signal").doesNotContainKey("refusal");
        moveSignalsToYesterday(b);
        assertThat((String) api.exitPreview("kc-admin", bobPaper, b, null).get("refusal"))
                .contains("no bid today and no signal since the entry");
        assertThat(code(() -> api.exitPreview("kc-alice", bobPaper, b, null))).isEqualTo(404);
    }

    private static void moveSignalsToYesterday(long position) {
        jdbc.update("update exec.signal_decision set decided_at = decided_at - interval '1 day' where instrument_token = "
                + "(select instrument_token from exec.position where id = ?)", position);
    }

    /**
     * Review 20 Nov 2026: a real broker's account in PAPER mode exits in the simulation, so its preview is a paper one:
     * no broker preconditions, no "exit in the broker app" advice, the paper pricing (today's old bid with its age) and
     * the paper refusal when the server would refuse.
     */
    @Test
    void aPaperModeBrokerAccountsPreviewIsAPaperOne() {
        AccountStore accounts = new AccountStore(dataSource);
        long alice = jdbc.queryForObject("select id from acct.app_user where external_subject = 'kc-alice'", Long.class);
        long zpaper = accounts.ensureFollowerAccount(aliceTenant, alice, "ALICE-ZERODHA-PAPER", BrokerType.ZERODHA,
                TradeMode.PAPER, 500_000, EquityMode.FIXED, LocalDate.now(), "../config/risk/follower-risk.v3.yaml", 100);
        new FanOut(dataSource).subscribe(aliceGroup, zpaper, List.of("oi-burst-cross"), 1.0);
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where broker_account_id = ?",
                zpaper);
        long p = position(aliceTenant, zpaper, aliceGroup, "PAPER", 90);
        LiveBookController api = new LiveBookController(new Access(dataSource), dataSource,
                com.autotrade.execution.BrokerRules.load(java.nio.file.Path.of("..", "config", "brokers",
                        "broker-rules.v1.yaml"))).withLive(com.autotrade.execution.AccountEgress.NONE, "it");
        long token = jdbc.queryForObject("select instrument_token from exec.position where id = ?", Long.class, p);
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, "
                + "analytics_complete, depth_complete) values (1, current_date, 'NIFTY', now() - interval '20 minutes', 1, ?, "
                + "'X', current_date + 6, 24000, 'CE', 88, '{88}', '{650}', '{3}', '{88.5}', '{650}', '{3}', false, true)",
                token);
        Map<String, Object> stale = api.exitPreview("kc-alice", zpaper, p, null);
        assertThat(stale).containsEntry("mode", "PAPER").containsEntry("priceFrom", "stale-bid").containsEntry("price", 88.0)
                .doesNotContainKeys("preconditions", "brokerDown", "refusal");
        assertThat(((Number) stale.get("bidAgeSec")).longValue()).isGreaterThanOrEqualTo(1200);
        assertThat((String) stale.get("how")).startsWith("paper:");
        jdbc.update("delete from md.option_tick where instrument_token = ?", token);
        moveSignalsToYesterday(p);
        Map<String, Object> refused = api.exitPreview("kc-alice", zpaper, p, null);
        assertThat(refused).doesNotContainKeys("preconditions", "brokerDown");
        assertThat((String) refused.get("refusal")).contains("no bid today and no signal since the entry");
        jdbc.update("update acct.broker_account set enabled = false where id = ?", zpaper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMemberSeesTheAdminsExitsAsANoticeAndTheAdminDoesNot() {
        jdbc.update("insert into exec.audit_log (tenant_id, broker_account_id, actor, action, detail) values (?, ?, "
                + "'flatten', 'POSITION_EXIT', '{\"by_admin\": true, \"actor\": \"kc-admin\", \"admin_reason\": \"drill\", "
                + "\"positions\": [\"NIFTY: 130 sold at 101.00\"]}'::jsonb)", aliceTenant, kite1);
        AdminDeskController desk = desk();
        assertThat(desk.notices("kc-alice")).singleElement().satisfies(n -> assertThat(n)
                .containsEntry("account", "ALICE-KITE").containsEntry("reason", "drill").containsEntry("action",
                        "POSITION_EXIT"));
        assertThat(desk.notices("kc-viewer")).hasSize(1);
        assertThat(desk.notices("kc-admin")).as("the admin's own action").isEmpty();
        assertThat(desk.notices("kc-bob")).isEmpty();
    }

    /**
     * Second review 20 Nov 2026: the same key sent several times at once (a double tap, a client retry while the first
     * request is still planning) starts one batch; before, a repeat arriving while the first was planning found no batch
     * yet, took the key's batch for forgotten and started a second one.
     */
    @Test
    void theSameKeyAtOnceStartsOneBatch() throws Exception {
        long p = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        AdminDeskController desk = desk();
        AdminDeskController.Batch b = batch("same-key", "EXIT 1 POSITIONS", false, new long[] {bobPaper, p});
        List<java.util.concurrent.Future<String>> calls = new ArrayList<>();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return (String) desk.exitBatch("kc-admin", b).getBody().get("batchId");
                }));
            }
            go.countDown();
            Set<String> ids = new java.util.HashSet<>();
            for (java.util.concurrent.Future<String> c : calls) {
                ids.add(c.get());
            }
            assertThat(ids).as("one batch for one key").hasSize(1);
            String id = ids.iterator().next();
            for (int i = 0; i < 200 && !"DONE".equals(desk.batch("kc-admin", id).get("status")); i++) {
                Thread.sleep(50);
            }
        }
        assertThat(ran).as("the position was exited once").hasSize(1);
    }

    /**
     * Second review 20 Nov 2026: a signal source's position (the engine's SIGNAL-1) is refused before anything else, so a
     * batch never engages the signal source's kill switch (decision 1: engine positions are not exited from the desk).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSignalSourceIsRefusedAndNeverStopped() throws Exception {
        long signal = jdbc.queryForObject("select signal_source_account_id from acct.copy_group where id = ?", Long.class,
                aliceGroup);
        long live = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        Map<String, Object> out = runBatch(desk(), batch("sig", "EXIT 2 POSITIONS", null, new long[] {signal, 4242},
                new long[] {kite1, live}));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results.get(0)).containsEntry("outcome", "REFUSED").extractingByKey("detail").asString()
                .contains("signal source");
        assertThat(results.get(1)).containsEntry("outcome", "SOLD");
        assertThat(jdbc.queryForList("select broker_account_id from exec.kill_switch where released_at is null", Long.class))
                .containsExactly(kite1);
    }

    /**
     * Second review 20 Nov 2026: the members are told only what an admin's single exit sold or may have; a position its
     * strategy closed first ("position 7 is not open") or a refused paper exit tells nobody "the admin exited". The
     * consent that cannot be read never blocks the exit (it was read unguarded on this path). An admin's flatten stops the
     * account even before the worker has started, marks its kill by_admin (its alert goes to the admin chat only) and the
     * members' one message says entries are stopped.
     */
    @Test
    void anAdminsExitThatSoldNothingTellsNobodyAndAnUnreadableConsentBlocksNothing() throws Exception {
        long a = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        List<String> answer = Collections.synchronizedList(new ArrayList<>(List.of("position " + a + " is not open")));
        List<Map<String, Object>> audits = Collections.synchronizedList(new ArrayList<>());
        AdminExits unreadable = new AdminExits(dataSource, null) {
            @Override
            Map<String, Object> consent(long tenantId) {
                throw new IllegalStateException("consent table unreadable");
            }

            @Override
            void exited(BrokerAccount account, String actor, String reason, List<String> lines) {
                told.add(account.displayName() + "|" + reason + "|" + String.join(";", lines));
            }
        };
        AccountsController api = new AccountsController(new Access(dataSource), dataSource, new ExecutionService(dataSource,
                new ExecutionProperties(null, "*", 0, null, 0, "it", null))).withAdminExits(unreadable)
                .withExitRunner((AccountsController.AuditedExitRunner) (account, position, actor, day, quantity, byAdmin,
                                                                       audit) -> {
                    audits.add(audit);
                    return List.copyOf(answer);
                });
        api.exitPosition("kc-admin", kite1, a, new AccountsController.PositionExit("EXIT", null, "n1", "check"));
        answer.clear();
        answer.add("NIFTY: NOT exited, no bid today and no signal since the entry for this contract today");
        api.exitPosition("kc-admin", kite1, a, new AccountsController.PositionExit("EXIT", null, "n2", "check"));
        assertThat(told).as("nothing was sold: nobody is told the admin exited").isEmpty();
        assertThat(audits).hasSize(2).allSatisfy(x -> assertThat((String) x.get("consent")).startsWith("unknown ("));
        answer.clear();
        answer.add("NIFTY: 0 of 130 sold (ORDER_UNKNOWN cancel not confirmed); 130 stay open");
        api.exitPosition("kc-admin", kite1, a, new AccountsController.PositionExit("EXIT", null, "n3", "check"));
        assertThat(told).as("may have sold: told").singleElement().asString().contains("ORDER_UNKNOWN");

        told.clear();
        Map<String, Object> flat = api.flatten("kc-admin", bobPaper, new AccountsController.Flatten("FLATTEN", "storm"));
        assertThat(flat.get("result")).isEqualTo(List.of("the worker has not started: kill switch on, nothing sold"));
        assertThat(jdbc.queryForObject("select detail ->> 'by_admin' from exec.audit_log where broker_account_id = ? and "
                + "action = 'KILL_ACCOUNT' order by id desc limit 1", String.class, bobPaper)).isEqualTo("true");
        assertThat(told).containsExactly("BOB-PAPER|storm|" + AdminExits.STOPPED);
    }

    /**
     * Second review 20 Nov 2026: the banner shows an admin's exit that sold something and every admin flatten (it stopped
     * the account), never an exit that sold nothing.
     */
    @Test
    void theBannerLeavesOutAnAdminExitThatSoldNothing() {
        String insert = "insert into exec.audit_log (tenant_id, broker_account_id, actor, action, detail) values (?, ?, "
                + "'flatten', ?, ?::jsonb)";
        jdbc.update(insert, aliceTenant, kite2, "POSITION_EXIT", "{\"by_admin\": true, \"actor\": \"kc-admin\", "
                + "\"positions\": [\"NIFTY: NOT exited, no bid today and no signal since the entry for this contract today\"]}");
        jdbc.update(insert, aliceTenant, kite2, "POSITION_EXIT", "{\"by_admin\": true, \"actor\": \"kc-admin\", "
                + "\"positions\": []}");
        jdbc.update(insert, aliceTenant, kite2, "FLATTEN", "{\"by_admin\": true, \"actor\": \"kc-admin\", "
                + "\"admin_reason\": \"storm\", \"positions\": []}");
        List<Map<String, Object>> shown = desk().notices("kc-alice").stream()
                .filter(n -> "ALICE-KITE-2".equals(n.get("account"))).toList();
        assertThat(shown).singleElement().satisfies(n -> assertThat(n).containsEntry("action", "FLATTEN")
                .containsEntry("reason", "storm"));
    }

    private static AdminDeskController.Batch scoped(String key, String scope, Boolean globalLive,
                                                    AdminDeskController.BatchPosition... positions) {
        return new AdminDeskController.Batch(List.of(positions), "risk check", null, key, "EXIT " + positions.length
                + " POSITIONS", null, globalLive, scope);
    }

    /**
     * User 25 Nov 2026 ("partial exits like the Overview page's open positions"): a batch sells a part of a position, whole
     * lots, through the same lane exit as the per-position Exit; the rest stays open. A count that is not whole lots is
     * refused there and then (nothing sent); asking for all of it or more is a whole exit. The asked part is in the results
     * and the audit.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aBatchSellsAPartOfEachPositionInWholeLotsAndTheRestStaysOpen() throws Exception {
        long half = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        long odd = position(aliceTenant, alicePaper, aliceGroup, "PAPER", 70);
        long whole = position(platform, adminPaper, adminGroup, "PAPER", 60);
        Map<String, Object> out = runBatch(desk(), scoped("part-1", "PAPER", null,
                new AdminDeskController.BatchPosition(bobPaper, half, 65L),
                new AdminDeskController.BatchPosition(alicePaper, odd, 100L),
                new AdminDeskController.BatchPosition(adminPaper, whole, 130L)));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results.get(0)).containsEntry("outcome", "SOLD").containsEntry("asked", 65L)
                .containsEntry("detail", "65 of 130 sold as asked, 65 stay open");
        assertThat(results.get(1)).containsEntry("outcome", "REFUSED").containsEntry("asked", 100L)
                .extractingByKey("detail").asString().contains("not a whole number of lots of 65");
        assertThat(results.get(2)).containsEntry("outcome", "SOLD").doesNotContainKey("asked");
        // the lane got the part, all of it as null (whole exit), and nothing for the refused count
        assertThat(ran).extracting(Ran::position, Ran::quantity).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(half, 65L), org.assertj.core.groups.Tuple.tuple(whole, null));
        assertThat(jdbc.queryForObject("select status || ':' || quantity_open from exec.position where id = ?",
                String.class, half)).isEqualTo("OPEN:65");
        assertThat(jdbc.queryForObject("select status || ':' || quantity_open from exec.position where id = ?",
                String.class, odd)).isEqualTo("OPEN:130");
        // audited per account with the asked part and the book
        Map<String, Object> audit = jdbc.queryForMap("select detail ->> 'positions' positions, detail ->> 'scope' scope "
                + "from exec.audit_log where action = 'ADMIN_EXIT_BATCH' and broker_account_id = ? and detail ->> 'key' = "
                + "'part-1'", bobPaper);
        assertThat(audit).containsEntry("scope", "PAPER");
        assertThat((String) audit.get("positions")).contains("SOLD [65 asked]");
        assertThat(told).filteredOn(t -> t.startsWith("BOB-PAPER|")).singleElement().asString()
                .contains("65 of 130 sold at 101.00; 65 stay open");
        // a quantity of 0 or below: refused as a whole
        assertThat(code(() -> desk().exitBatch("kc-admin", scoped("part-2", "PAPER", null,
                new AdminDeskController.BatchPosition(bobPaper, half, 0L))))).isEqualTo(400);
    }

    /**
     * User 25 Nov 2026 (choose LIVE or PAPER): a batch for one book never touches the other: a position of the other book
     * in it refuses the whole batch before any kill switch or exit; PAPER never engages GLOBAL_LIVE; the view and the audit
     * say the book. No scope is both books, as before.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aBatchForOneBookNeverTouchesTheOther() throws Exception {
        long live = position(aliceTenant, kite1, aliceGroup, "LIVE", 120);
        long paper = position(bobTenant, bobPaper, bobGroup, "PAPER", 80);
        AdminDeskController desk = desk();
        AdminDeskController.BatchPosition l = new AdminDeskController.BatchPosition(kite1, live);
        AdminDeskController.BatchPosition p = new AdminDeskController.BatchPosition(bobPaper, paper);
        assertThat(code(() -> desk.exitBatch("kc-admin", scoped("s-1", "PAPER", null, p, l)))).isEqualTo(409);
        assertThat(code(() -> desk.exitBatch("kc-admin", scoped("s-2", "LIVE", null, l, p)))).isEqualTo(409);
        assertThat(code(() -> desk.exitBatch("kc-admin", scoped("s-3", "PAPER", true, p)))).as("GLOBAL_LIVE").isEqualTo(400);
        assertThat(code(() -> desk.exitBatch("kc-admin", scoped("s-4", "SOME", null, p)))).isEqualTo(400);
        assertThat(ran).as("nothing ran").isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from exec.kill_switch where released_at is null", Integer.class))
                .as("no kill switch").isZero();

        // the refused key is free again: the same confirmation with the right rows runs
        Map<String, Object> paperOnly = runBatch(desk, scoped("s-1", "PAPER", null, p));
        assertThat(paperOnly).containsEntry("scope", "PAPER").containsEntry("killAfter", false)
                .containsEntry("globalLive", null);
        assertThat(ran).extracting(Ran::position).containsExactly(paper);
        assertThat(jdbc.queryForObject("select status from exec.position where id = ?", String.class, live))
                .isEqualTo("OPEN");
        // LIVE with GLOBAL_LIVE: only the LIVE book, its account stopped first
        Map<String, Object> liveOnly = runBatch(desk, scoped("s-5", "LIVE", true, l));
        assertThat(liveOnly).containsEntry("scope", "LIVE").containsEntry("globalLive", "ENGAGED")
                .containsEntry("killed", List.of("ALICE-KITE"));
        assertThat(jdbc.queryForObject("select detail ->> 'scope' from exec.audit_log where action = 'ADMIN_EXIT_BATCH' "
                + "and detail ->> 'key' = 's-5'", String.class)).isEqualTo("LIVE");
        // no scope: both books, as before
        long live2 = position(aliceTenant, kite2, aliceGroup, "LIVE", 50);
        long paper2 = position(bobTenant, bobPaper, bobGroup, "PAPER", 40);
        assertThat(runBatch(desk, batch("s-6", "EXIT 2 POSITIONS", false, new long[] {kite2, live2},
                new long[] {bobPaper, paper2}))).containsEntry("scope", "BOTH");
    }
}
