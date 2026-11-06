package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The live book of a LIVE follower: an open position priced at its newest captured bid with its best bid since entry and
 * the resting stop's risk, a closed one with the exit implied by its realised P&amp;L, and nothing from a DRY_RUN intent of
 * the same account; another tenant's user gets a 404.
 */
@Testcontainers(disabledWithoutDocker = true)
class LiveBookIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final long TOKEN = 3_000_040_009L;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

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
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static long intent(long group, long session, long account, int n, String mode) {
        new SignalDecisionStore(dataSource).insert(new SignalDecision(group, session, LocalDate.now(), Instant.now(),
                "oi-burst-cross", 2, "NIFTY", SignalDecision.Action.ENTER, "CE", null, "CONFIRMED", "IT", TOKEN,
                "NSE_FO|40009", "NIFTY 24650 CE 09 NOV 26", 65, 146, 9_500, 500_000, 0, group + ":" + session + ":" + n,
                Map.of()));
        new FanOut(dataSource).fanOut(group, LocalDate.now());
        long id = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ? order by id desc limit 1",
                Long.class, account);
        jdbc.update("update exec.trade_intent set execution_mode = ? where id = ?", mode, id);
        return id;
    }

    private static long position(long tenant, long account, long intent, String status, double entry, String opened,
                                 String closed, Double gross, Double charges) {
        long qty = "CLOSED".equals(status) ? 0 : 65;
        return jdbc.queryForObject("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, "
                + "underlying, option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, closed_at, "
                + "exit_reason, realised_gross, charges, status) values (?, ?, ?, 'oi-burst-cross', 'NIFTY', 'CE', ?, "
                + "'NIFTY 24650 CE 09 NOV 26', ?, ?, now() - ?::interval, now() - ?::interval, ?, ?, ?, ?) returning id", Long.class,
                tenant, account, intent, TOKEN, qty, entry, opened, closed, closed == null ? null : "STOP", gross, charges, status);
    }

    private static void order(long tenant, long account, long intent, String role, String status, long filled, Double trigger) {
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                + "client_tag, attempt_no, order_side, order_type, quantity_requested, quantity_filled, trigger_price, status) "
                + "values (?, ?, ?, 'ZERODHA', ?, ?, 1, ?, ?, 65, ?, ?, ?)", tenant, intent, account, role,
                "T" + intent + role, role.equals("ENTRY") ? "BUY" : "SELL", role.equals("STOP") ? "STOP_LIMIT" : "LIMIT",
                filled, trigger, status);
    }

    private static void tick(String ago, double bid) {
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, symbol, "
                + "strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, analytics_complete, "
                + "depth_complete) values (1, current_date, 'NIFTY', now() - ?::interval, 1, ?, 'NIFTY 24650 CE 09 NOV 26', 24650, "
                + "'CE', ?, array[?]::float8[], '{650}', '{3}', array[?]::float8[], '{650}', '{3}', false, true)",
                ago, TOKEN, bid + 0.05, bid, bid + 0.05);
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsOnlyTheAccountsLivePositionsWithQuoteStopAndExit() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("owner");
        long owner = accounts.ensureUser("local-owner", "me");
        accounts.ensureMembership(tenant, owner, "OWNER");
        long account = accounts.ensureFollowerAccount(tenant, owner, "ZERODHA-1", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                200_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v7.yaml", 0);
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-1", 500_000, LocalDate.now(),
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "G-1", signal);
        new FanOut(dataSource).subscribe(group, account, List.of("oi-burst-cross"), 1.0);
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('SIG-1', 'PAPER_LIVE', current_date, 'it', 'live-set', "
                + "'sha256:it', '{}'::jsonb, 'it', 'DONE') returning id", Long.class);

        long live = intent(group, session, account, 1, "LIVE");
        position(tenant, account, live, "OPEN", 146.35, "5 minutes", null, null, null);
        order(tenant, account, live, "ENTRY", "FILLED", 65, null);
        order(tenant, account, live, "STOP", "OPEN", 0, 73.15);
        tick("3 minutes", 163.80);                         // the best since entry
        tick("30 seconds", 163.70);                        // the newest

        long done = intent(group, session, account, 2, "LIVE");
        position(tenant, account, done, "CLOSED", 185.00, "2 days 01:00:00", "2 days", -2574.0, 89.51);
        order(tenant, account, done, "ENTRY", "FILLED", 65, null);
        long add = intent(group, session, account, 4, "LIVE");             // an add to it 30 minutes in: 130 bought in all
        jdbc.update("update exec.trade_intent set action = 'ADD', created_at = now() - interval '2 days 00:30:00' where id = ?", add);
        order(tenant, account, add, "ENTRY", "FILLED", 65, null);
        jdbc.update("insert into exec.kill_switch (scope, reason, engaged_by) values ('GLOBAL_LIVE', 'ops', 'it')");

        long rehearsal = intent(group, session, account, 3, "DRY_RUN");
        position(tenant, account, rehearsal, "CLOSED", 100.00, "3 days", "3 days", 650.0, 40.0);
        order(tenant, account, rehearsal, "ENTRY", "FILLED", 65, null);

        jdbc.update("update acct.broker_account set trade_mode = 'LIVE' where id = ?", account);   // after the fan-outs
        LiveBookController api = new LiveBookController(new Access(dataSource), dataSource);
        Map<String, Object> book = api.liveBook("local-owner", account, 7);

        assertThat(book).containsEntry("account", "ZERODHA-1").containsEntry("mode", "LIVE")
                .containsEntry("killSwitches", List.of("all LIVE accounts: ops"));
        List<Map<String, Object>> open = (List<Map<String, Object>>) book.get("open");
        assertThat(open).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("quantity", 65L).containsEntry("bid", 163.70).containsEntry("best", 163.80)
                    .containsEntry("stop", 73.15).containsEntry("stopWorking", true).containsEntry("lotSize", 65);
            assertThat((Double) p.get("pnl")).isEqualTo(1127.75);
            assertThat((Double) p.get("pnlPct")).isEqualTo(11.86);
            assertThat((Double) p.get("atRisk")).isEqualTo(5885.75);
        });
        List<Map<String, Object>> closed = (List<Map<String, Object>>) book.get("closed");
        assertThat(closed).as("the DRY_RUN rehearsal is not real money").singleElement().satisfies(p -> {
            assertThat(p).containsEntry("quantity", 130L).containsEntry("exit", 165.2).containsEntry("exitReason", "STOP")
                    .containsEntry("net", -2663.51).containsEntry("pct", -10.7);
        });
        Map<String, Object> summary = (Map<String, Object>) book.get("summary");
        assertThat(summary).containsEntry("openPnl", 1127.75).containsEntry("atRisk", 5885.75)
                .containsEntry("realisedToday", 0.0).containsEntry("sinceLiveNet", -2663.51).containsEntry("sinceLiveTrades", 1);

        // a stop being cancelled no longer protects the position: not working, nothing counted at risk
        jdbc.update("update exec.order_execution set status = 'CANCEL_PENDING' where role = 'STOP' and trade_intent_id = ?", live);
        Map<String, Object> cancelling = ((List<Map<String, Object>>) api.liveBook("local-owner", account, 7).get("open")).getFirst();
        assertThat(cancelling).containsEntry("stopWorking", false).doesNotContainKey("atRisk");

        long other = accounts.ensureTenant("other");
        accounts.ensureMembership(other, accounts.ensureUser("kc-other", "other"), "OWNER");
        assertThatThrownBy(() -> api.liveBook("kc-other", account, 7)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
