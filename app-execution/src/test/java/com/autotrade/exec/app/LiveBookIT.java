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
                + "underlying, option_side, instrument_token, instrument_key, symbol, quantity_open, average_entry, opened_at, "
                + "closed_at, exit_reason, realised_gross, charges, status) values (?, ?, ?, 'oi-burst-cross', 'NIFTY', 'CE', ?, "
                + "'NSE_FO|40009', "
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
                + "expiry, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, analytics_complete, "
                + "depth_complete) values (1, current_date, 'NIFTY', now() - ?::interval, 1, ?, 'NIFTY 24650 CE 09 NOV 26', "
                + "date '2026-11-09', 24650, 'CE', ?, array[?]::float8[], '{650}', '{3}', array[?]::float8[], '{650}', '{3}', "
                + "false, true)",
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
        // the test's clock (decided_at) and the database's (enabled_at) differ by up to a second in Docker: a
        // subscription made a moment before its decision must not count as made after it (full-suite flake, 8 Nov 2026)
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?", group);
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('SIG-1', 'PAPER_LIVE', current_date, 'it', 'live-set', "
                + "'sha256:it', '{}'::jsonb, 'it', 'DONE') returning id", Long.class);

        long live = intent(group, session, account, 1, "LIVE");
        position(tenant, account, live, "OPEN", 146.35, "5 minutes", null, null, null);
        order(tenant, account, live, "ENTRY", "FILLED", 65, null);
        order(tenant, account, live, "STOP", "OPEN", 0, 73.15);
        tick("3 minutes", 163.80);                         // the best since entry
        tick("30 seconds", 163.70);                        // the newest
        jdbc.update("update md.option_tick set delta = 0.45 where instrument_token = ? and recv_ts > now() - interval '40 "
                + "seconds'", TOKEN);                                // phase 3: rupees per index point = delta x quantity

        long done = intent(group, session, account, 2, "LIVE");
        position(tenant, account, done, "CLOSED", 185.00, "2 days 01:00:00", "2 days", -2574.0, 89.51);
        // the contract (best and worst while held are read through the contract index, not the token)
        jdbc.update("insert into ref.instrument_snapshot (id, snapshot_date, source, file_sha256, instruments) values "
                + "(901, current_date, 'it', 'it', 1) on conflict do nothing");
        jdbc.update("insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, type, "
                + "underlying, trading_symbol, expiry, strike, lot_size, tick_size, freeze_quantity, weekly) values (901, "
                + "'NSE_FO|40009', '40009', 'NSE_FO', 'NSE', 'CE', 'NIFTY', 'NIFTY 24650 CE', date '2026-11-09', 24650, 65, "
                + "0.05, 1755, true) on conflict do nothing");
        tick("2 days 00:40:00", 190.00);                   // while it was held: the best bid
        tick("2 days 00:20:00", 160.00);                   // and the worst
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

        assertThat(book).containsEntry("account", "ZERODHA-1").containsEntry("mode", "LIVE").containsEntry("canAct", true);
        assertThat((List<Map<String, Object>>) book.get("killSwitches")).singleElement().satisfies(k -> assertThat(k)
                .containsEntry("text", "all LIVE accounts: ops").containsEntry("releasable", false));   // GLOBAL_LIVE: ADMIN only
        List<Map<String, Object>> open = (List<Map<String, Object>>) book.get("open");
        assertThat(open).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("quantity", 65L).containsEntry("bid", 163.70).containsEntry("best", 163.80)
                    .containsEntry("stop", 73.15).containsEntry("stopWorking", true).containsEntry("lotSize", 65);
            assertThat((Double) p.get("pnl")).isEqualTo(1127.75);
            assertThat((Double) p.get("pnlPct")).isEqualTo(11.86);
            assertThat((Double) p.get("atRisk")).isEqualTo(5885.75);
            assertThat(p).containsEntry("perPoint", 29.25);
        });
        List<Map<String, Object>> closed = (List<Map<String, Object>>) book.get("closed");
        assertThat(closed).as("the DRY_RUN rehearsal is not real money").singleElement().satisfies(p -> {
            assertThat(p).containsEntry("quantity", 130L).containsEntry("exit", 165.2).containsEntry("exitReason", "STOP")
                    .containsEntry("net", -2663.51).containsEntry("pct", -10.7);
            assertThat(p).containsEntry("bestBid", 190.0).containsEntry("worstBid", 160.0).containsEntry("missed", 3224.0)
                    .containsEntry("saved", 676.0);                    // (190 - 165.2) x 130 and (165.2 - 160) x 130
        });
        Map<String, Object> summary = (Map<String, Object>) book.get("summary");
        assertThat(summary).containsEntry("openPnl", 1127.75).containsEntry("atRisk", 5885.75)
                .containsEntry("realisedToday", 0.0).containsEntry("sinceLiveNet", -2663.51).containsEntry("sinceLiveTrades", 1);
        assertThat((Map<String, Object>) summary.get("perPoint")).containsExactly(Map.entry("NIFTY", 29.25));
        assertThat(new PositionFacts(dataSource).of(((Number) open.getFirst().get("id")).longValue()))
                .containsEntry("bid", 163.70).containsEntry("pnl", 1127.75).containsEntry("perPoint", 29.25)
                .doesNotContainKeys("strategyExitedAt", "lateJoin");

        // 17 Nov 2026 (phase 2): the exit preview prices the sale at the captured bid; a 30 s old quote asks for typing EXIT
        long openId = ((Number) open.getFirst().get("id")).longValue();
        Map<String, Object> preview = api.exitPreview("local-owner", account, openId, null);
        assertThat(preview).containsEntry("mode", "LIVE").containsEntry("quantity", 65L).containsEntry("lots", 1L)
                .containsEntry("remaining", 0L).containsEntry("bid", 163.70).containsEntry("price", 163.70)
                .containsEntry("priceFrom", "bid").containsEntry("bidQuantity", 650L).containsEntry("orders", 1L)
                .containsEntry("gross", 1127.75).containsEntry("typeToConfirm", true);
        assertThat((List<String>) preview.get("warnings")).singleElement().asString().contains("s old");
        assertThatThrownBy(() -> api.exitPreview("local-owner", account, openId, 30L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> api.exitPreview("kc-nobody", account, openId, null)).isInstanceOf(ResponseStatusException.class);

        // a stop being cancelled no longer protects the position: not working, nothing counted at risk
        jdbc.update("update exec.order_execution set status = 'CANCEL_PENDING' where role = 'STOP' and trade_intent_id = ?", live);
        Map<String, Object> cancelling = ((List<Map<String, Object>>) api.liveBook("local-owner", account, 7).get("open")).getFirst();
        assertThat(cancelling).containsEntry("stopWorking", false).doesNotContainKey("atRisk");

        // sold outside the app: the reconciler's kill names the contract; the book offers the window to price the hand sale
        jdbc.update("update exec.order_execution set status = 'CANCELLED', updated_at = now() - interval '400 seconds' "
                + "where role = 'STOP' and trade_intent_id = ?", live);
        tick("390 seconds", 150.10);
        tick("370 seconds", 147.00);
        jdbc.update("insert into exec.kill_switch (tenant_id, scope, broker_account_id, reason, engaged_by, engaged_at) values "
                + "(?, 'ACCOUNT', ?, 'RECONCILIATION: NSE_FO|40009: ours 65, broker 0', 'reconciler', now() - interval '350 "
                + "seconds')", tenant, account);
        Map<String, Object> sold = api.liveBook("local-owner", account, 7);
        assertThat(((List<Map<String, Object>>) sold.get("open")).getFirst()).extractingByKey("notAtBroker")
                .satisfies(h -> assertThat((Map<String, Object>) h).containsEntry("bidFirst", 150.10)
                        .containsEntry("bidLow", 147.00).containsEntry("bidHigh", 150.10));
        assertThat((List<Map<String, Object>>) sold.get("killSwitches")).hasSize(2).first().as("the account's own: oldest first")
                .satisfies(k -> assertThat(k).containsEntry("releasable", true));

        long other = accounts.ensureTenant("other");
        accounts.ensureMembership(other, accounts.ensureUser("kc-other", "other"), "OWNER");
        assertThatThrownBy(() -> api.liveBook("kc-other", account, 7)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        // 17 Nov 2026: quotes are read through the contract (ref.instrument -> the contract index), not by token alone:
        // a tick of the same contract under another token is the newest quote; an unknown key falls back to the token
        jdbc.update("insert into md.option_tick (manifest_id, session_date, underlying, recv_ts, src_seq, instrument_token, "
                + "symbol, expiry, strike, option_type, ltp, bid_px, bid_qty, bid_orders, ask_px, ask_qty, ask_orders, "
                + "analytics_complete, depth_complete) values (1, current_date, 'NIFTY', now() - interval '2 seconds', 2, ?, "
                + "'NIFTY 24650 CE 09 NOV 26', date '2026-11-09', 24650, 'CE', 171, '{171}', '{650}', '{3}', '{171.05}', "
                + "'{650}', '{3}', false, true)", TOKEN + 1);
        ContractQuotes quotes = new ContractQuotes(jdbc);
        assertThat(quotes.latest("NSE_FO|40009", "NIFTY", TOKEN, 10)).containsEntry("bid", 171.0);
        assertThat(quotes.latest("NSE_FO|99999", "NIFTY", TOKEN, 10)).as("not in ref.instrument: by token")
                .containsEntry("bid", 163.70);
    }

    /**
     * 17 Nov 2026: a paper twin's book is its PAPER entries; an open position the strategy already exited (more than two
     * minutes ago) says so, and one bought long after its signal is a late join. A managed, on-time one says neither.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aPaperAccountsBookShowsItsPaperPositionsAndFlagsStrayAndLateOnes() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("family-paper");
        long member = accounts.ensureUser("kc-paper", "paper");
        accounts.ensureMembership(tenant, member, "OWNER");
        long twin = accounts.ensureFollowerAccount(tenant, member, "Kotak paper", BrokerType.PAPER, TradeMode.PAPER,
                50_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v7.yaml", 0);
        long signal = accounts.ensureSignalSourceAccount(tenant, member, "SIG-P", 500_000, LocalDate.now(),
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "G-P", signal);
        new FanOut(dataSource).subscribe(group, twin, List.of("oi-burst-cross"), 1.0);
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '3 hours' where copy_group_id = ?", group);
        long session = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('SIG-P', 'PAPER_LIVE', current_date, 'it', 'live-set', "
                + "'sha256:it', '{}'::jsonb, 'it', 'DONE') returning id", Long.class);
        long stray = TOKEN + 7, managed = TOKEN + 8;
        java.util.function.BiFunction<Long, Object[], Long> decide = (token, at) -> {
            new SignalDecisionStore(dataSource).insert(new SignalDecision(group, session, LocalDate.now(), (Instant) at[0],
                    "oi-burst-cross", 2, "NIFTY", (SignalDecision.Action) at[1], "CE", null, "CONFIRMED", "IT", token,
                    "NSE_FO|" + token, "NIFTY 25350 CE 17 NOV 26", 65, 64.25, 9_500, 500_000, 0,
                    "P:" + token + ":" + at[1], Map.of()));
            new FanOut(dataSource).fanOut(group, LocalDate.now());
            return jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ? order by id desc limit 1",
                    Long.class, twin);
        };
        Instant entryAt = Instant.now().minusSeconds(3900);
        long strayIntent = decide.apply(stray, new Object[] {entryAt, SignalDecision.Action.ENTER});
        decide.apply(stray, new Object[] {entryAt.plusSeconds(900), SignalDecision.Action.EXIT});
        // the twin bought it an hour after its signal (the first-switch bug), at the signal's price
        jdbc.update("update exec.trade_intent set created_at = now() - interval '10 minutes' where id = ?", strayIntent);
        long managedIntent = decide.apply(managed, new Object[] {Instant.now(), SignalDecision.Action.ENTER});
        for (Object[] p : new Object[][] {{strayIntent, stray}, {managedIntent, managed}}) {
            jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                    + "option_side, instrument_token, instrument_key, symbol, quantity_open, average_entry, opened_at, status) "
                    + "values (?, ?, ?, 'oi-burst-cross', 'NIFTY', 'CE', ?, ?, 'NIFTY 25350 CE 17 NOV 26', 65, 64.25, "
                    + "now() - interval '10 minutes', 'OPEN')", tenant, twin, p[0], p[1], "NSE_FO|" + p[1]);
        }

        Map<String, Object> book = new LiveBookController(new Access(dataSource), dataSource).liveBook("kc-paper", twin, 7);
        assertThat(book).containsEntry("bookMode", "PAPER").containsEntry("canAct", true).containsEntry("squareOff", null);
        List<Map<String, Object>> open = (List<Map<String, Object>>) book.get("open");
        assertThat(open).hasSize(2);
        Map<String, Object> s = open.stream().filter(p -> p.get("id").equals(jdbc.queryForObject(
                "select id from exec.position where entry_trade_intent_id = ?", Long.class, strayIntent))).findFirst().orElseThrow();
        assertThat((String) s.get("strategyExitedAt")).isNotNull();
        assertThat((Map<String, Object>) s.get("lateJoin")).containsKeys("signalAt", "joinedAt");
        Map<String, Object> m = open.stream().filter(p -> p != s).findFirst().orElseThrow();
        assertThat(m).doesNotContainKeys("strategyExitedAt", "lateJoin");

        // its preview: no quote captured and no signal after it was bought (the strategy had exited before this late
        // join), so a person's paper exit is refused rather than booked at a signal's price (principal review 20 Nov
        // 2026: never the entry's own price); paper never asks for typing
        Map<String, Object> preview = new LiveBookController(new Access(dataSource), dataSource)
                .exitPreview("kc-paper", twin, ((Number) s.get("id")).longValue(), null);
        assertThat(preview).containsEntry("mode", "PAPER").containsEntry("quantity", 65L)
                .containsEntry("priceFrom", "none").containsEntry("orders", 1L).containsEntry("typeToConfirm", false)
                .doesNotContainKeys("charges", "rest");
        assertThat((String) preview.get("refusal")).contains("no signal since the entry");
        assertThat((List<String>) preview.get("warnings")).singleElement().asString().contains("no quote");
        // Telegram's line for it says the same as the book
        String line = new PositionFacts(dataSource).line(((Number) s.get("id")).longValue());
        assertThat(line).contains("no recent quote").contains("NOT MANAGED").contains("LATE JOIN");
    }

    /**
     * V49 (19 Nov 2026, user on DEMO-1: "it should be as same as PAPER-1"; review the same day): a mirror account takes no
     * entry of its own and an EXIT reaches it only while it still holds a position; a manual buy is refused; every view
     * of its trades (book, Today, details, positions, P&L, ledger, My accounts) is the mirrored engine account's closed
     * PAPER_LIVE trades from the mirror account's own start (the open ones come from /api/status in the UI).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aMirrorAccountShowsTheMirroredSessionsTradesAndTakesNoneOfItsOwn() {
        AccountStore accounts = new AccountStore(dataSource);
        long tenant = accounts.ensureTenant("family-mirror");
        long member = accounts.ensureUser("kc-mirror", "mirror");
        accounts.ensureMembership(tenant, member, "OWNER");
        long demo = accounts.ensureFollowerAccount(tenant, member, "DEMO-M", BrokerType.PAPER, TradeMode.PAPER,
                500_000, EquityMode.COMPOUNDING, LocalDate.now(), "config/risk/follower-risk.v3.yaml", 100);
        long real = accounts.ensureFollowerAccount(tenant, member, "REAL-M", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                100_000, EquityMode.FIXED, LocalDate.now(), "config/risk/follower-risk.v7.yaml", 0);
        assertThatThrownBy(() -> jdbc.update("update acct.broker_account set mirror_of = 'PAPER-M' where id = ?", real))
                .hasMessageContaining("broker_account_mirror_paper");             // only a paper account mirrors

        // the fan-out: no entry and no exit while it holds nothing; an exit once it holds a position from before
        long signal = accounts.ensureSignalSourceAccount(tenant, member, "SIG-M", 500_000, LocalDate.now(),
                "config/risk/signal-source.v2.yaml");
        long group = accounts.ensureCopyGroup(tenant, "G-M", signal);
        new FanOut(dataSource).subscribe(group, demo, List.of("oi-burst-cross"), 1.0);
        jdbc.update("update acct.strategy_subscription set enabled_at = now() - interval '1 hour' where copy_group_id = ?", group);
        long sigSession = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('SIG-M', 'PAPER_LIVE', current_date, 'it', 'live-set', "
                + "'sha256:it', '{}'::jsonb, 'it', 'RUNNING') returning id", Long.class);
        java.util.function.Consumer<String> decide = key -> {
            new SignalDecisionStore(dataSource).insert(new SignalDecision(group, sigSession, LocalDate.now(), Instant.now(),
                    "oi-burst-cross", 2, "NIFTY", key.startsWith("ENTER") ? SignalDecision.Action.ENTER
                    : SignalDecision.Action.EXIT, "CE", null, "CONFIRMED", "IT", TOKEN, "NSE_FO|40009",
                    "NIFTY 24650 CE 09 NOV 26", 65, 146, 9_500, 500_000, 0, "M:" + key, Map.of()));
            new FanOut(dataSource).fanOut(group, LocalDate.now());
        };
        // a position it bought before it became a mirror
        decide.accept("ENTER-0");
        long held = jdbc.queryForObject("select id from exec.trade_intent where broker_account_id = ?", Long.class, demo);
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                + "option_side, instrument_token, instrument_key, symbol, quantity_open, average_entry, opened_at, status) "
                + "values (?, ?, ?, 'oi-burst-cross', 'NIFTY', 'CE', ?, 'NSE_FO|40009', 'NIFTY 24650 CE 09 NOV 26', 65, "
                + "146, now() - interval '1 day', 'OPEN')", tenant, demo, held, TOKEN);
        jdbc.update("update acct.broker_account set mirror_of = 'PAPER-M' where id = ?", demo);
        assertThatThrownBy(() -> com.autotrade.execution.ManualOrders.refuseMirror(jdbc, accounts.byId(demo).orElseThrow()))
                .hasMessageContaining("mirrors PAPER-M");                        // no manual buy or chart order on it
        decide.accept("ENTER-1");                           // a mirror: no entry
        decide.accept("EXIT-1");                            // ... but the exit of what it still holds
        assertThat(jdbc.queryForList("select action from exec.trade_intent where broker_account_id = ? order by id",
                String.class, demo)).containsExactly("ENTER", "EXIT");
        jdbc.update("update exec.position set status = 'CLOSED', quantity_open = 0, closed_at = now() - interval '23 hours', "
                + "realised_gross = 0, charges = 0 where broker_account_id = ?", demo);   // closed before today: not in today's views
        decide.accept("EXIT-2");                            // holding nothing: no exit intent (no NOT_HELD noise)
        assertThat(jdbc.queryForList("select action from exec.trade_intent where broker_account_id = ? order by id",
                String.class, demo)).containsExactly("ENTER", "EXIT");

        // the mirrored engine account: one trade closed today, one closed before the mirror account's start (not shown),
        // and one of another account; an open engine position is never in the database (written when it closes)
        long mine = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('PAPER-M', 'PAPER_LIVE', current_date, 'it', 'live-set', "
                + "'sha256:it', '{}'::jsonb, 'it', 'RUNNING') returning id", Long.class);
        long before = jdbc.queryForObject("insert into trade.session (account, mode, session_date, feed, strategy_id, "
                + "strategy_hash, configs, code_version, status) values ('PAPER-M', 'PAPER_LIVE', current_date - 3, 'it', "
                + "'live-set', 'sha256:it', '{}'::jsonb, 'it', 'DONE') returning id", Long.class);
        String insert = "insert into trade.position (session_id, underlying, option_side, symbol, opened_at, closed_at, stages, "
                + "exit_reason, realised, costs, net, strategy_id, quantity, lot_size, average_cost, average_exit) values "
                + "(?, 'NIFTY', 'CE', ?, ?, ?, '{CONFIRMED}', ?, ?, ?, ?, 'session-break-retest', ?, 65, ?, ?)";
        java.sql.Timestamp opened = java.sql.Timestamp.from(Instant.now().minusSeconds(2400));
        jdbc.update(insert, mine, "NIFTY 25350 CE 23 NOV 26", opened, java.sql.Timestamp.from(Instant.now().minusSeconds(600)),
                "LEVEL_REJECT", 38_844.0, 291.0, 38_553.0, 585, 151.70, 218.10);
        jdbc.update(insert, before, "NIFTY 25000 CE 23 NOV 26", java.sql.Timestamp.from(Instant.now().minusSeconds(3 * 86_400)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(3 * 86_400 - 600)), "STOP", -5_000.0, 100.0, -5_100.0, 65,
                100.0, 23.08);
        jdbc.update(insert, mine, "NIFTY 25400 CE 23 NOV 26", opened, java.sql.Timestamp.from(Instant.now().minusSeconds(300)),
                "ENTRY_NOT_FILLED", 0.0, 0.0, 0.0, 0, null, null);        // never filled: not a trade
        jdbc.update(insert, sigSession, "NIFTY 24650 CE 09 NOV 26", opened, java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                "STOP", -9_999.0, 1.0, -10_000.0, 65, 146.0, 0.0);

        Map<String, Object> book = new LiveBookController(new Access(dataSource), dataSource).liveBook("kc-mirror", demo, 7);
        assertThat(book).containsEntry("mirrorOf", "PAPER-M").containsEntry("bookMode", "PAPER")
                .containsEntry("canAct", false).containsEntry("canStop", false).containsEntry("open", List.of());
        List<Map<String, Object>> closed = (List<Map<String, Object>>) book.get("closed");
        assertThat(closed).singleElement().satisfies(c -> assertThat(c).containsEntry("net", 38_553.0)
                .containsEntry("charges", 291.0).containsEntry("exit", 218.1).containsEntry("exitReason", "LEVEL_REJECT")
                .containsEntry("quantity", 585L));
        Map<String, Object> summary = (Map<String, Object>) book.get("summary");
        assertThat(summary).containsEntry("realisedToday", 38_553.0).containsEntry("openPnl", 0.0)
                .containsEntry("sinceLiveNet", 38_553.0).containsEntry("sinceLiveTrades", 1);

        // the Brokers page's account panel and My accounts read the same trades (review: they still showed its own)
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support
                .TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        ExecutionProperties properties = new ExecutionProperties(null, "*", 1000, null, 0, "it", null);
        AccountAdminController admin = new AccountAdminController(new Access(dataSource), dataSource, tx, properties,
                TestBrokers.live(dataSource));
        Map<String, Object> details = admin.details("kc-mirror", demo);
        assertThat(details).containsEntry("mirrorOf", "PAPER-M");
        assertThat((Map<String, Object>) details.get("today")).satisfies(t -> {
            assertThat(((Number) t.get("open")).intValue()).isZero();
            assertThat(((Number) t.get("closed")).intValue()).isEqualTo(1);
            assertThat(((Number) t.get("net")).doubleValue()).isEqualTo(38_553.0);
        });
        assertThat(admin.positions("kc-mirror", demo, null)).extracting(p -> p.get("symbol"))
                .containsExactly("NIFTY 25350 CE 23 NOV 26");
        assertThat(admin.ledger("kc-mirror", demo, 60)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("trades", 1).containsEntry("open", 0).containsEntry("net", 38_553.0)
                .containsEntry("openingEquity", 500_000.0).containsEntry("closingEquity", 538_553.0));
        assertThat(admin.pnl("kc-mirror", demo, 30)).singleElement()
                .satisfies(r -> assertThat(((Number) r.get("net")).doubleValue()).isEqualTo(38_553.0));
        AccountsController.DayBook card = new AccountsController(new Access(dataSource), dataSource,
                new ExecutionService(dataSource, properties)).accounts("kc-mirror").stream()
                .filter(v -> v.id() == demo).findFirst().orElseThrow().today();
        assertThat(card.net()).isEqualTo(38_553.0);
        assertThat(card.trades()).isEqualTo(1);
        assertThat(card.open()).isZero();
    }
}
