package com.autotrade.exec.app;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.autotrade.execution.KillSwitches;

import tools.jackson.databind.JsonNode;

/**
 * The command bot's reader (Telegram controls T0, 16 Nov 2026): one thread long-polls {@code getUpdates} with an offset,
 * keeps every update in exec.telegram_update first (Telegram's update_id is the key, so a repeat is dropped) and hands the
 * new ones to {@link TelegramCommands}. Only where {@code autotrade.execution.telegram.commands} is on: Telegram lets a
 * single reader use a bot's updates.
 * <p>
 * <b>Health</b>, because a silent reader is worse than none (a {@code /kill} that never arrives looks like one that
 * did): a 409 (another reader of the same token, or a webhook someone set) and a webhook URL on the bot raise the alarm
 * at once; no successful poll for {@link #STALE} on a trading day between 09:00 and 15:35 IST raises it too. The alarm
 * goes to a second bot ({@code alarm-file}) when there is one, since the command bot is the thing in doubt, else to the
 * command bot's admin chat, and always to the log and the audit log ({@code TELEGRAM_POLL_ALARM}); once per kind every
 * {@link #ALARM_EVERY}.
 * <p>
 * At start, updates stored but not handled (the worker stopped in between) are marked skipped, never run late.
 */
@Component
class TelegramGateway implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TelegramGateway.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Duration STALE = Duration.ofSeconds(60);
    static final Duration ALARM_EVERY = Duration.ofMinutes(10);
    private static final int POLL_SECONDS = 25;

    private final JdbcTemplate jdbc;
    private final TelegramCommands commands;
    private final TelegramCommandProperties props;
    private final TelegramNotifier notifier;
    private final TelegramNotifier alarmBot;              // null: no second bot
    private final TradingDays tradingDays;
    private final String workerId;
    private final Map<String, Instant> alarmed = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Instant lastOk;
    private volatile String lastError;
    private volatile Instant webhookCheckedAt = Instant.EPOCH;
    private long offset;
    /** When the newest update was received (null: none kept); after {@link #QUIET} Telegram may restart its ids. */
    private volatile Instant lastUpdateAt;
    /** The bot the offset belongs to (its token's id). */
    private Long botId;
    /**
     * Review 16 Nov 2026 (D8): Telegram picks the next update_id at random after a week without updates; one below our
     * offset would be confirmed without being returned (a command lost, the poller healthy). Past this quiet, or with
     * another bot, the reader reads once without an offset (the update_id table drops repeats).
     */
    static final Duration QUIET = Duration.ofDays(6);
    static final String NO_TOKEN = "Telegram commands are ON on this worker, but bot.env has no command bot token: nothing "
            + "reads your commands. Use the web or the broker app; put the token in bot.env and restart.";

    TelegramGateway(DataSource dataSource, TelegramCommands commands, TelegramCommandProperties props,
                    TelegramNotifier notifier, TradingDays tradingDays, ExecutionProperties execution) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.commands = commands;
        this.props = props;
        this.notifier = notifier;
        this.alarmBot = props.alarmFile() == null ? null : new TelegramNotifier(new KiteLoginProperties(null, null, null,
                null, null, props.alarmFile()), null, notifier.api());
        this.tradingDays = tradingDays;
        this.workerId = execution.workerId();
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!props.commands()) {
            log.info("Telegram commands off on this worker (autotrade.execution.telegram.commands)");
            return;
        }
        if (!commands.bot().configured()) {
            // review 16 Nov 2026 (D9): only a log line, so commands looked on while nothing read them; health() repeats it
            alarm("NO_TOKEN", NO_TOKEN);
            return;
        }
        prepare();
        running = true;
        thread = Thread.ofPlatform().name("telegram-poll").daemon(true).start(this::loop);
        log.info("Telegram commands on: reading the bot from update {}", offset);
    }

    /** The offset to read from, the restart rules applied (separate from the thread, for tests). */
    void prepare() {
        int skipped = jdbc.update("update exec.telegram_update set processed_at = now(), outcome = 'SKIPPED_RESTART' "
                + "where processed_at is null");
        if (skipped > 0) {
            log.warn("Telegram: {} update(s) stored but not handled before the restart: skipped", skipped);
        }
        commands.recover();
        Long max = jdbc.queryForObject("select max(update_id) from exec.telegram_update", Long.class);
        offset = max == null ? 0 : max + 1;
        java.sql.Timestamp newest = jdbc.queryForObject("select max(received_at) from exec.telegram_update",
                java.sql.Timestamp.class);
        lastUpdateAt = newest == null ? null : newest.toInstant();
        List<Long> stored = jdbc.queryForList("select bot_id from exec.telegram_poller where id = 1", Long.class);
        Long known = stored.isEmpty() ? null : stored.getFirst();
        botId = commands.bot().botId();
        if (known != null && botId != null && !known.equals(botId)) {
            log.warn("Telegram: the command bot changed ({} -> {}): reading it from its first unconfirmed update", known,
                    botId);
            offset = 0;
        }
        saveBot();
    }

    private void saveBot() {
        jdbc.update("insert into exec.telegram_poller (id, worker_id, bot_id) values (1, ?, ?) on conflict (id) do update "
                + "set bot_id = excluded.bot_id", workerId, botId);
    }

    private void loop() {
        while (running) {
            boolean ok;
            try {
                ok = pollOnce(POLL_SECONDS);
            } catch (Throwable e) {
                // the database (or anything else) failed: the thread must live on, or every later /kill is lost unheard
                // (review 16 Nov 2026, D9: an Error, not only a RuntimeException, ended it for good)
                lastError = e.getClass().getSimpleName();
                log.error("Telegram reader: poll failed, trying again in 5 s", e);
                ok = false;
            }
            if (!ok) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * One getUpdates and the handling of what it brought; false when the poll failed. The offset moves past an update
     * only once it is stored: when the database fails, Telegram is not told it was read and sends it again at the next
     * poll (its age then decides whether it still runs). Throws when the database fails.
     */
    boolean pollOnce(int timeoutSeconds) {
        Long bot = commands.bot().botId();
        if (bot != null && !bot.equals(botId)) {                     // bot.env pointed at another bot meanwhile
            log.warn("Telegram: the command bot changed ({} -> {}): reading it from its first unconfirmed update", botId,
                    bot);
            botId = bot;
            offset = 0;
            saveBot();
        }
        Instant newest = lastUpdateAt;
        if (offset > 0 && newest != null && newest.isBefore(Instant.now().minus(QUIET))) {
            log.info("Telegram: no update for {} days: reading without an offset (its next id may be lower)",
                    Duration.between(newest, Instant.now()).toDays());
            offset = 0;
        }
        TelegramBot.Polled polled = commands.bot().getUpdates(offset, timeoutSeconds);
        if (polled.status() != 200) {
            failed(polled.status(), polled.error());
            return false;
        }
        lastOk = Instant.now();
        for (JsonNode update : polled.updates()) {
            long id = update.path("update_id").asLong(-1);
            if (id < 0) {
                continue;
            }
            boolean fresh = store(update);                          // false: kept before (a repeat)
            offset = Math.max(offset, id + 1);
            lastUpdateAt = Instant.now();
            if (fresh) {
                String outcome = commands.handle(update);
                jdbc.update("update exec.telegram_update set processed_at = now(), outcome = ? where update_id = ?",
                        outcome.length() > 200 ? outcome.substring(0, 200) : outcome, id);
            }
        }
        jdbc.update("insert into exec.telegram_poller (id, worker_id, last_ok_at) values (1, ?, now()) on conflict (id) "
                + "do update set worker_id = excluded.worker_id, last_ok_at = excluded.last_ok_at", workerId);
        return true;
    }

    /** Keeps one update; false when it was already kept (a repeat after a restart). */
    boolean store(JsonNode u) {
        JsonNode message = u.has("message") ? u.path("message") : u.path("callback_query").path("message");
        JsonNode from = u.has("message") ? message.path("from") : u.path("callback_query").path("from");
        String kind = u.has("message") ? "MESSAGE" : u.has("callback_query") ? "CALLBACK" : "OTHER";
        String text = kind.equals("MESSAGE") ? message.path("text").asString(null)
                : kind.equals("CALLBACK") ? u.path("callback_query").path("data").asString(null) : null;
        java.sql.Timestamp sent = kind.equals("MESSAGE") && message.path("date").isNumber()
                ? java.sql.Timestamp.from(Instant.ofEpochSecond(message.path("date").asLong())) : null;
        return jdbc.update("insert into exec.telegram_update (update_id, sent_at, from_user_id, chat_id, chat_type, kind, "
                        + "text) values (?, ?, ?, ?, ?, ?, ?) on conflict (update_id) do nothing",
                u.path("update_id").asLong(), sent, from.path("id").isNumber() ? from.path("id").asLong() : null,
                message.path("chat").path("id").isNumber() ? message.path("chat").path("id").asLong() : null,
                message.path("chat").path("type").asString(null), kind,
                text == null ? null : text.length() > 4096 ? text.substring(0, 4096) : text) > 0;
    }

    private void failed(int status, String error) {
        lastError = error;
        jdbc.update("insert into exec.telegram_poller (id, worker_id, last_error, last_error_at) values (1, ?, ?, now()) "
                + "on conflict (id) do update set worker_id = excluded.worker_id, last_error = excluded.last_error, "
                + "last_error_at = excluded.last_error_at", workerId, error);
        if (status == 409) {
            alarm("CONFLICT", "Telegram command bot: 409 Conflict. Another program is reading this bot's messages, or a "
                    + "webhook was set on it. Your Telegram commands may NOT be reaching auto-trade. Use the web or the "
                    + "broker app for anything urgent. If nothing of ours should be reading it, revoke the token in "
                    + "BotFather now.");
        } else if (status == 401 || status == 404) {
            alarm("TOKEN", "Telegram command bot: the token was refused (HTTP " + status + "). Commands are off until "
                    + "bot.env holds a valid token.");
        }
    }

    /** Every 15 s: no poll for {@link #STALE} in market hours, or a webhook on the bot → alarm. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 60_000)
    void health() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime t = now.toLocalTime();
        boolean market = tradingDays.isTradingDay(now.toLocalDate()) && !t.isBefore(LocalTime.of(9, 0))
                && t.isBefore(LocalTime.of(15, 35));
        if (!running) {
            if (props.commands() && !commands.bot().configured() && market) {
                alarm("NO_TOKEN", NO_TOKEN);
            }
            return;
        }
        Instant ok = lastOk;
        if (market && (ok == null || Duration.between(ok, Instant.now()).compareTo(STALE) > 0)) {
            alarm("STALE", "Telegram command bot: no successful read for " + (ok == null ? "ever"
                    : Duration.between(ok, Instant.now()).toSeconds() + " s")
                    + (lastError == null ? "" : " (" + lastError + ")") + ". Commands sent "
                    + "now are not being received. Use the web or the broker app.");
        }
        if (Duration.between(webhookCheckedAt, Instant.now()).compareTo(Duration.ofMinutes(5)) > 0) {
            webhookCheckedAt = Instant.now();
            checkWebhook();
        }
    }

    /** A webhook on the bot (we never set one) takes every command away from us: alarm. True when one is set. */
    boolean checkWebhook() {
        String url = commands.bot().webhookUrl();
        if (url != null && !url.isEmpty()) {
            alarm("WEBHOOK", "Telegram command bot: a webhook is set on it, so every command goes there instead of "
                    + "auto-trade. We never set one. Revoke the token in BotFather and put the new one in bot.env.");
            return true;
        }
        return false;
    }

    void alarm(String kind, String text) {
        Instant last = alarmed.get(kind);
        if (last != null && Duration.between(last, Instant.now()).compareTo(ALARM_EVERY) < 0) {
            return;
        }
        alarmed.put(kind, Instant.now());
        log.error("TELEGRAM POLL ALARM {}: {}", kind, text);
        try {
            new KillSwitches(jdbc.getDataSource()).audit(null, null, workerId, "TELEGRAM_POLL_ALARM", Map.of("kind", kind,
                    "text", text));
        } catch (RuntimeException e) {
            log.warn("telegram alarm not audited: {}", e.getMessage());
        }
        if (alarmBot != null && alarmBot.configured()) {
            alarmBot.send(text);
        } else {
            notifier.send(text);                                 // may be the very bot in trouble: better than nothing
        }
    }

    /** For the status page. */
    Map<String, Object> health(boolean detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commands", props.commands());
        out.put("polling", running);
        out.put("lastOk", lastOk == null ? null : lastOk.toString());
        if (detail) {
            out.put("lastError", lastError);
        }
        return out;
    }

    @Override
    public void destroy() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }
}
