package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Trader workspaces through the API (V29): a member's brokers with their paper twins, the day's broker and the
 * PAPER/LIVE switch with every refusal, the admin's LIVE eligibility, SUPPORT without and with the member's grant,
 * break-glass, the household view, and what an ACCOUNT_MODE workspace keeps.
 */
@Testcontainers(disabledWithoutDocker = true)
class WorkspaceIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final String V7 = "../config/risk/follower-risk.v7.yaml";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static MembersController members;
    private static AccountAdminController admin;
    private static AccountsController api;
    private static WorkspaceController workspaces;
    private static SupportController support;
    private static LiveSwitchController liveSwitch;
    private static long ownerTenant, group;

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
        ownerTenant = accounts.ensureTenant("owner");
        long satya = accounts.ensureUser("satya", "satya");
        accounts.ensureMembership(ownerTenant, satya, "ADMIN");
        long signal = accounts.ensureSignalSourceAccount(ownerTenant, satya, "SIGNAL-1", 500_000, LocalDate.of(2026, 11, 1),
                "../config/risk/signal-source.v2.yaml");
        group = accounts.ensureCopyGroup(ownerTenant, "LIVE-SET", signal);

        Access access = new Access(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ExecutionProperties properties = new ExecutionProperties(null, "*", 1000, null, 0, "it", null);
        TelegramNotifier telegram = new TelegramNotifier(new KiteLoginProperties(null, null, null, null, null,
                "/nonexistent/bot.env"), dataSource);
        members = new MembersController(access, dataSource, tx, new KeycloakAdmin(new KeycloakAdminProperties(null, null,
                null, null)), telegram);
        admin = new AccountAdminController(access, dataSource, tx, properties);
        api = new AccountsController(access, dataSource, new ExecutionService(dataSource, properties));
        workspaces = new WorkspaceController(access, dataSource, tx, telegram);
        support = new SupportController(access, dataSource, telegram);
        liveSwitch = new LiveSwitchController(access, dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static int code(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private static String reason(Runnable call) {
        try {
            call.run();
            return "";
        } catch (ResponseStatusException e) {
            return String.valueOf(e.getReason());
        }
    }

    private static long member(String username, String role) {
        return (Long) members.add("satya", new MembersController.NewMember(username, null, role, null, "SPOUSE", null, null))
                .get("tenantId");
    }

    private static long account(String who, long tenant, String name, String broker, String clientId) {
        return ((Number) admin.create(who, new AccountAdminController.NewAccount(name, broker, null, clientId, null,
                300_000.0, "FIXED", null, null, List.of("ma-cross", "early-confirm-runner"), true, tenant)).get("id"))
                .longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(String who, long tenant) {
        return (Map<String, Object>) workspaces.today(who, tenant).get("state");
    }

    /** Makes the member's Zerodha account LIVE-ready: a broker app, a login that worked, a session today. */
    private static void loggedIn(long tenant, long account) {
        long profile = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'USER_PRIVATE', ?, 'store:k', 'store:s') returning id",
                Long.class, tenant, "app-" + account);
        jdbc.update("update acct.broker_account set credential_profile_id = ?, risk_file = ? where id = ?", profile, V7,
                account);
        jdbc.update("insert into exec.audit_log (tenant_id, broker_account_id, actor, action, detail) values "
                + "(?, ?, 'it', 'SESSION_LOGIN', '{}')", tenant, account);
        jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status) values "
                + "(?, 'file:kite-x.token', now(), now() + interval '6 hours', 'ACTIVE')", account);
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", account);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMembersDayFromPaperToLiveWithEveryRefusal() {
        long asha = member("asha", "OWNER");
        // she adds her own brokers: each real one is PAPER with a paper twin; any of the four brokers
        long zerodha = account("asha", asha, "Zerodha", "ZERODHA", "AB1234");
        long upstox = account("asha", asha, "Upstox", "UPSTOX", "7AB12C");
        long groww = account("asha", asha, "Groww", "GROWW", "924189");
        AccountStore accounts = new AccountStore(dataSource);
        BrokerAccount z = accounts.byId(zerodha).orElseThrow();
        assertThat(z.tradeMode().name()).isEqualTo("PAPER");
        assertThat(z.riskFile()).endsWith("follower-risk.v9.yaml");
        assertThat(z.sizeStepPct()).isZero();
        Long twin = jdbc.queryForObject("select id from acct.broker_account where paper_of_account_id = ?", Long.class, zerodha);
        assertThat(accounts.byId(twin).orElseThrow().brokerType().name()).isEqualTo("PAPER");
        assertThat(code(() -> account("asha", asha, "Bad", "UPSTOX", "not a code!"))).isEqualTo(400);
        assertThat(code(() -> admin.create("asha", new AccountAdminController.NewAccount("Kite2", "ZERODHA", "DRY_RUN",
                "AB9999", null, 100_000.0, "FIXED", null, null, List.of(), true, asha)))).as("no mode of its own").isEqualTo(400);

        // the first account is the default broker, PAPER; the view lists brokers, never twins
        Map<String, Object> today = workspaces.today("asha", asha);
        assertThat((List<Map<String, Object>>) today.get("brokers")).extracting(b -> b.get("name"))
                .containsExactly("Zerodha", "Upstox", "Groww");
        assertThat(state("asha", asha)).containsEntry("brokerAccountId", zerodha).containsEntry("mode", "PAPER")
                .containsEntry("targetAccountId", twin).containsEntry("trading", "PAPER");

        // choosing: hers yes; a twin, another workspace's account, and a viewer: no
        workspaces.broker("asha", asha, new WorkspaceController.Broker(upstox));
        assertThat(state("asha", asha)).containsEntry("broker", "Upstox").containsEntry("mode", "PAPER");
        assertThat(code(() -> workspaces.broker("asha", asha, new WorkspaceController.Broker(twin)))).isEqualTo(400);
        long bina = member("bina", "OWNER");
        long binaAccount = account("bina", bina, "Zerodha", "ZERODHA", "CD5678");
        assertThat(code(() -> workspaces.broker("asha", asha, new WorkspaceController.Broker(binaAccount)))).isEqualTo(404);
        assertThat(code(() -> workspaces.today("asha", bina))).isEqualTo(404);
        long chitra = member("chitra", "VIEWER");
        assertThat(code(() -> workspaces.broker("chitra", chitra, new WorkspaceController.Broker(null)))).isEqualTo(403);

        // LIVE on a broker without an adapter, without eligibility, without a session: refused, with the reason
        assertThat(reason(() -> workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"))))
                .contains("UPSTOX has no LIVE order adapter");
        workspaces.broker("asha", asha, new WorkspaceController.Broker(zerodha));
        assertThat(code(() -> workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", null))))
                .as("must type LIVE").isEqualTo(400);
        assertThat(reason(() -> workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"))))
                .contains("not LIVE-eligible");

        // eligibility is the platform admin's, after a login worked
        assertThat(code(() -> workspaces.liveEligible("asha", zerodha, new WorkspaceController.Eligible(true, "Zerodha"))))
                .isEqualTo(403);
        assertThat(reason(() -> workspaces.liveEligible("satya", zerodha, new WorkspaceController.Eligible(true,
                "Zerodha")))).contains("set the broker client id and the broker app first");
        loggedIn(asha, zerodha);
        assertThat(code(() -> workspaces.liveEligible("satya", zerodha, new WorkspaceController.Eligible(true, "zerodha"))))
                .as("the name typed exactly").isEqualTo(400);
        assertThat(code(() -> workspaces.liveEligible("satya", groww, new WorkspaceController.Eligible(true, "Groww"))))
                .isEqualTo(400);
        assertThat(code(() -> workspaces.liveEligible("satya", twin, new WorkspaceController.Eligible(true, "Zerodha paper"))))
                .isEqualTo(400);
        workspaces.liveEligible("satya", zerodha, new WorkspaceController.Eligible(true, "Zerodha"));
        assertThat(accounts.byId(zerodha).orElseThrow().liveTradingEnabled()).isTrue();
        assertThat(state("asha", asha)).containsEntry("targetAccountId", twin).as("eligible, still PAPER");

        // her kill switch and her loss limit stop LIVE; a session lost too
        long kill = ((Number) api.engage("asha", new AccountsController.Engage("TENANT", null, null, null, "pause", asha,
                null)).get("id")).longValue();
        assertThat(reason(() -> workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"))))
                .contains("kill switch is engaged");
        api.release("asha", kill);
        jdbc.update("update acct.broker_account set connection_status = 'TOKEN_EXPIRED' where id = ?", zerodha);
        assertThat(reason(() -> workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"))))
                .contains("no broker session today");
        jdbc.update("update acct.broker_account set connection_status = 'ACTIVE' where id = ?", zerodha);

        // LIVE: the day's target is the broker itself; an entry from now goes there
        workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"));
        assertThat(state("asha", asha)).containsEntry("mode", "LIVE").containsEntry("targetAccountId", zerodha)
                .containsEntry("trading", "LIVE");
        assertThat(jdbc.queryForList("select action from exec.audit_log where tenant_id = ? order by id", String.class, asha))
                .contains("TODAYS_BROKER", "LIVE_ELIGIBLE_ON", "KILL_TENANT", "KILL_RELEASED", "MODE_LIVE");

        // switching broker while LIVE: the new one starts PAPER; it cannot go LIVE while the old one holds a live position
        long intent = liveIntent(asha, zerodha);
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                + "option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, status) values "
                + "(?, ?, ?, 'ma-cross', 'NIFTY', 'CE', 77, 'NIFTY 23000 CE', 65, 100, now(), 'OPEN')", asha, zerodha, intent);
        // the Accounts page: each account's book today and the twin's link to its real account (user 9 Nov 2026)
        assertThat(view("asha", zerodha).today()).isEqualTo(new AccountsController.DayBook(0, 0, 1, 0, 0, 1));
        assertThat(view("asha", zerodha).paperOf()).isNull();
        assertThat(view("asha", twin).paperOf()).isEqualTo(zerodha);
        assertThat(view("asha", twin).today()).isEqualTo(AccountsController.DayBook.NONE);
        // a PAPER-day trade on the twin, closed today, and one closed yesterday: today's paper only, never counted LIVE
        long paperIntent = intent(asha, twin, "PAPER");
        for (String closedAt : List.of("now()", "now() - interval '1 day'")) {
            jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, "
                    + "underlying, option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, closed_at, "
                    + "realised_gross, charges, status) values (?, ?, ?, 'ma-cross', 'NIFTY', 'PE', 78, 'NIFTY 23000 PE', 0, "
                    + "100, now() - interval '1 day', " + closedAt + ", 1500, 20, 'CLOSED')", asha, twin, paperIntent);
        }
        assertThat(view("asha", twin).today()).isEqualTo(new AccountsController.DayBook(1480, 1, 0, 0, 0, 0));
        workspaces.broker("asha", asha, new WorkspaceController.Broker(groww));
        assertThat(state("asha", asha)).containsEntry("broker", "Groww").containsEntry("mode", "PAPER");
        assertThat(reason(() -> workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"))))
                .contains("GROWW has no LIVE order adapter");
        // back to Zerodha: LIVE again is allowed (its own position is not "another broker")
        workspaces.broker("asha", asha, new WorkspaceController.Broker(zerodha));
        workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"));
        // the member's LIVE loss limit, reached: LIVE refused for the rest of the day
        workspaces.mode("asha", asha, new WorkspaceController.Mode("PAPER", null));
        workspaces.limits("asha", asha, new WorkspaceController.Limits(5_000.0, null));
        jdbc.update("update exec.position set quantity_open = 0, status = 'CLOSED', closed_at = now(), realised_gross = -6000, "
                + "charges = 40 where broker_account_id = ?", zerodha);
        Map<String, Object> loss = (Map<String, Object>) workspaces.today("asha", asha).get("lossLimit");
        assertThat(loss).containsEntry("reached", true).containsEntry("liveNetToday", -6040.0);
        assertThat(view("asha", zerodha).today()).isEqualTo(new AccountsController.DayBook(-6040.0, 1, 0, -6040.0, 1, 0));
        assertThat(reason(() -> workspaces.mode("asha", asha, new WorkspaceController.Mode("LIVE", "LIVE"))))
                .contains("daily loss limit");
        assertThat(code(() -> workspaces.limits("chitra", chitra, new WorkspaceController.Limits(1.0, null)))).isEqualTo(403);
        assertThat(code(() -> workspaces.limits("asha", asha, new WorkspaceController.Limits(-1.0, null)))).isEqualTo(400);
    }

    private static AccountsController.AccountView view(String user, long account) {
        return api.accounts(user).stream().filter(a -> a.id() == account).findFirst().orElseThrow();
    }

    private static long liveIntent(long tenant, long account) {
        return intent(tenant, account, "LIVE");
    }

    private static long intent(long tenant, long account, String mode) {
        long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, source, idempotency_key, status) values "
                + "(?, 'MANUAL', ?, 'DONE') returning id", Long.class, tenant, "it-" + account + "-" + System.nanoTime());
        return jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, broker_type, "
                + "execution_mode, strategy_id, priority, underlying, action, option_side, state, idempotency_key) values "
                + "(?, ?, ?, 'ZERODHA', ?, 'ma-cross', 1, 'NIFTY', 'ENTER', 'CE', 'EXECUTED', ?) returning id", Long.class,
                tenant, batch, account, mode, "it-" + batch);
    }

    @Test
    void supportStopsTroubleAlwaysButRunsAWorkspaceOnlyWithTheMembersGrant() {
        long deepa = member("deepa", "OWNER");
        long account = account("deepa", deepa, "Kite", "ZERODHA", "EF1234");
        // without a grant: see, stop (account kill, PAPER), never run
        assertThat(code(() -> workspaces.today("satya", deepa))).isEqualTo(200);
        assertThat(code(() -> workspaces.broker("satya", deepa, new WorkspaceController.Broker(null)))).isEqualTo(403);
        assertThat(code(() -> admin.settings("satya", account, new AccountAdminController.Settings(200_000.0, null, null,
                null, null, null, null, null)))).isEqualTo(403);
        long kill = ((Number) api.engage("satya", new AccountsController.Engage("ACCOUNT", account, null, null, "trouble",
                null, null)).get("id")).longValue();
        assertThat(code(() -> api.release("satya", kill))).as("releasing is hers").isEqualTo(403);
        api.release("deepa", kill);
        assertThat(code(() -> workspaces.mode("satya", deepa, new WorkspaceController.Mode("PAPER", null)))).isEqualTo(200);
        assertThat(code(() -> workspaces.mode("satya", deepa, new WorkspaceController.Mode("LIVE", "LIVE")))).isEqualTo(403);

        // only the OWNER grants; then SUPPORT runs it; revoked: no more
        assertThat(code(() -> support.grant("satya", deepa, new SupportController.Grant(2)))).isEqualTo(403);
        assertThat(code(() -> support.grant("deepa", deepa, new SupportController.Grant(0)))).isEqualTo(400);
        assertThat(code(() -> support.grant("deepa", deepa, new SupportController.Grant(169)))).isEqualTo(400);
        support.grant("deepa", deepa, new SupportController.Grant(2));
        admin.settings("satya", account, new AccountAdminController.Settings(200_000.0, null, null, null, null, null, null,
                null));
        Long twin = jdbc.queryForObject("select id from acct.broker_account where paper_of_account_id = ?", Long.class, account);
        assertThat(jdbc.queryForObject("select allocated_capital::float8 from acct.broker_account where id = ?", Double.class,
                twin)).as("the twin follows").isEqualTo(200_000.0);
        assertThat(code(() -> admin.settings("satya", twin, new AccountAdminController.Settings(100_000.0, null, null, null,
                null, null, null, null)))).as("a twin is never changed directly").isEqualTo(400);
        assertThat(code(() -> admin.settings("satya", account, new AccountAdminController.Settings(null, null, null, null,
                "DRY_RUN", null, null, null)))).as("no mode of its own").isEqualTo(400);
        long grant = ((Number) support.grants("deepa", deepa).getFirst().get("id")).longValue();
        support.revoke("deepa", deepa, grant);
        assertThat(code(() -> workspaces.broker("satya", deepa, new WorkspaceController.Broker(null)))).isEqualTo(403);

        // break-glass: SUPPORT only, a real reason, one hour
        assertThat(code(() -> support.breakGlass("satya", deepa, new SupportController.BreakGlass("x")))).isEqualTo(400);
        assertThat(code(() -> support.breakGlass("deepa", deepa, new SupportController.BreakGlass("my own")))).isEqualTo(403);
        support.breakGlass("satya", deepa, new SupportController.BreakGlass("stuck exit at 15:05, member unreachable"));
        assertThat(code(() -> workspaces.broker("satya", deepa, new WorkspaceController.Broker(null)))).isEqualTo(200);
        assertThat(jdbc.queryForList("select action from exec.audit_log where tenant_id = ? order by id", String.class, deepa))
                .contains("SUPPORT_GRANTED", "SUPPORT_REVOKED", "SUPPORT_BREAK_GLASS");

        // the per-account LIVE switch is not for a daily workspace
        assertThat(reason(() -> liveSwitch.live("satya", account, new LiveSwitchController.Switch(true, "Kite"))))
                .contains("daily PAPER/LIVE switch");
        // the household view is the admin's; the member's own day view is hers
        assertThat(workspaces.household("satya")).extracting(c -> c.get("tenantId")).contains(deepa)
                .doesNotContain(ownerTenant);
        assertThat(code(() -> workspaces.household("deepa"))).isEqualTo(403);
    }

    @Test
    void membersAreFamilyOnlyAndTheAdminsOwnWorkspaceKeepsItsAccountMode() {
        assertThat(reason(() -> members.add("satya", new MembersController.NewMember("eve", null, "OWNER", null, "FRIEND",
                null, null)))).contains("family only");
        assertThat(reason(() -> members.add("satya", new MembersController.NewMember("eve", null, "OWNER", null, "SPOUSE",
                null, "CREATE")))).contains("not configured");
        assertThat(code(() -> members.add("satya", new MembersController.NewMember("eve", null, "OWNER", null, "SPOUSE",
                "not-an-email", null)))).isEqualTo(400);
        long eve = member("eve", "OWNER");
        assertThat(jdbc.queryForObject("select trading_control from acct.tenant where id = ?", String.class, eve))
                .isEqualTo("DAILY_SWITCH");
        assertThat(jdbc.queryForObject("select m.role from acct.tenant_membership m join acct.app_user u on u.id = "
                + "m.app_user_id where m.tenant_id = ? and u.external_subject = 'satya'", String.class, eve)).isEqualTo("SUPPORT");
        assertThat(jdbc.queryForObject("select status || ':' || delivery from acct.invite where username = 'eve'", String.class))
                .isEqualTo("PENDING:EXISTING_LOGIN");
        members.me("eve");                                                    // her first sign-in accepts the invite
        assertThat(jdbc.queryForObject("select status from acct.invite where username = 'eve'", String.class))
                .isEqualTo("ACCEPTED");
        // the admin's own workspace: no daily switch; the admin cannot be disabled or reset through a member's call
        assertThat(code(() -> workspaces.broker("satya", ownerTenant, new WorkspaceController.Broker(null)))).isEqualTo(409);
        assertThat(code(() -> members.resetAuthenticator("satya", "satya"))).isEqualTo(404);
        assertThat(code(() -> members.status("eve", "satya", new MembersController.Status("DISABLED")))).isEqualTo(403);
        assertThat(code(() -> members.checkTelegram("eve"))).as("no code waiting").isEqualTo(400);
        assertThat(code(() -> members.memberTelegram("satya", "eve", new MembersController.ChatId("not digits"))))
                .isEqualTo(400);
        members.memberTelegram("satya", "eve", new MembersController.ChatId("123456789"));
        assertThat(members.me("eve")).containsEntry("telegramLinked", true);
    }

    /** Code review 7 Nov 2026: LIVE is the OWNER's; disabling the OWNER never hands their workspace to SUPPORT. */
    @Test
    void onlyTheOwnerSwitchesLiveAndADisabledOwnerStillOwnsTheWorkspace() {
        long hina = member("hina", "OWNER");
        long account = account("hina", hina, "Kite", "ZERODHA", "KL1234");
        loggedIn(hina, account);
        workspaces.liveEligible("satya", account, new WorkspaceController.Eligible(true, "Kite"));
        AccountStore accounts = new AccountStore(dataSource);
        accounts.ensureMembership(hina, accounts.ensureUser("hina-trader", "t"), "TRADER");
        assertThat(code(() -> workspaces.mode("hina-trader", hina, new WorkspaceController.Mode("LIVE", "LIVE"))))
                .as("a TRADER does not switch real money LIVE").isEqualTo(403);
        assertThat(code(() -> workspaces.broker("hina-trader", hina, new WorkspaceController.Broker(account)))).isEqualTo(200);
        workspaces.mode("hina", hina, new WorkspaceController.Mode("LIVE", "LIVE"));
        // disabled: her LIVE day goes back to PAPER, and SUPPORT still needs a grant
        members.status("satya", "hina", new MembersController.Status("DISABLED"));
        assertThat(state("satya", hina)).containsEntry("mode", "PAPER");
        assertThat(code(() -> workspaces.mode("satya", hina, new WorkspaceController.Mode("LIVE", "LIVE")))).isEqualTo(403);
        assertThat(code(() -> admin.settings("satya", account, new AccountAdminController.Settings(100_000.0, null, null,
                null, null, null, null, null)))).isEqualTo(403);
        members.status("satya", "hina", new MembersController.Status("ACTIVE"));
    }

    /** Second review, 7 Nov 2026: LIVE eligibility was checked for a client id and an app; changing either removes it. */
    @Test
    void changingTheClientIdOrTheBrokerAppRemovesLiveEligibility() {
        long ira = member("ira", "OWNER");
        long account = account("ira", ira, "Kite", "ZERODHA", "MN1234");
        loggedIn(ira, account);
        workspaces.liveEligible("satya", account, new WorkspaceController.Eligible(true, "Kite"));
        workspaces.mode("ira", ira, new WorkspaceController.Mode("LIVE", "LIVE"));
        jdbc.update("update acct.broker_session set status = 'EXPIRED' where broker_account_id = ?", account);
        admin.settings("ira", account, new AccountAdminController.Settings(null, null, null, null, null, null, "OP1234",
                null));
        AccountStore accounts = new AccountStore(dataSource);
        assertThat(accounts.byId(account).orElseThrow().liveTradingEnabled()).isFalse();
        assertThat(state("ira", ira)).containsEntry("mode", "PAPER");
        // eligible again, then another app linked: removed again
        jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, status) values "
                + "(?, 'file:kite-x.token', now(), now() + interval '6 hours', 'ACTIVE')", account);
        workspaces.liveEligible("satya", account, new WorkspaceController.Eligible(true, "Kite"));
        jdbc.update("update acct.broker_session set status = 'EXPIRED' where broker_account_id = ?", account);
        long other = jdbc.queryForObject("insert into acct.broker_credential_profile (tenant_id, broker_type, scope, name, "
                + "api_key_ref, api_secret_ref) values (?, 'ZERODHA', 'USER_PRIVATE', 'second', 'store:a', 'store:b') "
                + "returning id", Long.class, ira);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        BrokerAppController apps = new BrokerAppController(new Access(dataSource), dataSource, tx,
                new KiteLoginProperties(null, null, null, null, null, "/nonexistent/bot.env"),
                new BrokerLoginProperties(null, null, null, null, null));
        apps.link("ira", account, new BrokerAppController.Link(other));
        assertThat(accounts.byId(account).orElseThrow().liveTradingEnabled()).isFalse();
        assertThat(jdbc.queryForList("select detail->>'reason' from exec.audit_log where broker_account_id = ? and action = "
                + "'LIVE_ELIGIBLE_OFF' order by id", String.class, account)).containsExactly("client id changed",
                "broker app changed");
    }

    @Test
    void twoWorkspacesMayNameTheirAccountsAlike() {
        long f1 = member("farah", "OWNER"), f2 = member("gita", "OWNER");
        long a1 = account("farah", f1, "Zerodha", "ZERODHA", "GH1234");
        long a2 = account("gita", f2, "Zerodha", "ZERODHA", "IJ1234");
        assertThat(a1).isNotEqualTo(a2);
        assertThat(jdbc.queryForList("select tenant_id from acct.broker_account where id in (?, ?) order by id", Long.class,
                a1, a2)).containsExactly(f1, f2);
        assertThat(jdbc.queryForObject("select external_broker_user_id from acct.broker_account where id = ?", String.class,
                a1)).as("the first one untouched").isEqualTo("GH1234");
    }
}
