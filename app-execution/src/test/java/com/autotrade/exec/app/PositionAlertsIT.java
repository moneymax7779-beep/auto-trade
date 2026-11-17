package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Position alerts (17 Nov 2026): BUY, each profit / loss level once (the highest crossed) with Book buttons for those
 * who may act, SELL and SOLD; every linked member chat and the admin chat once each; a restart replays nothing; the
 * owner's own paper accounts never alert.
 */
@Testcontainers(disabledWithoutDocker = true)
class PositionAlertsIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final long TOKEN = 3_000_045_394L;
    private static final String KEY = "NSE_FO|45394";

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static long tenant, twin, ownPaper, group, seq;

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
        tenant = accounts.ensureTenant("family-alerts");
        long owner = accounts.ensureUser("kc-ravi", "ravi"), viewer = accounts.ensureUser("kc-view", "view");
        accounts.ensureMembership(tenant, owner, "OWNER");
        accounts.ensureMembership(tenant, viewer, "VIEWER");
        jdbc.update("update acct.app_user set telegram_chat_id = '111' where id = ?", owner);
        jdbc.update("update acct.app_user set telegram_chat_id = '222' where id = ?", viewer);
        long real = accounts.createFollowerAccount(tenant, owner, "Ravi Kotak", BrokerType.KOTAK_NEO, TradeMode.PAPER, 50_000,
                EquityMode.FIXED, LocalDate.now(), "../config/risk/follower-risk.v7.yaml", 0);
        twin = accounts.ensurePaperTwin(real);
        ownPaper = accounts.createFollowerAccount(tenant, owner, "Own paper", BrokerType.PAPER, TradeMode.PAPER, 50_000,
                EquityMode.FIXED, LocalDate.now(), "../config/risk/follower-risk.v7.yaml", 0);
        long signal = accounts.ensureSignalSourceAccount(tenant, owner, "SIG-alerts", 500_000, LocalDate.now(),
                "config/risk/signal-source.v2.yaml");
        group = accounts.ensureCopyGroup(tenant, "SET-alerts", signal);
        jdbc.update("insert into ref.instrument_snapshot (id, snapshot_date, source, file_sha256, instruments) values "
                + "(902, current_date, 'it', 'it', 1) on conflict do nothing");
        jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values (902, ?, "
                + "'45394', 'NSE_FO', 'NSE', 'CE', 'NIFTY', 'NIFTY 25350 CE', date '2026-11-17', 25350, 65, 0.05, 1800, true)",
                KEY);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static long position(long account, long qty, double entry) {
        seq++;
        long decision = jdbc.queryForObject("insert into exec.signal_decision (copy_group_id, signal_session_id, session_date, "
                + "decided_at, strategy_id, priority, underlying, action, option_side, reason, instrument_token, symbol, "
                + "lot_size, reference_price, premium_budget, reference_capital, lots, idempotency_key) values (?, 1, "
                + "current_date, now(), 'oi-burst-cross', 0, 'NIFTY', 'ENTER', 'CE', 'IT', ?, 'NIFTY 25350 CE 17 NOV 26', 65, "
                + "?, 10000, 500000, 2, ?) returning id", Long.class, group, TOKEN, entry, "pa:" + seq);
        long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, copy_group_id, signal_decision_id, source, "
                + "idempotency_key, status) values (?, ?, ?, 'SIGNAL', ?, 'DONE') returning id", Long.class, tenant, group,
                decision, "SIG:pa:" + seq);
        long intent = jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, state, "
                + "idempotency_key) values (?, ?, ?, 'PAPER', 'PAPER', 'oi-burst-cross', 1, 'NIFTY', 'ENTER', 'CE', "
                + "'EXECUTED', ?) returning id", Long.class, tenant, batch, account, "pa:" + seq + ":" + account);
        return jdbc.queryForObject("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, "
                + "strategy_id, underlying, option_side, instrument_token, instrument_key, symbol, quantity_open, "
                + "average_entry, opened_at, charges, status) values (?, ?, ?, 'oi-burst-cross', 'NIFTY', 'CE', ?, ?, "
                + "'NIFTY 25350 CE 17 NOV 26', ?, ?, now(), 20, 'OPEN') returning id", Long.class, tenant, account, intent,
                TOKEN, KEY, qty, entry);
    }

    private static void bid(double bid) {
        seq++;
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, "
                + "analytics_complete, depth_complete) values (1, current_date, 'NIFTY', now(), ?, ?, 'NIFTY 25350 CE', "
                + "date '2026-11-17', 25350, 'CE', ?, array[?]::float8[], '{650}', '{3}', array[?]::float8[], '{650}', "
                + "'{3}', false, true)", seq, TOKEN, bid, bid, bid + 0.05);
    }

    private static List<String> texts(List<PositionAlerts.Message> m) {
        return m.stream().map(x -> x.chat() + ":" + x.text()).toList();
    }

    @Test
    void eachAlertAlsoGoesToTheWorkspacesPhonesOpeningPositions() {
        List<String> pushed = new java.util.concurrent.CopyOnWriteArrayList<>();
        WebPushSender recorder = new WebPushSender(dataSource, new PushProperties(true, "mailto:x@example.invalid"),
                "https://app.example", java.net.http.HttpClient.newHttpClient()) {
            @Override
            void toMembers(long tenantId, Message m) {
                pushed.add("members " + tenantId + " " + m.url() + " " + m.body());
            }

            @Override
            void toAdmins(Message m) {
                pushed.add("admins " + m.url() + " " + m.body());
            }
        };
        PositionAlerts alerts = new PositionAlerts(dataSource, new Access(dataSource), null, null);
        alerts.webPush(recorder);
        alerts.collect("999", true);
        long p = position(twin, 130, 100);
        alerts.collect("999", true);
        assertThat(pushed).hasSize(2);
        assertThat(pushed.get(0)).startsWith("members ").contains(" /positions ").contains("#" + p + " BUY NIFTY 25350 CE");
        assertThat(pushed.get(1)).startsWith("admins /positions ");
    }

    @Test
    void buyLevelsOnceWithBookButtonsSellAndSoldToEachChatOnce() {
        PositionAlerts alerts = new PositionAlerts(dataSource, new Access(dataSource), null, null);
        assertThat(alerts.collect("999", true)).isEmpty();                 // the first look: nothing to replay

        long p = position(twin, 130, 100);
        long mine = position(ownPaper, 65, 100);                           // the owner's own paper: never
        List<PositionAlerts.Message> buy = alerts.collect("999", true);
        assertThat(buy).extracting(PositionAlerts.Message::chat).containsExactly("111", "222", "999");
        assertThat(buy.getFirst().text()).contains("Ravi Kotak paper (PAPER) #" + p + " BUY NIFTY 25350 CE 17 NOV 26: "
                + "2 lot(s), 130 qty at 100.00 (oi-burst-cross)");
        assertThat(texts(buy)).noneMatch(t -> t.contains("#" + mine));

        bid(125);                                                          // +25 %: the +20 % level (and +10 % with it)
        List<PositionAlerts.Message> up = alerts.collect("999", true);
        assertThat(up).hasSize(3).allSatisfy(m -> assertThat(m.text()).contains("+20 % now (+25.0 %)")
                .contains("Book partial or full?"));
        assertThat(up.get(0).buttons()).as("the owner acts").isNotNull();
        assertThat(up.get(0).buttons().getFirst()).extracting(TelegramBot.Button::data)
                .containsExactly("b:" + p + ":1", "b:" + p + ":0");
        assertThat(up.get(1).buttons()).as("a VIEWER only reads").isNull();
        assertThat(alerts.collect("999", true)).as("once per level").isEmpty();

        bid(160);
        assertThat(alerts.collect(null, true)).hasSize(2).allSatisfy(m -> assertThat(m.text()).contains("+50 % now"));
        bid(85);
        assertThat(alerts.collect(null, false)).hasSize(2).allSatisfy(m -> {
            assertThat(m.text()).contains("-10 % now (-15.0 %)");
            assertThat(m.buttons()).as("commands off: no buttons").isNull();
        });

        jdbc.update("update exec.position set quantity_open = 65, realised_gross = -975 where id = ?", p);
        assertThat(texts(alerts.collect(null, true))).hasSize(2).allMatch(t -> t.contains("SELL NIFTY 25350 CE 17 NOV 26: "
                + "1 lot(s), 65 qty sold, 65 qty still open"));
        jdbc.update("update exec.position set quantity_open = 0, realised_gross = -1950, status = 'CLOSED', closed_at = now(), "
                + "exit_reason = 'TRADER_EXIT' where id = ?", p);
        assertThat(texts(alerts.collect(null, true))).hasSize(2).allMatch(t -> t.contains("SOLD all NIFTY 25350 CE 17 NOV 26: "
                + "exited by you, net -Rs 1,970"));
        assertThat(alerts.collect(null, true)).isEmpty();

        // a restart: an open position already at +60 % is not said again
        long again = position(twin, 65, 100);
        bid(160);
        PositionAlerts restarted = new PositionAlerts(dataSource, new Access(dataSource), null, null);
        assertThat(restarted.collect("999", true)).isEmpty();
        assertThat(restarted.collect("999", true)).isEmpty();
        jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now() where id in (?, ?)",
                again, mine);
    }
}
