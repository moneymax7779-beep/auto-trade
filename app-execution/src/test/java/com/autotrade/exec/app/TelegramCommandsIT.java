package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telegram controls T0 + T1 (16 Nov 2026) against a stub Bot API: who is obeyed, the confirm card, the web's rights and
 * actions, expiry, replays, restarts and the reader's alarm.
 */
@Testcontainers(disabledWithoutDocker = true)
class TelegramCommandsIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long ALICE_CHAT = 111, VIEWER_CHAT = 222, BOB_CHAT = 333, STRANGER = 999;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static HttpServer stub;
    private static final List<Map.Entry<String, JsonNode>> calls = new ArrayList<>();
    private static final Deque<int[]> pollStatus = new ArrayDeque<>();
    private static final Deque<String> pollBodies = new ArrayDeque<>();
    /** Each getUpdates' query string, in order. */
    private static final List<String> pollQueries = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static Path botFile;
    private static volatile String webhook = "";
    private static long aliceTenant, aliceAccount, bobAccount, position, nextMessage = 500;
    private static TelegramCommands commands;
    private static TelegramGateway gateway;
    private static TelegramNotifier notifier;
    private static final List<String> actions = new ArrayList<>();
    private static volatile List<String> flattenResult = List.of("1 position sold");
    private static long updateId = 1000;

    @BeforeAll
    static void start() throws IOException {
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
        aliceTenant = accounts.ensureTenant("t-alice");
        long bobTenant = accounts.ensureTenant("t-bob");
        long alice = accounts.ensureUser("kc-alice", "alice"), viewer = accounts.ensureUser("kc-viewer", "viewer");
        long bob = accounts.ensureUser("kc-bob", "bob");
        accounts.ensureMembership(aliceTenant, alice, "OWNER");
        accounts.ensureMembership(aliceTenant, viewer, "VIEWER");
        accounts.ensureMembership(bobTenant, bob, "OWNER");
        jdbc.update("update acct.app_user set telegram_chat_id = ? where id = ?", Long.toString(ALICE_CHAT), alice);
        jdbc.update("update acct.app_user set telegram_chat_id = ? where id = ?", Long.toString(VIEWER_CHAT), viewer);
        jdbc.update("update acct.app_user set telegram_chat_id = ? where id = ?", Long.toString(BOB_CHAT), bob);
        LocalDate day = LocalDate.of(2026, 11, 16);
        aliceAccount = accounts.ensureFollowerAccount(aliceTenant, alice, "ALICE-1", BrokerType.ZERODHA, TradeMode.DRY_RUN,
                500_000, EquityMode.FIXED, day, "../config/risk/follower-risk.v3.yaml", 100);
        bobAccount = accounts.ensureFollowerAccount(bobTenant, bob, "BOB-1", BrokerType.PAPER, TradeMode.PAPER, 500_000,
                EquityMode.FIXED, day, "../config/risk/follower-risk.v3.yaml", 100);
        long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, source, idempotency_key, status) values "
                + "(?, 'MANUAL', 'tg1', 'DONE') returning id", Long.class, aliceTenant);
        long intent = jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, state, "
                + "idempotency_key) values (?, ?, ?, 'ZERODHA', 'DRY_RUN', 'ma-cross', 1, 'NIFTY', 'ENTER', 'CE', "
                + "'EXECUTED', 'tg1') returning id", Long.class, aliceTenant, batch, aliceAccount);
        position = jdbc.queryForObject("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, "
                + "strategy_id, underlying, option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, "
                + "status) values (?, ?, ?, 'ma-cross', 'NIFTY', 'CE', 77, 'NIFTY 24200 CE', 75, 58.2, now(), 'OPEN') "
                + "returning id", Long.class, aliceTenant, aliceAccount, intent);

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String method = exchange.getRequestURI().getPath().replaceAll(".*/", "");
            byte[] in = exchange.getRequestBody().readAllBytes();
            JsonNode body = parse(new String(in, StandardCharsets.UTF_8));
            int status = 200;
            String out;
            synchronized (calls) {
                calls.add(Map.entry(method, body));
            }
            switch (method) {
                case "getUpdates" -> {
                    pollQueries.add(String.valueOf(exchange.getRequestURI().getQuery()));
                    int[] s = pollStatus.poll();
                    status = s == null ? 200 : s[0];
                    String b = pollBodies.poll();
                    out = status == 200 ? "{\"ok\":true,\"result\":" + (b == null ? "[]" : b) + "}" : "{\"ok\":false}";
                }
                case "sendMessage" -> out = "{\"ok\":true,\"result\":{\"message_id\":" + (nextMessage++) + "}}";
                case "getWebhookInfo" -> out = "{\"ok\":true,\"result\":{\"url\":\"" + webhook + "\"}}";
                default -> out = "{\"ok\":true,\"result\":true}";
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        Path dir = Files.createTempDirectory("tg");
        Path file = dir.resolve("bot.env");
        botFile = file;
        Files.writeString(file, "TELEGRAM_BOT_TOKEN=T0K\nTELEGRAM_CHAT_ID=42\n");
        String api = "http://127.0.0.1:" + stub.getAddress().getPort();
        KiteLoginProperties kite = new KiteLoginProperties(null, null, null, null, null, file.toString());
        notifier = new TelegramNotifier(kite, dataSource, api, true);
        TelegramCommandProperties props = new TelegramCommandProperties(true, null, 60, 60, false);
        TelegramCommands.Actions fake = new TelegramCommands.Actions() {
            @Override
            public List<String> flatten(long accountId, String actor, String reason) {
                actions.add("flatten " + accountId + " " + actor);
                return flattenResult;
            }

            @Override
            public List<String> exitPosition(long accountId, long positionId, String actor) {
                actions.add("exit " + accountId + " " + positionId + " " + actor);
                return List.of("sold #" + positionId);
            }

            @Override
            public Instant lastTick() {
                return Instant.now();
            }
        };
        commands = new TelegramCommands(dataSource, new Access(dataSource), fake, new TelegramBot(notifier::token, api),
                props, Runnable::run);
        gateway = new TelegramGateway(dataSource, commands, props, notifier,
                new TradingDays("../config/exchange/nse-bse-sessions.v3.yaml"),
                new ExecutionProperties(null, "*", 0, null, 0, "it", null));
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void clear() {
        synchronized (calls) {
            calls.clear();
        }
        actions.clear();
        flattenResult = List.of("1 position sold");
        jdbc.update("update exec.kill_switch set released_at = now(), released_by = 'it' where released_at is null");
    }

    // ---------------------------------------------------------------------------------------------------- who

    @Test
    void aStrangerOrAGroupOrAForwardedSenderIsNeverObeyed() {
        int before = commandCount();
        assertThat(deliver(message(STRANGER, STRANGER, "private", "/flatten ALICE-1", 0))).isEqualTo("UNLINKED");
        assertThat(lastSent()).contains("not linked");
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "group", "/kill", 0))).isEqualTo("NOT_PRIVATE");
        assertThat(deliver(message(ALICE_CHAT, 555, "private", "/kill ALICE-1", 0)))
                .as("a private chat whose sender is not the chat").isEqualTo("UNLINKED");
        assertThat(sent("sendMessage")).as("the group was not answered").hasSize(2);
        assertThat(commandCount()).isEqualTo(before);
    }

    @Test
    void aMessageOlderThanAMinuteIsNeverRunLate() {
        int before = commandCount();
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/flatten ALICE-1", 120))).isEqualTo("TOO_OLD");
        assertThat(lastSent()).contains("Not run").contains("Send it again");
        assertThat(commandCount()).isEqualTo(before);
    }

    @Test
    void readsAreForEveryMemberAndShowTheirOwnOnly() {
        assertThat(deliver(message(VIEWER_CHAT, VIEWER_CHAT, "private", "/positions", 0))).isEqualTo("POSITIONS");
        assertThat(lastSent()).contains("#" + position).contains("ALICE-1").contains("NIFTY 24200 CE").contains("qty 75");
        assertThat(deliver(message(BOB_CHAT, BOB_CHAT, "private", "/positions", 0))).isEqualTo("POSITIONS");
        assertThat(lastSent()).isEqualTo("No open positions.");
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/status", 0))).isEqualTo("STATUS");
        assertThat(lastSent()).contains("ALICE-1 · DRY_RUN").contains("open 1").doesNotContain("BOB-1");
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/whoami@AutoDhan_bot", 0))).isEqualTo("WHOAMI");
        assertThat(lastSent()).contains("kc-alice").contains("t-alice: OWNER");
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "hello", 0))).isEqualTo("HELP");
        assertThat(lastSent()).contains("/flatten").contains("web only");
    }

    @Test
    void aViewerOrAnotherWorkspaceCannotStopAnything() {
        int before = commandCount();
        assertThat(deliver(message(VIEWER_CHAT, VIEWER_CHAT, "private", "/kill ALICE-1", 0))).isEqualTo("FORBIDDEN");
        assertThat(lastSent()).contains("read-only");
        assertThat(deliver(message(BOB_CHAT, BOB_CHAT, "private", "/flatten ALICE-1", 0))).isEqualTo("NO_ACCOUNT");
        assertThat(deliver(message(BOB_CHAT, BOB_CHAT, "private", "/exit " + position, 0))).isEqualTo("NO_POSITION");
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/killall", 0))).isEqualTo("FORBIDDEN");
        assertThat(commandCount()).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------------- the card

    @Test
    void aKillRunsOnlyOnTheConfirmTapAndIsTheWebsOwnSwitch() {
        String out = deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/kill alice-1", 0));
        assertThat(out).startsWith("CARD T");
        long id = Long.parseLong(out.substring(6));
        JsonNode card = lastCall("sendMessage");
        assertThat(card.path("text").asString()).contains("KILL ALICE-1 (DRY_RUN, 1 open position(s))")
                .contains("nothing is sold").contains("Release on the web only");
        assertThat(card.path("reply_markup").path("inline_keyboard").size()).as("Confirm and Cancel on separate rows")
                .isEqualTo(2);
        assertThat(engaged()).as("nothing before the tap").isEmpty();

        String confirm = card.path("reply_markup").path("inline_keyboard").get(0).get(0).path("callback_data").asString();
        assertThat(deliver(callback(ALICE_CHAT, ALICE_CHAT, confirm))).isEqualTo("CONFIRMED T" + id);
        assertThat(engaged()).containsExactly("ACCOUNT telegram:kc-alice");
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(lastCall("editMessageText").path("text").asString()).contains("T" + id + " KILL_ACCOUNT DONE")
                .contains("open positions: 1");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'TELEGRAM_COMMAND' and "
                + "detail ->> 'ref' = ? and actor = 'telegram:kc-alice'", Integer.class, "T" + id)).isEqualTo(1);

        assertThat(deliver(callback(ALICE_CHAT, ALICE_CHAT, confirm))).as("a second tap").isEqualTo("STALE T" + id);
        assertThat(engaged()).hasSize(1);
    }

    @Test
    void aTapByAnotherPersonOrWithAWrongNonceOrTooLateDoesNothing() {
        long id = card("/kill ALICE-1");
        String nonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, id);
        assertThat(deliver(callback(BOB_CHAT, BOB_CHAT, "c:" + id + ":" + nonce))).isEqualTo("STALE T" + id);
        assertThat(deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + id + ":WRONGNONCE12"))).isEqualTo("STALE T" + id);
        assertThat(status(id)).isEqualTo("PENDING");
        jdbc.update("update exec.telegram_command set created_at = now() - interval '61 seconds' where id = ?", id);
        assertThat(deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + id + ":" + nonce))).isEqualTo("EXPIRED T" + id);
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(engaged()).isEmpty();
    }

    @Test
    void cancelDoesNothing() {
        long id = card("/kill");
        String nonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, id);
        assertThat(lastCall("sendMessage").path("text").asString()).contains("KILL workspace t-alice");
        assertThat(deliver(callback(ALICE_CHAT, ALICE_CHAT, "x:" + id + ":" + nonce))).isEqualTo("CANCELLED T" + id);
        assertThat(engaged()).isEmpty();
        assertThat(deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + id + ":" + nonce))).isEqualTo("STALE T" + id);
        assertThat(engaged()).isEmpty();
    }

    @Test
    void theRightsAreCheckedAgainAtTheTap() {
        long id = card("/kill ALICE-1");
        String nonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, id);
        jdbc.update("update acct.tenant_membership set role = 'VIEWER' where tenant_id = ? and app_user_id = "
                + "(select id from acct.app_user where external_subject = 'kc-alice')", aliceTenant);
        try {
            deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + id + ":" + nonce));
            assertThat(status(id)).isEqualTo("REFUSED");
            assertThat(engaged()).isEmpty();
        } finally {
            jdbc.update("update acct.tenant_membership set role = 'OWNER' where tenant_id = ? and app_user_id = "
                    + "(select id from acct.app_user where external_subject = 'kc-alice')", aliceTenant);
        }
    }

    @Test
    void exitIsPickedFromAListThenConfirmedAndCallsTheWebsExit() {
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/exit", 0))).isEqualTo("PICK");
        JsonNode list = lastCall("sendMessage");
        String pick = list.path("reply_markup").path("inline_keyboard").get(0).get(0).path("callback_data").asString();
        assertThat(pick).isEqualTo("p:e:" + position);
        String out = deliver(callback(ALICE_CHAT, ALICE_CHAT, pick));
        assertThat(out).startsWith("CARD T");
        JsonNode card = lastCall("sendMessage");
        assertThat(card.path("text").asString()).contains("EXIT ALICE-1 (DRY_RUN): #" + position + " NIFTY 24200 CE qty 75 "
                + "avg 58.20 (ma-cross)");
        assertThat(actions).isEmpty();
        deliver(callback(ALICE_CHAT, ALICE_CHAT,
                card.path("reply_markup").path("inline_keyboard").get(0).get(0).path("callback_data").asString()));
        assertThat(actions).containsExactly("exit " + aliceAccount + " " + position + " telegram:kc-alice");
        assertThat(lastCall("editMessageText").path("text").asString()).contains("EXIT DONE").contains("sold #" + position);
    }

    @Test
    void flattenCallsTheWebsFlatten() {
        long id = card("/flatten ALICE-1");
        JsonNode card = lastCall("sendMessage");
        assertThat(card.path("text").asString()).contains("FLATTEN ALICE-1 (DRY_RUN)").contains("NIFTY 24200 CE qty 75");
        String nonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, id);
        deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + id + ":" + nonce));
        assertThat(actions).containsExactly("flatten " + aliceAccount + " telegram:kc-alice");
        assertThat(status(id)).isEqualTo("DONE");
    }

    // ---------------------------------------------------------------------------------------------------- the reader

    @Test
    void theReaderKeepsEachUpdateOnceAndTheStartLinkReadsTheTable() {
        gateway.prepare();
        ObjectNode start = message(STRANGER, STRANGER, "private", "/start ABCD2345", 0);
        long u = start.path("update_id").asLong();
        String update = start.toString();
        pollBodies.add("[" + update + "]");
        assertThat(gateway.pollOnce(0)).isTrue();
        pollBodies.add("[" + update + "]");                                   // Telegram sends it again
        assertThat(gateway.pollOnce(0)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from exec.telegram_update where update_id = ?", Integer.class, u))
                .isEqualTo(1);
        assertThat(sent("sendMessage")).as("answered once").hasSize(1);
        assertThat(lastSent()).contains("Press Check");
        assertThat(notifier.startMessages()).extracting(TelegramNotifier.StartMessage::code).contains("ABCD2345");
        assertThat(jdbc.queryForObject("select outcome from exec.telegram_update where update_id = ?", String.class, u))
                .isEqualTo("START");
        assertThat(jdbc.queryForObject("select last_ok_at is not null from exec.telegram_poller", Boolean.class)).isTrue();
    }

    @Test
    void afterAQuietWeekOrOnAnotherBotTheReaderReadsWithoutItsOffset() throws Exception {
        // review 16 Nov 2026 (D8): Telegram picks the next update_id at random after a week without updates; one below our
        // offset was confirmed without being returned (a /kill lost, the poller looking healthy). The same with another bot
        Path saved = Files.createTempFile("bot", ".env");
        Files.copy(botFile, saved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.writeString(botFile, "TELEGRAM_BOT_TOKEN=4242:AAA\nTELEGRAM_CHAT_ID=42\n");
            gateway.prepare();
            jdbc.update("update exec.telegram_update set received_at = now() - interval '7 days'");
            long high = jdbc.queryForObject("select coalesce(max(update_id), 0) + 50 from exec.telegram_update", Long.class);
            jdbc.update("insert into exec.telegram_update (update_id, received_at, kind, processed_at, outcome) values (?, "
                    + "now() - interval '7 days', 'OTHER', now(), 'IT')", high);
            gateway.prepare();                                            // offset high + 1, the newest 7 days old
            pollQueries.clear();
            ObjectNode low = message(STRANGER, STRANGER, "private", "/start LOWID234", 0);
            low.put("update_id", 7);                                      // the random restart, below our offset
            pollBodies.add("[" + low + "]");
            assertThat(gateway.pollOnce(0)).isTrue();
            assertThat(pollQueries.getFirst()).as("no offset after the quiet week").doesNotContain("offset=");
            assertThat(jdbc.queryForObject("select outcome from exec.telegram_update where update_id = 7", String.class))
                    .isEqualTo("START");
            assertThat(gateway.pollOnce(0)).isTrue();
            assertThat(pollQueries.get(1)).as("then from the new id").contains("offset=8&");
            // second review: a restart took max(update_id) + 1 (the old high id) and ate every update below it
            gateway.prepare();
            assertThat(gateway.pollOnce(0)).isTrue();
            assertThat(pollQueries.get(2)).as("a restart goes on from the reader's own offset").contains("offset=8&");

            Files.writeString(botFile, "TELEGRAM_BOT_TOKEN=5353:BBB\nTELEGRAM_CHAT_ID=42\n");
            assertThat(gateway.pollOnce(0)).isTrue();
            assertThat(pollQueries.get(3)).as("another bot: its own first update").doesNotContain("offset=");
            assertThat(jdbc.queryForObject("select bot_id from exec.telegram_poller", Long.class)).isEqualTo(5353L);
            Files.writeString(botFile, "TELEGRAM_BOT_TOKEN=6464:CCC\nTELEGRAM_CHAT_ID=42\n");
            gateway.prepare();                                            // a restart on a third bot
            assertThat(gateway.pollOnce(0)).isTrue();
            assertThat(pollQueries.get(4)).doesNotContain("offset=");
        } finally {
            Files.copy(saved, botFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            jdbc.update("update exec.telegram_update set received_at = now() where received_at < now() - interval '1 day'");
            jdbc.update("delete from exec.telegram_update where update_id = 7");
            gateway.prepare();
        }
    }

    @Test
    void commandsOnWithNoTokenRaiseTheAlarm() {
        // review 16 Nov 2026 (D9): only a log line, so commands looked on while nothing read them
        TelegramCommandProperties on = new TelegramCommandProperties(true, null, 60, 60, false);
        TelegramCommands noBot = new TelegramCommands(dataSource, new Access(dataSource), null, new TelegramBot(() -> null,
                "http://127.0.0.1:" + stub.getAddress().getPort()), on, Runnable::run);
        TelegramGateway reader = new TelegramGateway(dataSource, noBot, on, notifier,
                new TradingDays("../config/exchange/nse-bse-sessions.v3.yaml"),
                new ExecutionProperties(null, "*", 0, null, 0, "it", null));
        reader.start();
        assertThat(jdbc.queryForList("select detail ->> 'kind' from exec.audit_log where action = 'TELEGRAM_POLL_ALARM'",
                String.class)).contains("NO_TOKEN");
    }

    @Test
    void aConflictRaisesTheAlarmAndAWebhookToo() {
        pollStatus.add(new int[] {409});
        assertThat(gateway.pollOnce(0)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'TELEGRAM_POLL_ALARM' and "
                + "detail ->> 'kind' = 'CONFLICT'", Integer.class)).isEqualTo(1);
        assertThat(lastSent()).contains("409").contains("may NOT be reaching");
        pollStatus.add(new int[] {409});
        gateway.pollOnce(0);
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'TELEGRAM_POLL_ALARM' and "
                + "detail ->> 'kind' = 'CONFLICT'", Integer.class)).as("once per 10 minutes").isEqualTo(1);
    }

    @Test
    void aWebhookOnTheBotRaisesTheAlarm() {
        assertThat(gateway.checkWebhook()).isFalse();
        webhook = "https://elsewhere.example/hook";
        try {
            assertThat(gateway.checkWebhook()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where action = 'TELEGRAM_POLL_ALARM' and "
                    + "detail ->> 'kind' = 'WEBHOOK'", Integer.class)).isEqualTo(1);
            assertThat(lastSent()).contains("webhook").contains("Revoke the token");
        } finally {
            webhook = "";
        }
    }

    @Test
    void aCommandCaughtByARestartIsNeverRunAgain() {
        long id = card("/kill ALICE-1");
        jdbc.update("update exec.telegram_command set status = 'CONFIRMED', confirmed_at = now() where id = ?", id);
        long pending = card("/flatten ALICE-1");
        commands.recover();
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(status(pending)).isEqualTo("EXPIRED");
        assertThat(lastSent()).contains("T" + id).contains("NOT run again");
        assertThat(engaged()).isEmpty();
        assertThat(actions).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------- review 16 Nov

    @Test
    void aModeChangeBetweenTheCardAndTheTapIsRefused() {
        long id = card("/flatten ALICE-1");
        String nonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, id);
        jdbc.update("update acct.broker_account set trade_mode = 'PAPER' where id = ?", aliceAccount);
        try {
            deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + id + ":" + nonce));
            assertThat(status(id)).isEqualTo("REFUSED");
            assertThat(actions).as("a DRY_RUN card never flattens a PAPER (or LIVE) account").isEmpty();
            assertThat(lastCall("editMessageText").path("text").asString()).contains("is PAPER now, the card said DRY_RUN");
        } finally {
            jdbc.update("update acct.broker_account set trade_mode = 'DRY_RUN' where id = ?", aliceAccount);
        }
    }

    @Test
    void nothingIsSoldOnALiveAccountUnlessLiveExitsIsOnButAKillStillWorks() {
        // review 16 Nov 2026: the plan's LIVE gate was not in the code; a card shown for a LIVE account was obeyed
        long card = card("/flatten ALICE-1");
        String nonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, card);
        jdbc.update("update acct.broker_account set trade_mode = 'LIVE' where id = ?", aliceAccount);
        try {
            int before = commandCount();
            assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/flatten ALICE-1", 0))).isEqualTo("FORBIDDEN");
            assertThat(lastSent()).contains("ALICE-1 is LIVE");
            assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/exit " + position, 0))).isEqualTo("FORBIDDEN");
            assertThat(commandCount()).isEqualTo(before);
            jdbc.update("update exec.telegram_command set trade_mode = 'LIVE' where id = ?", card);
            deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + card + ":" + nonce));
            assertThat(status(card)).as("a LIVE card shown before the gate is refused at the tap too").isEqualTo("REFUSED");
            assertThat(actions).isEmpty();
            long kill = card("/kill ALICE-1");
            String killNonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, kill);
            deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + kill + ":" + killNonce));
            assertThat(status(kill)).isEqualTo("DONE");
            assertThat(engaged()).isNotEmpty();
        } finally {
            jdbc.update("update acct.broker_account set trade_mode = 'DRY_RUN' where id = ?", aliceAccount);
        }
    }

    @Test
    void anAccountNameHeldInTwoWorkspacesIsPickedNotGuessed() {
        AccountStore accounts = new AccountStore(dataSource);
        long carol = accounts.ensureUser("kc-carol", "carol");
        long a = accounts.ensureTenant("t-carol-a"), b = accounts.ensureTenant("t-carol-b");
        accounts.ensureMembership(a, carol, "OWNER");
        accounts.ensureMembership(b, carol, "TRADER");
        jdbc.update("update acct.app_user set telegram_chat_id = '444' where id = ?", carol);
        LocalDate day = LocalDate.of(2026, 11, 16);
        long inA = accounts.createFollowerAccount(a, carol, "SAME-1", BrokerType.PAPER, TradeMode.PAPER, 100_000,
                EquityMode.FIXED, day, "../config/risk/follower-risk.v3.yaml", 100);
        long inB = accounts.createFollowerAccount(b, carol, "SAME-1", BrokerType.PAPER, TradeMode.PAPER, 100_000,
                EquityMode.FIXED, day, "../config/risk/follower-risk.v3.yaml", 100);
        int before = commandCount();
        assertThat(deliver(message(444, 444, "private", "/flatten SAME-1", 0))).isEqualTo("PICK");
        JsonNode rows = lastCall("sendMessage").path("reply_markup").path("inline_keyboard");
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.toString()).contains("t-carol-a").contains("t-carol-b").contains("p:f:" + inA)
                .contains("p:f:" + inB);
        assertThat(deliver(message(444, 444, "private", "/kill same-1", 0))).isEqualTo("PICK");
        assertThat(lastCall("sendMessage").toString()).contains("p:ka:" + inA).contains("p:ka:" + inB);
        assertThat(commandCount()).as("no card until one is picked").isEqualTo(before);
        assertThat(deliver(callback(444, 444, "p:ka:" + inB))).startsWith("CARD T");
        assertThat(lastSent()).contains("KILL SAME-1 (PAPER, 0 open position(s)), workspace t-carol-b");
    }

    @Test
    void aFlattenThatLeavesSomethingUnsoldSaysIncomplete() {
        flattenResult = List.of("NIFTY 24200 CE: NOT flattened, the broker holds 0 of our 75");
        long id = card("/flatten ALICE-1");
        String nonce = jdbc.queryForObject("select nonce from exec.telegram_command where id = ?", String.class, id);
        deliver(callback(ALICE_CHAT, ALICE_CHAT, "c:" + id + ":" + nonce));
        assertThat(status(id)).isEqualTo("INCOMPLETE");
        assertThat(lastCall("editMessageText").path("text").asString()).contains("FLATTEN INCOMPLETE")
                .contains("NOT flattened").contains("Check /positions");
    }

    @Test
    void aForwardedCommandIsNeverRun() {
        int before = commandCount();
        ObjectNode forwarded = message(ALICE_CHAT, ALICE_CHAT, "private", "/flatten ALICE-1", 0);
        ((ObjectNode) forwarded.path("message")).put("forward_date", Instant.now().getEpochSecond() - 3600);
        assertThat(deliver(forwarded)).isEqualTo("FORWARDED");
        assertThat(lastSent()).contains("Forwarded messages are never run");
        assertThat(commandCount()).isEqualTo(before);
    }

    @Test
    void aVeryOldMessageGetsNoAnswerAndAStrangerIsAnsweredOnce() {
        assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/kill", 3600))).isEqualTo("TOO_OLD_SILENT");
        assertThat(deliver(message(STRANGER, STRANGER, "private", "/start OLDCODE9", 3600))).isEqualTo("TOO_OLD_SILENT");
        assertThat(sent("sendMessage")).isEmpty();
        assertThat(notifier.startMessages()).as("an old /start still links").extracting(TelegramNotifier.StartMessage::code)
                .contains("OLDCODE9");
        long flood = 777;
        for (int i = 0; i < 5; i++) {
            assertThat(deliver(message(flood, flood, "private", "/flatten X", 0))).isEqualTo("UNLINKED");
        }
        assertThat(sent("sendMessage")).as("one 'not linked' answer, not five").hasSize(1);
    }

    @Test
    void anAccountTurnedOffThatStillHoldsAPositionCanBeFlattened() {
        jdbc.update("update acct.broker_account set enabled = false where id = ?", aliceAccount);
        try {
            assertThat(deliver(message(ALICE_CHAT, ALICE_CHAT, "private", "/flatten", 0))).isEqualTo("PICK");
            assertThat(lastCall("sendMessage").toString()).contains("p:f:" + aliceAccount).contains("open 1");
        } finally {
            jdbc.update("update acct.broker_account set enabled = true where id = ?", aliceAccount);
        }
    }

    @Test
    void aTextTooLongForTelegramIsCutNotLost() {
        String fitted = TelegramBot.fit("x".repeat(10_000));
        assertThat(fitted).hasSizeLessThanOrEqualTo(TelegramBot.MAX_TEXT).endsWith("the rest is on the web)");
        assertThat(TelegramBot.fit("short")).isEqualTo("short");
    }

    @Test
    void theIncompleteWordingMatchesTheEngines() {
        assertThat(TelegramCommands.incomplete(List.of("NIFTY: 75 sold at 58.20"))).isFalse();
        assertThat(TelegramCommands.incomplete(List.of("not flattened: no broker session"))).isTrue();
        assertThat(TelegramCommands.incomplete(List.of("X: 25 of 75 sold (PARTIAL x); the rest keeps its broker stop")))
                .isTrue();
        assertThat(TelegramCommands.incomplete(List.of("the worker has not started"))).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** A JSON body (the command bot) or a form body (the alert sender) as one object. */
    private static JsonNode parse(String body) {
        if (body.isBlank()) {
            return JSON.createObjectNode();
        }
        if (body.trim().startsWith("{")) {
            return JSON.readTree(body);
        }
        ObjectNode out = JSON.createObjectNode();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static long card(String text) {
        String out = deliver(message(ALICE_CHAT, ALICE_CHAT, "private", text, 0));
        assertThat(out).startsWith("CARD T");
        return Long.parseLong(out.substring(6));
    }

    /** Through the reader's own store, then the commands (as the poll does). */
    private static String deliver(ObjectNode update) {
        assertThat(gateway.store(update)).isTrue();
        return commands.handle(update);
    }

    private static ObjectNode message(long chat, long from, String type, String text, long ageSeconds) {
        ObjectNode u = JSON.createObjectNode().put("update_id", ++updateId);
        ObjectNode m = u.putObject("message").put("message_id", updateId).put("text", text)
                .put("date", Instant.now().getEpochSecond() - ageSeconds);
        m.putObject("chat").put("id", chat).put("type", type);
        m.putObject("from").put("id", from).put("is_bot", false);
        return u;
    }

    private static ObjectNode callback(long chat, long from, String data) {
        ObjectNode u = JSON.createObjectNode().put("update_id", ++updateId);
        ObjectNode q = u.putObject("callback_query").put("id", "cb" + updateId).put("data", data);
        q.putObject("from").put("id", from);
        ObjectNode m = q.putObject("message").put("message_id", 1).put("date", Instant.now().getEpochSecond());
        m.putObject("chat").put("id", chat).put("type", "private");
        return u;
    }

    private static List<JsonNode> sent(String method) {
        synchronized (calls) {
            return calls.stream().filter(e -> e.getKey().equals(method)).map(Map.Entry::getValue).toList();
        }
    }

    private static JsonNode lastCall(String method) {
        List<JsonNode> all = sent(method);
        return all.isEmpty() ? null : all.getLast();
    }

    private static String lastSent() {
        JsonNode last = lastCall("sendMessage");
        return last == null ? null : last.path("text").asString();
    }

    private static int commandCount() {
        return jdbc.queryForObject("select count(*) from exec.telegram_command", Integer.class);
    }

    private static String status(long id) {
        return jdbc.queryForObject("select status from exec.telegram_command where id = ?", String.class, id);
    }

    private static List<String> engaged() {
        return jdbc.queryForList("select scope || ' ' || engaged_by from exec.kill_switch where released_at is null",
                String.class);
    }
}
