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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.ExecutionLoop;
import com.autotrade.execution.FanOut;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Family members (29 Oct 2026, user: "family only"): the owner (ADMIN) adds a member, who gets a tenant of their own that
 * follows the owner's signal group by an explicit share. The member's accounts get their own batches and intents; they
 * never see or act on the owner's accounts, and a tenant without a share gets nothing even with a subscription.
 */
@Testcontainers(disabledWithoutDocker = true)
class FamilyMembersIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);    // a past day: subscriptions made now take it all
    private static final String RISK = "../config/risk/follower-risk.v3.yaml";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static AccountStore accounts;
    private static MembersController members;
    private static AccountAdminController admin;
    private static AccountsController api;
    private static StatusController status;
    private static long ownerTenant, group, ownerAccount, strangerAccount;
    private static List<ExecutionLoop.AccountStatus> turns = List.of();

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
        accounts = new AccountStore(dataSource);
        ownerTenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("satya", "satya");
        accounts.ensureMembership(ownerTenant, owner, "ADMIN");
        long signal = accounts.ensureSignalSourceAccount(ownerTenant, owner, "SIGNAL-1", 500_000, DAY,
                "../config/risk/signal-source.v2.yaml");
        group = accounts.ensureCopyGroup(ownerTenant, "LIVE-SET", signal);
        accounts.ensureCopyGroup(ownerTenant, "CHAOS", signal);                 // a drill group: never shared
        ownerAccount = accounts.ensureFollowerAccount(ownerTenant, owner, "ZERODHA-1", BrokerType.PAPER, TradeMode.PAPER,
                200_000, EquityMode.FIXED, DAY, RISK, 100);
        new FanOut(dataSource).subscribe(group, ownerAccount, List.of("oi-burst-cross", "owner-only"), 1.0);
        // a tenant nobody shared the group with, subscribed anyway (by hand): must get nothing
        long strangerTenant = accounts.ensureTenant("stranger");
        long stranger = accounts.ensureUser("stranger", "stranger");
        accounts.ensureMembership(strangerTenant, stranger, "OWNER");
        strangerAccount = accounts.ensureFollowerAccount(strangerTenant, stranger, "STRANGER-1", BrokerType.PAPER,
                TradeMode.PAPER, 500_000, EquityMode.FIXED, DAY, RISK, 100);
        new FanOut(dataSource).subscribe(group, strangerAccount, List.of("oi-burst-cross"), 1.0);

        Access access = new Access(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ExecutionProperties properties = new ExecutionProperties(null, "*", 1000, null, 0, "it", null);
        ExecutionService service = new ExecutionService(dataSource, properties) {
            @Override
            List<ExecutionLoop.AccountStatus> status() {
                return turns;
            }
        };
        members = new MembersController(access, dataSource, tx);
        admin = new AccountAdminController(access, dataSource, tx, properties);
        api = new AccountsController(access, dataSource, service);
        status = new StatusController(service, properties, access);
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

    private static void decide(String strategy, String key) {
        decide(strategy, key, "ENTER", Instant.parse("2026-10-19T04:40:00Z"));
    }

    private static void decide(String strategy, String key, String action, Instant at) {
        jdbc.update("insert into exec.signal_decision (copy_group_id, signal_session_id, session_date, decided_at, strategy_id, "
                + "priority, underlying, action, option_side, reason, instrument_token, symbol, lot_size, reference_price, "
                + "premium_budget, reference_capital, lots, idempotency_key) values (?, 1, ?, ?, ?, 0, 'NIFTY', ?, 'CE', "
                + "'OBC_UP', 1, 'NIFTY 22800 CE', 65, 159.3, 250000, 500000, 1, ?)", group,
                java.sql.Date.valueOf(at.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate()), java.sql.Timestamp.from(at),
                strategy, action, key);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFamilyMemberFollowsTheOwnersSignalsInTheirOwnTenantAndSeesOnlyTheirs() {
        // only an ADMIN adds members
        assertThat(code(() -> members.add("stranger", new MembersController.NewMember("eve", null, "TRADER", null))))
                .isEqualTo(403);
        Map<String, Object> added = members.add("satya", new MembersController.NewMember("mum", "Mum", "TRADER", null));
        long mumTenant = (Long) added.get("tenantId");
        assertThat(added).containsEntry("follows", List.of(group));
        assertThat(code(() -> members.add("satya", new MembersController.NewMember("mum", null, "TRADER", null))))
                .as("a second time").isEqualTo(400);

        // who she is: her tenant only, not an admin; the owner administers both
        Map<String, Object> me = members.me("mum");
        assertThat(me).containsEntry("admin", false);
        assertThat((List<?>) me.get("tenants")).hasSize(1);
        assertThat(((List<Map<String, Object>>) me.get("tenants")).getFirst()).containsEntry("follows", List.of("LIVE-SET"))
                .containsEntry("role", "TRADER").containsEntry("mayAct", true).containsEntry("manages", false);
        assertThat(code(() -> members.members("mum"))).isEqualTo(403);
        assertThat(members.members("satya")).extracting(t -> t.get("tenantId")).contains(ownerTenant, mumTenant);

        // a TRADER does not create accounts; the owner creates hers, and it follows the shared group
        assertThat(code(() -> admin.create("mum", account("MUM-1", null)))).isEqualTo(403);
        long mumAccount = ((Number) admin.create("satya", account("MUM-1", mumTenant)).get("id")).longValue();
        assertThat(jdbc.queryForObject("select copy_group_id from acct.strategy_subscription where broker_account_id = ?",
                Long.class, mumAccount)).isEqualTo(group);
        assertThat(jdbc.queryForObject("select u.external_subject from acct.broker_account a join acct.app_user u "
                + "on u.id = a.app_user_id where a.id = ?", String.class, mumAccount)).as("her account is hers").isEqualTo("mum");
        long dadTenant = (Long) members.add("satya", new MembersController.NewMember("dad", null, "VIEWER", null)).get("tenantId");
        long dadAccount = ((Number) admin.create("satya", account("DAD-1", dadTenant)).get("id")).longValue();
        assertThat(jdbc.queryForObject("select u.external_subject from acct.broker_account a join acct.app_user u "
                + "on u.id = a.app_user_id where a.id = ?", String.class, dadAccount)).as("a VIEWER's account is his too")
                .isEqualTo("dad");
        long ownAccount = ((Number) admin.create("satya", account("OWN-2", ownerTenant)).get("id")).longValue();
        assertThat(jdbc.queryForObject("select u.external_subject from acct.broker_account a join acct.app_user u "
                + "on u.id = a.app_user_id where a.id = ?", String.class, ownAccount)).as("the owner's own").isEqualTo("satya");

        // she sees her account only; the owner's is not found, whatever the call
        assertThat(api.accounts("mum")).extracting(AccountsController.AccountView::name).containsExactly("MUM-1");
        assertThat(code(() -> admin.details("mum", ownerAccount))).isEqualTo(404);
        assertThat(code(() -> admin.positions("mum", ownerAccount, null))).isEqualTo(404);
        assertThat(code(() -> api.audit("mum", ownerAccount))).isEqualTo(404);
        assertThat(code(() -> api.engage("mum", new AccountsController.Engage("ACCOUNT", ownerAccount, null, null, "x",
                null, null)))).isEqualTo(404);
        assertThat(code(() -> api.engage("mum", new AccountsController.Engage("STRATEGY", null, "oi-burst-cross", null, "x",
                ownerTenant, null)))).isEqualTo(403);
        assertThat(api.audit("mum", null)).extracting(r -> r.get("action")).contains("MEMBER_ADDED", "ACCOUNT_CREATED")
                .doesNotContain("KILL_STRATEGY");

        // strategies she can take: what the shared group publishes; never the owner's own subscriptions
        decide("oi-burst-cross", "d1");
        assertThat(admin.strategyIds("mum")).contains("oi-burst-cross").doesNotContain("owner-only");

        // the fan-out: one batch per entitled tenant, one intent per subscribed account; the stranger gets nothing
        new FanOut(dataSource).fanOut(group, DAY);
        assertThat(jdbc.queryForList("select tenant_id from exec.trade_batch where idempotency_key = 'SIG:d1' order by tenant_id",
                Long.class))
                .as("one batch per entitled tenant: the owner's, mum's and dad's").containsExactly(ownerTenant, mumTenant, dadTenant);
        assertThat(jdbc.queryForList("select broker_account_id from exec.trade_intent where idempotency_key like 'd1:%' "
                + "order by broker_account_id", Long.class))
                .containsExactlyInAnyOrder(ownerAccount, mumAccount, dadAccount, ownAccount);
        assertThat(jdbc.queryForObject("select tenant_id from exec.trade_intent where broker_account_id = ? "
                + "and idempotency_key like 'd1:%'", Long.class, mumAccount)).isEqualTo(mumTenant);

        // the worker status through the UI: her account's turn only; on the host (no header): every account
        turns = List.of(turn(ownerAccount, ownerTenant, "ZERODHA-1"), turn(mumAccount, mumTenant, "MUM-1"));
        assertThat((List<?>) status.status("mum").get("accounts")).hasSize(1);
        assertThat((List<?>) status.status("satya").get("accounts")).hasSize(2);
        assertThat((List<?>) status.status(null).get("accounts")).hasSize(2);

        // the owner acts for two tenants: a strategy switch must say whose
        assertThat(code(() -> api.engage("satya", new AccountsController.Engage("STRATEGY", null, "oi-burst-cross", null,
                "x", null, null)))).isEqualTo(400);
        assertThat(code(() -> api.engage("satya", new AccountsController.Engage("STRATEGY", null, "oi-burst-cross", null,
                "drill", mumTenant, null)))).isEqualTo(200);
        assertThat((List<?>) api.engage("satya", new AccountsController.Engage("STRATEGY", null, "owner-only", null, "drill",
                null, true)).get("ids")).as("one switch per tenant the owner acts for: own, mum, dad").hasSize(3);
        assertThat(code(() -> api.engage("mum", new AccountsController.Engage("STRATEGY", null, "x", null, "drill", null,
                true)))).as("a TRADER acts for her own tenant only").isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from exec.kill_switch where strategy_id = 'x'", Integer.class))
                .isEqualTo(1);

        // disabled: every call refused at once
        assertThat(code(() -> members.status("mum", "satya", new MembersController.Status("DISABLED")))).isEqualTo(403);
        assertThat(code(() -> members.status("satya", "satya", new MembersController.Status("DISABLED")))).isEqualTo(400);
        assertThat(code(() -> members.status("satya", "stranger", new MembersController.Status("DISABLED")))).isEqualTo(404);
        accounts.ensureMembership(mumTenant, accounts.ensureUser("signal-owner", "s"), "VIEWER");
        jdbc.update("update acct.broker_account set app_user_id = (select id from acct.app_user where external_subject = "
                + "'signal-owner') where display_name = 'SIGNAL-1'");
        assertThat(code(() -> members.status("satya", "signal-owner", new MembersController.Status("DISABLED"))))
                .as("the signal source's owner").isEqualTo(400);
        members.status("satya", "mum", new MembersController.Status("DISABLED"));
        assertThat(code(() -> api.accounts("mum"))).isEqualTo(403);
        members.status("satya", "mum", new MembersController.Status("ACTIVE"));
        assertThat(api.accounts("mum")).hasSize(1);
    }

    /**
     * Code review 29 Oct 2026 ("demo", created 10:42, booked the 09:43 entry at its old price): a subscription enabled
     * during the session takes no earlier ENTER or ADD of that day; an exit still reaches it; later entries do.
     */
    @Test
    void aSubscriptionEnabledMidSessionTakesNoEarlierEntry() {
        long tenant = accounts.ensureTenant("late");
        long late = accounts.ensureUser("late", "late");
        accounts.ensureMembership(tenant, late, "OWNER");
        long account = accounts.ensureFollowerAccount(tenant, late, "LATE-1", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, DAY, RISK, 100);
        accounts.shareCopyGroup(group, tenant, late);
        new FanOut(dataSource).subscribe(group, account, List.of("late-test"), 1.0);
        LocalDate day = LocalDate.of(2026, 10, 20);
        jdbc.update("update acct.strategy_subscription set enabled_at = ? where broker_account_id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-10-20T05:12:00Z")), account);      // 10:42 IST
        decide("late-test", "late-enter-0943", "ENTER", Instant.parse("2026-10-20T04:13:00Z"));
        decide("late-test", "late-exit-1249", "EXIT", Instant.parse("2026-10-20T07:19:00Z"));
        decide("late-test", "late-enter-1336", "ENTER", Instant.parse("2026-10-20T08:06:00Z"));
        new FanOut(dataSource).fanOut(group, day);
        assertThat(jdbc.queryForList("select idempotency_key from exec.trade_intent where broker_account_id = ? order by id",
                String.class, account)).containsExactly("late-exit-1249:" + account, "late-enter-1336:" + account);

        // switched off and on again during the day: from that moment, not the entries it missed
        jdbc.update("update acct.strategy_subscription set enabled = false where broker_account_id = ?", account);
        new FanOut(dataSource).subscribe(group, account, List.of("late-test"), 1.0);
        assertThat(jdbc.queryForObject("select enabled_at > '2026-10-21' from acct.strategy_subscription "
                + "where broker_account_id = ?", Boolean.class, account)).isTrue();
    }

    /** 29 Oct 2026, user: "its own ledger": opening equity compounds with the net since the equity date; others: 404. */
    @Test
    void anAccountsLedgerCompoundsDayByDayAndIsTheirsAlone() {
        long tenant = accounts.ensureTenant("ledger");
        long led = accounts.ensureUser("led", "led");
        accounts.ensureMembership(tenant, led, "OWNER");
        long account = accounts.ensureFollowerAccount(tenant, led, "LED-1", BrokerType.PAPER, TradeMode.PAPER, 100_000,
                EquityMode.COMPOUNDING, LocalDate.of(2026, 10, 21), RISK, 100);
        accounts.shareCopyGroup(group, tenant, led);
        new FanOut(dataSource).subscribe(group, account, List.of("ledger-test"), 1.0);
        decide("ledger-test", "led-1", "ENTER", Instant.parse("2026-10-21T04:00:00Z"));
        new FanOut(dataSource).fanOut(group, LocalDate.of(2026, 10, 21));
        long intent = jdbc.queryForObject("select id from exec.trade_intent where idempotency_key = ?", Long.class,
                "led-1:" + account);
        for (Object[] p : new Object[][] {{"2026-10-21T05:00:00Z", 10_000.0, 200.0}, {"2026-10-22T05:00:00Z", -3_000.0, 100.0}}) {
            jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                    + "option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, closed_at, realised_gross, "
                    + "charges, status) values (?, ?, ?, 'ledger-test', 'NIFTY', 'CE', 1, 'NIFTY 22800 CE', 0, 100, ?, ?, ?, ?, "
                    + "'CLOSED')", tenant, account, intent, java.sql.Timestamp.from(Instant.parse((String) p[0])),
                    java.sql.Timestamp.from(Instant.parse((String) p[0]).plusSeconds(3600)), p[1], p[2]);
        }
        List<Map<String, Object>> ledger = admin.ledger("led", account, 60);
        assertThat(ledger).extracting(r -> r.get("day")).containsExactly("2026-10-22", "2026-10-21");
        assertThat(ledger.get(1)).containsEntry("openingEquity", 100_000.0).containsEntry("net", 9_800.0)
                .containsEntry("closingEquity", 109_800.0).containsEntry("trades", 1).containsEntry("wins", 1);
        assertThat(ledger.get(0)).containsEntry("openingEquity", 109_800.0).containsEntry("net", -3_100.0)
                .containsEntry("closingEquity", 106_700.0).containsEntry("wins", 0);
        assertThat(code(() -> admin.ledger("mum", account, 60))).isEqualTo(404);
    }

    private static AccountAdminController.NewAccount account(String name, Long tenant) {
        return new AccountAdminController.NewAccount(name, "PAPER", "PAPER", null, null, 100_000.0, "FIXED", null, null,
                List.of("oi-burst-cross"), true, tenant);
    }

    private static ExecutionLoop.AccountStatus turn(long account, long tenant, String name) {
        return new ExecutionLoop.AccountStatus(account, tenant, name, "PAPER", "PAPER", "simulated fills", 1L, "RUNNING",
                Instant.now(), "nothing new");
    }
}
