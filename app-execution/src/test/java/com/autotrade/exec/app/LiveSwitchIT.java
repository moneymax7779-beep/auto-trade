package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.FanOut;
import com.autotrade.execution.SignalDecision;
import com.autotrade.execution.SignalDecisionStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The LIVE switch (the Kite pilot, user 2 Nov 2026): only an OWNER or ADMIN, only by typing the account's name, only a
 * Zerodha DRY_RUN account on a risk file with live limits, at one lot an entry, with today's Kite session and no open
 * position; off again only with no position open. Each switch is audited.
 */
@Testcontainers(disabledWithoutDocker = true)
class LiveSwitchIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final String V6 = "../config/risk/follower-risk.v6.yaml";
    private static final String V7 = "../config/risk/follower-risk.v7.yaml";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static LiveSwitchController api;
    private static long tenant;
    private static long owner;
    private static long profile;
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
        AccountStore accounts = new AccountStore(dataSource);
        tenant = accounts.ensureTenant("owner");
        owner = accounts.ensureUser("local-owner", "me");
        accounts.ensureMembership(tenant, owner, "OWNER");
        accounts.ensureMembership(tenant, accounts.ensureUser("kc-trader", "trader"), "TRADER");
        accounts.ensureMembership(tenant, accounts.ensureUser("kc-admin", "admin"), "ADMIN");
        profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'TENANT_SHARED', 'KITE-1', 'env:KITE_API_KEY', "
                + "'env:KITE_API_SECRET') returning id", Long.class, tenant);
        api = new LiveSwitchController(new Access(dataSource), dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    /** A Zerodha DRY_RUN follower with the app's profile; {@code session}: today's Kite session is ACTIVE. */
    private static long account(String riskFile, double sizeStep, boolean session) {
        String name = "KITE-" + (++seq);
        long id = new AccountStore(dataSource).ensureFollowerAccount(tenant, owner, name, BrokerType.ZERODHA,
                TradeMode.DRY_RUN, 200_000, EquityMode.FIXED, LocalDate.now(), riskFile, sizeStep);
        jdbc.update("update acct.broker_account set credential_profile_id = ? where id = ?", profile, id);
        if (session) {
            jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status) "
                    + "values (?, 'session-file:it', now(), now() + interval '8 hours', 'ACTIVE')", id);
        }
        return id;
    }

    private static String name(long id) {
        return jdbc.queryForObject("select display_name from acct.broker_account where id = ?", String.class, id);
    }

    private static String mode(long id) {
        return jdbc.queryForObject("select trade_mode || '/' || live_trading_enabled from acct.broker_account where id = ?",
                String.class, id);
    }

    private static String refusal(Runnable call) {
        try {
            call.run();
            return "allowed";
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value() + " " + e.getReason();
        }
    }

    /** V32 (user 9 Nov 2026): the platform owner selects or deselects LIVE strategies; everyone else only sees them. */
    @Test
    void onlyThePlatformOwnerChoosesLiveStrategiesWithinTheRiskFile() {
        long id = account("../config/risk/follower-risk.v9.yaml", 0, false);
        Map<String, Object> view = api.liveStrategies("local-owner", id);
        assertThat(view.get("available")).isEqualTo(List.of("break-retest-runner", "early-confirm-runner",
                "expiry-trend-rider", "ma-cross", "oi-burst-cross", "wall-break-rider"));
        assertThat(view.get("effective")).isEqualTo(List.of("early-confirm-runner", "ma-cross", "oi-burst-cross"));
        assertThat(view.get("chosen")).isNull();
        // the account's OWNER manages it (user 11 Nov 2026: follower accounts switch the owner's LIVE strategies)
        assertThat(view.get("canEdit")).isEqualTo(true);
        assertThat(api.liveStrategies("kc-admin", id).get("canEdit")).isEqualTo(true);
        assertThat(api.liveStrategies("kc-trader", id).get("canEdit")).isEqualTo(false);

        // a TRADER may not change it
        assertThat(refusal(() -> api.chooseLiveStrategies("kc-trader", id,
                new LiveSwitchController.LiveChoice(List.of("ma-cross"), null)))).startsWith("403");
        // the owner drops ecr and adds wall-break-rider
        Map<String, Object> after = api.chooseLiveStrategies("kc-admin", id, new LiveSwitchController.LiveChoice(
                List.of("ma-cross", "oi-burst-cross", "wall-break-rider"), null));
        assertThat(after.get("effective")).isEqualTo(List.of("ma-cross", "oi-burst-cross", "wall-break-rider"));
        assertThat(after.get("chosen")).isEqualTo(List.of("ma-cross", "oi-burst-cross", "wall-break-rider"));
        // never wider than the risk file
        assertThat(refusal(() -> api.chooseLiveStrategies("kc-admin", id,
                new LiveSwitchController.LiveChoice(List.of("opening-drive"), null))))
                .startsWith("400 not allowed LIVE by the risk file: [opening-drive]");
        // none at all is a valid choice; reset goes back to the file's default
        assertThat(api.chooseLiveStrategies("kc-admin", id, new LiveSwitchController.LiveChoice(List.of(), null))
                .get("effective")).isEqualTo(List.of());
        assertThat(api.chooseLiveStrategies("kc-admin", id, new LiveSwitchController.LiveChoice(null, true))
                .get("chosen")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'LIVE_STRATEGIES'", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select detail->>'after' from exec.audit_log where broker_account_id = ? and "
                + "action = 'LIVE_STRATEGIES' order by id limit 1", String.class, id)).contains("wall-break-rider");
    }

    /** V33: the owner's global LIVE list, standing until changed; an account with its own list keeps it. */
    @Test
    void onlyThePlatformOwnerSetsTheGlobalLiveList() {
        long follows = account("../config/risk/follower-risk.v9.yaml", 0, false);
        long own = account("../config/risk/follower-risk.v9.yaml", 0, false);
        try {
            api.chooseLiveStrategies("kc-admin", own, new LiveSwitchController.LiveChoice(List.of("ma-cross"), null));
            // the owner's set-up is not shown to anyone else (members: the chart and trades only)
            assertThat(refusal(() -> api.globalLiveStrategies("local-owner"))).startsWith("403");
            assertThat(refusal(() -> api.globalLiveStrategies("kc-trader"))).startsWith("403");
            Map<String, Object> view = api.globalLiveStrategies("kc-admin");
            assertThat(view.get("available")).asString().contains("break-retest-runner", "early-confirm-runner",
                    "expiry-trend-rider", "ma-cross", "oi-burst-cross", "wall-break-rider");
            assertThat(view.get("global")).isNull();
            assertThat(view.get("canEdit")).isEqualTo(true);
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("local-owner",
                    new LiveSwitchController.GlobalChoice(List.of("ma-cross"), null, null)))).startsWith("403");

            // the owner takes ecr out of LIVE everywhere and adds wall-break-rider
            Map<String, Object> after = api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(
                    List.of("ma-cross", "oi-burst-cross", "wall-break-rider"), null, null));
            assertThat(after.get("global")).isEqualTo(List.of("ma-cross", "oi-burst-cross", "wall-break-rider"));
            assertThat(api.liveStrategies("local-owner", follows).get("effective"))
                    .isEqualTo(List.of("ma-cross", "oi-burst-cross", "wall-break-rider"));
            assertThat(api.liveStrategies("local-owner", follows).get("source")).isEqualTo("GLOBAL");
            assertThat(api.liveStrategies("local-owner", own).get("effective")).isEqualTo(List.of("ma-cross"));
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("kc-admin",
                    new LiveSwitchController.GlobalChoice(List.of("opening-drive"), null, null))))
                    .startsWith("400 not a LIVE strategy of any account's risk file: [opening-drive]");
            // the account back under the global list; the global list back to the defaults
            api.chooseLiveStrategies("kc-admin", own, new LiveSwitchController.LiveChoice(null, true));
            assertThat(api.liveStrategies("local-owner", own).get("source")).isEqualTo("GLOBAL");
            assertThat(api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(null, true, null))
                    .get("global")).isNull();
            assertThat(api.liveStrategies("local-owner", follows).get("effective"))
                    .isEqualTo(List.of("early-confirm-runner", "ma-cross", "oi-burst-cross"));
            assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'LIVE_STRATEGIES_GLOBAL'",
                    Integer.class)).isEqualTo(2);
        } finally {
            jdbc.update("delete from acct.live_strategy_policy");
        }
    }

    /**
     * V35 (user 11 Nov 2026): the owner turns a strategy OFF everywhere (no PAPER, no LIVE); OFF also takes it out of
     * every LIVE list, so switching it back on makes it PAPER until LIVE is ticked again.
     */
    @Test
    void theOwnerTurnsAStrategyOffOnPaperAndLive() {
        long follows = account("../config/risk/follower-risk.v9.yaml", 0, false);
        long own = account("../config/risk/follower-risk.v9.yaml", 0, false);
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('PAPER-1', 'PAPER_LIVE', current_date, 'it', "
                + "'ma-cross+oi-burst-cross+opening-drive', 'h', '{}'::jsonb, 'it', 'DONE') returning id", Long.class);
        try {
            api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(
                    List.of("ma-cross", "oi-burst-cross"), null, null));
            api.chooseLiveStrategies("kc-admin", own, new LiveSwitchController.LiveChoice(List.of("ma-cross"), null));
            Map<String, Object> view = api.globalLiveStrategies("kc-admin");
            assertThat(view.get("running")).isEqualTo(List.of("ma-cross", "oi-burst-cross", "opening-drive"));
            assertThat(view.get("off")).isEqualTo(List.of());
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("local-owner",
                    new LiveSwitchController.GlobalChoice(null, null, List.of("ma-cross"))))).startsWith("403");

            // ma-cross OFF (LIVE-eligible) and opening-drive OFF (runs on PAPER only, in no risk file)
            Map<String, Object> after = api.chooseGlobalLiveStrategies("kc-admin",
                    new LiveSwitchController.GlobalChoice(null, null, List.of("ma-cross", "opening-drive")));
            assertThat(after.get("off")).isEqualTo(List.of("ma-cross", "opening-drive"));
            assertThat(after.get("global")).isEqualTo(List.of("oi-burst-cross"));
            assertThat(api.liveStrategies("local-owner", own).get("chosen")).isEqualTo(List.of());
            assertThat(api.liveStrategies("local-owner", follows).get("effective")).isEqualTo(List.of("oi-burst-cross"));
            assertThat(api.liveStrategies("local-owner", follows).get("off")).isEqualTo(List.of("ma-cross", "opening-drive"));

            // never both LIVE and OFF, never an unknown strategy, and a refused request changes nothing
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("kc-admin",
                    new LiveSwitchController.GlobalChoice(List.of("ma-cross"), null, null))))
                    .startsWith("400 a strategy cannot be both LIVE and OFF: [ma-cross]");
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("kc-admin",
                    new LiveSwitchController.GlobalChoice(List.of("oi-burst-cross"), null, List.of("oi-burst-cross")))))
                    .startsWith("400 a strategy cannot be both LIVE and OFF");
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("kc-admin",
                    new LiveSwitchController.GlobalChoice(List.of(), null, List.of("no-such-strategy")))))
                    .startsWith("400 not a running or LIVE-eligible strategy: [no-such-strategy]");
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("kc-admin",
                    new LiveSwitchController.GlobalChoice(null, null, null)))).startsWith("400");
            assertThat(api.globalLiveStrategies("kc-admin").get("global")).isEqualTo(List.of("oi-burst-cross"));

            // back on: PAPER, not LIVE; then the owner makes it LIVE again in the same request
            Map<String, Object> on = api.chooseGlobalLiveStrategies("kc-admin",
                    new LiveSwitchController.GlobalChoice(null, null, List.of("opening-drive")));
            assertThat(on.get("off")).isEqualTo(List.of("opening-drive"));
            assertThat(on.get("global")).isEqualTo(List.of("oi-burst-cross"));
            assertThat(api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(
                    List.of("ma-cross", "oi-burst-cross"), null, List.of())).get("global"))
                    .isEqualTo(List.of("ma-cross", "oi-burst-cross"));
            assertThat(api.liveStrategies("local-owner", follows).get("effective"))
                    .isEqualTo(List.of("ma-cross", "oi-burst-cross"));

            assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'STRATEGIES_OFF'",
                    Integer.class)).isEqualTo(3);
            // the account whose own list lost ma-cross has the change in its own history
            assertThat(jdbc.queryForObject("select detail->>'reason' from exec.audit_log where broker_account_id = ? and "
                    + "action = 'LIVE_STRATEGIES' order by id desc limit 1", String.class, own))
                    .isEqualTo("turned OFF for every account: [ma-cross, opening-drive]");
            // a null or blank name is ignored, never a server error
            assertThat(api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(null, null,
                    java.util.Arrays.asList("opening-drive", null, " "))).get("off")).isEqualTo(List.of("opening-drive"));
            // a strategy since dropped from the deployed set and every risk file can still be kept OFF or switched on
            jdbc.update("update trade.session set strategy_id = 'ma-cross+oi-burst-cross' where id = ?", session);
            assertThat(api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(null, null,
                    List.of("ma-cross", "opening-drive"))).get("off")).isEqualTo(List.of("ma-cross", "opening-drive"));
            assertThat(api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(null, null,
                    List.of("ma-cross"))).get("off")).isEqualTo(List.of("ma-cross"));
            assertThat(refusal(() -> api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(
                    null, null, List.of("ma-cross", "opening-drive"))))).startsWith("400 not a running or LIVE-eligible");
            assertThat(jdbc.queryForObject("select detail->>'accountListsChanged' from exec.audit_log where action = "
                    + "'STRATEGIES_OFF' order by id limit 1", String.class)).isEqualTo("[" + own + "]");

            // the live session's reader: cached, and it sees a change after its interval
            jdbc.update("delete from acct.strategy_off");
            java.util.function.Supplier<java.util.Set<String>> reader =
                    com.autotrade.execution.LiveStrategies.offReader(dataSource, java.time.Duration.ofHours(1));
            assertThat(reader.get()).isEmpty();
            jdbc.update("insert into acct.strategy_off (strategy, changed_by) values ('ma-cross', 'it')");
            assertThat(reader.get()).isEmpty();
            assertThat(com.autotrade.execution.LiveStrategies.offReader(dataSource, java.time.Duration.ZERO).get())
                    .containsExactly("ma-cross");
        } finally {
            jdbc.update("delete from acct.strategy_off");
            jdbc.update("delete from acct.live_strategy_policy");
            jdbc.update("update acct.broker_account set live_strategies = null where id in (?, ?)", follows, own);
            jdbc.update("delete from trade.session where id = ?", session);
            // the global-list test counts its own audit rows
            jdbc.update("delete from exec.audit_log where action in ('LIVE_STRATEGIES_GLOBAL', 'STRATEGIES_OFF')");
        }
    }

    /**
     * User 11 Nov 2026: "if choose PAPER ... Follower/Demo accounts shouldn't make it LIVE, only if choose LIVE -
     * Follower/Demo accounts should switch from PAPER/LIVE": the account's owner switches the platform owner's LIVE
     * strategies only; the owner's PAPER ones never trade LIVE, whatever the account's own list holds; OFF ones are hidden.
     */
    @Test
    void anAccountsOwnerSwitchesOnlyTheOwnersLiveStrategies() {
        long id = account("../config/risk/follower-risk.v9.yaml", 0, false);
        try {
            api.chooseLiveStrategies("local-owner", id, new LiveSwitchController.LiveChoice(
                    List.of("ma-cross", "wall-break-rider"), null));                // no global list yet: the file's list
            api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(
                    List.of("ma-cross", "oi-burst-cross"), null, List.of("early-confirm-runner")));
            Map<String, Object> view = api.liveStrategies("local-owner", id);
            assertThat(view.get("choosable")).isEqualTo(List.of("ma-cross", "oi-burst-cross"));
            assertThat(view.get("paperOnly")).isEqualTo(List.of("break-retest-runner", "expiry-trend-rider",
                    "wall-break-rider"));
            // wall-break-rider is still in the account's own list, but the owner has it on PAPER: it does not trade LIVE
            assertThat(view.get("chosen")).isEqualTo(List.of("ma-cross", "wall-break-rider"));
            assertThat(view.get("effective")).isEqualTo(List.of("ma-cross"));
            // the account's owner switches oi-burst-cross to LIVE and ma-cross to PAPER, within the owner's LIVE list
            assertThat(api.chooseLiveStrategies("local-owner", id, new LiveSwitchController.LiveChoice(
                    List.of("oi-burst-cross"), null)).get("effective")).isEqualTo(List.of("oi-burst-cross"));
            assertThat(refusal(() -> api.chooseLiveStrategies("local-owner", id, new LiveSwitchController.LiveChoice(
                    List.of("wall-break-rider"), null)))).startsWith("400 the platform owner has [wall-break-rider] on PAPER only");
            assertThat(refusal(() -> api.chooseLiveStrategies("local-owner", id, new LiveSwitchController.LiveChoice(
                    List.of("early-confirm-runner"), null)))).startsWith("400 turned OFF for every account");
            // the platform owner moves oi-burst-cross to PAPER: off LIVE on the account at once
            api.chooseGlobalLiveStrategies("kc-admin", new LiveSwitchController.GlobalChoice(List.of("ma-cross"), null,
                    null));
            assertThat(api.liveStrategies("local-owner", id).get("effective")).isEqualTo(List.of());
            assertThat(jdbc.queryForObject("select actor from exec.audit_log where broker_account_id = ? and action = "
                    + "'LIVE_STRATEGIES' order by id desc limit 1", String.class, id)).isEqualTo("local-owner");
        } finally {
            jdbc.update("delete from acct.strategy_off");
            jdbc.update("delete from acct.live_strategy_policy");
            jdbc.update("delete from exec.audit_log where action in ('LIVE_STRATEGIES_GLOBAL', 'STRATEGIES_OFF')");
        }
    }

    @Test
    void theOwnerSwitchesLiveByTypingTheNameAndBackWhenFlat() {
        long id = account(V7, 0, true);
        assertThat(refusal(() -> api.live("local-owner", id, new LiveSwitchController.Switch(true, "wrong"))))
                .startsWith("400").contains("type the account's name");
        assertThat(mode(id)).isEqualTo("DRY_RUN/false");
        api.live("local-owner", id, new LiveSwitchController.Switch(true, name(id)));
        assertThat(mode(id)).isEqualTo("LIVE/true");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'LIVE_ON'", Integer.class, id)).isEqualTo(1);
        api.live("local-owner", id, new LiveSwitchController.Switch(false, null));
        assertThat(mode(id)).isEqualTo("DRY_RUN/false");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? and action = "
                + "'LIVE_OFF'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void everyConditionIsCheckedBeforeAnAccountGoesLive() {
        long noLimits = account(V6, 0, true);
        assertThat(refusal(() -> api.live("local-owner", noLimits, new LiveSwitchController.Switch(true, name(noLimits)))))
                .contains("no live limits");
        long fullSize = account(V7, 100, true);
        assertThat(refusal(() -> api.live("local-owner", fullSize, new LiveSwitchController.Switch(true, name(fullSize)))))
                .contains("one lot an entry");
        long noLogin = account(V7, 0, false);
        assertThat(refusal(() -> api.live("local-owner", noLogin, new LiveSwitchController.Switch(true, name(noLogin)))))
                .contains("no Kite session");
        long byTrader = account(V7, 0, true);
        assertThat(refusal(() -> api.live("kc-trader", byTrader, new LiveSwitchController.Switch(true, name(byTrader)))))
                .startsWith("403");
        for (long id : new long[] {noLimits, fullSize, noLogin, byTrader}) {
            assertThat(mode(id)).isEqualTo("DRY_RUN/false");
        }
    }

    @Test
    void anAccountWithAnOpenPositionIsNotSwitchedEitherWay() {
        long id = account(V7, 0, true);
        api.live("local-owner", id, new LiveSwitchController.Switch(true, name(id)));
        openPosition(id);
        assertThat(refusal(() -> api.live("local-owner", id, new LiveSwitchController.Switch(false, null))))
                .contains("flatten them first");
        assertThat(mode(id)).isEqualTo("LIVE/true");
    }

    /** An OPEN exec.position of the account, from a fanned-out decision (the intent it needs). */
    private static void openPosition(long accountId) {
        AccountStore accounts = new AccountStore(dataSource);
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-" + accountId, 500_000, LocalDate.now(),
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "G-" + accountId, signal);
        new FanOut(dataSource).subscribe(group, accountId, List.of("ma-cross"), 1.0);
        // the test's clock (decided_at) and the database's (enabled_at) differ by up to a second in Docker: a
        // subscription made a moment before its decision must not count as made after it (full-suite flake, 8 Nov 2026)
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?", group);
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values (?, 'PAPER_REPLAY', current_date, 'it', 'live-set', "
                + "'sha256:it', '{}'::jsonb, 'it', 'DONE') returning id", Long.class, "SIG-" + accountId);
        new SignalDecisionStore(dataSource).insert(new SignalDecision(group, session, LocalDate.now(), Instant.now(),
                "ma-cross", 2, "NIFTY", SignalDecision.Action.ENTER, "PE", null, "CONFIRMED", "IT", 3_000_047_168L,
                "NSE_FO|47168", "NIFTY 23100 PE", 65, 100, 6_500, 500_000, 0, group + ":" + session + ":1", Map.of()));
        new FanOut(dataSource).fanOut(group, LocalDate.now());
        long intent = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ? order by id desc "
                + "limit 1", Long.class, accountId);
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                + "option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, status) values (?, ?, ?, "
                + "'ma-cross', 'NIFTY', 'PE', 3000047168, 'NIFTY 23100 PE', 65, 100, now(), 'OPEN')", tenant, accountId,
                intent);
    }
}
