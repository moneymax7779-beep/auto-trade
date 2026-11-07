package com.autotrade.exec.app;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Telegram messages ({@code TELEGRAM_BOT_TOKEN} / {@code TELEGRAM_CHAT_ID} in the file trading-core uses):
 * <ul>
 *   <li>{@link #send}: to the platform admin's chat ({@code TELEGRAM_CHAT_ID}), as before;</li>
 *   <li>{@link #sendToUser} and {@link #sendToMembers} (trader workspaces, 7 Nov 2026): to a person's own chat, linked
 *       with a one-time code ({@code acct.app_user.telegram_chat_id}); a person without a linked chat is skipped (the
 *       caller decides whether the admin hears instead);</li>
 *   <li>{@link #startMessages}: the {@code /start <code>} messages the bot holds, read without an offset, so nothing is
 *       consumed for anyone else reading the same bot.</li>
 * </ul>
 * No file or no settings: the message is logged instead. A failed send is logged, never thrown; the bot token is never
 * logged.
 */
@Component
class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A {@code /start <code>} message to the bot: who sent it (their chat) and the code. */
    record StartMessage(String chatId, String code) {
    }

    private final Path file;
    private final JdbcTemplate jdbc;                 // null: no database (tests): only the admin chat
    private final String api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    TelegramNotifier(KiteLoginProperties props) {
        this(props, null, "https://api.telegram.org");
    }

    @Autowired
    TelegramNotifier(KiteLoginProperties props, DataSource dataSource) {
        this(props, dataSource, "https://api.telegram.org");
    }

    TelegramNotifier(KiteLoginProperties props, DataSource dataSource, String api) {
        this.file = Path.of(props.telegramFile());
        this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
        this.api = api.replaceAll("/+$", "");
    }

    /** To the platform admin's chat. */
    void send(String text) {
        Map<String, String> settings = settings();
        String chat = settings.get("TELEGRAM_CHAT_ID");
        if (settings.get("TELEGRAM_BOT_TOKEN") == null || chat == null) {
            log.info("no Telegram settings in {}: {}", file, text);
            return;
        }
        deliver(settings.get("TELEGRAM_BOT_TOKEN"), chat, text);
    }

    /** To one person's linked chat; false when they have none (or the bot is not set up). */
    boolean sendToUser(long appUserId, String text) {
        if (jdbc == null) {
            return false;
        }
        String chat = jdbc.queryForList("select telegram_chat_id from acct.app_user where id = ? and status = 'ACTIVE' "
                + "and telegram_chat_id is not null", String.class, appUserId).stream().findFirst().orElse(null);
        String token = settings().get("TELEGRAM_BOT_TOKEN");
        if (chat == null || token == null) {
            return false;
        }
        return deliver(token, chat, text);
    }

    /**
     * To every member of a workspace with a linked chat (OWNER, TRADER, VIEWER, AUDITOR; never SUPPORT or ADMIN, who have
     * the admin chat). Returns how many were sent.
     */
    int sendToMembers(long tenantId, String text) {
        if (jdbc == null) {
            return 0;
        }
        int sent = 0;
        for (long user : jdbc.queryForList("select m.app_user_id from acct.tenant_membership m where m.tenant_id = ? "
                + "and m.role in ('OWNER', 'TRADER', 'VIEWER', 'AUDITOR') order by m.app_user_id", Long.class, tenantId)) {
            if (sendToUser(user, text)) {
                sent++;
            }
        }
        return sent;
    }

    /** True when the bot is set up (a token in the file). */
    boolean configured() {
        return settings().get("TELEGRAM_BOT_TOKEN") != null;
    }

    /** The bot's username (for a t.me link), or null when it cannot be read. */
    String botUsername() {
        String token = settings().get("TELEGRAM_BOT_TOKEN");
        if (token == null) {
            return null;
        }
        try {
            JsonNode me = get(token, "getMe");
            String name = me == null ? "" : me.path("result").path("username").asString("");
            return name.isEmpty() ? null : name;
        } catch (Exception e) {
            log.warn("Telegram getMe failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The {@code /start <code>} messages the bot holds (Telegram keeps them 24 h), private chats only. Read without an
     * offset: nothing is marked as read. Empty when the bot has a webhook (Telegram then refuses getUpdates) or cannot be
     * reached.
     */
    List<StartMessage> startMessages() {
        String token = settings().get("TELEGRAM_BOT_TOKEN");
        List<StartMessage> out = new ArrayList<>();
        if (token == null) {
            return out;
        }
        try {
            JsonNode updates = get(token, "getUpdates?allowed_updates=%5B%22message%22%5D");
            if (updates == null) {
                return out;
            }
            for (JsonNode u : updates.path("result")) {
                JsonNode message = u.path("message");
                String text = message.path("text").asString("");
                JsonNode chat = message.path("chat");
                if (text.startsWith("/start ") && "private".equals(chat.path("type").asString(""))
                        && chat.path("id").isNumber()) {
                    out.add(new StartMessage(chat.path("id").toString(), text.substring(7).trim()));
                }
            }
        } catch (Exception e) {
            log.warn("Telegram getUpdates failed: {}", e.getClass().getSimpleName());
        }
        return out;
    }

    private JsonNode get(String token, String method) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api + "/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            log.warn("Telegram {} refused: HTTP {}", method.replaceAll("\\?.*", ""), r.statusCode());
            return null;
        }
        return JSON.readTree(r.body());
    }

    private boolean deliver(String token, String chat, String text) {
        try {
            String body = "chat_id=" + URLEncoder.encode(chat, StandardCharsets.UTF_8) + "&text="
                    + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&disable_web_page_preview=true";
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api + "/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("Telegram message not delivered: HTTP {}", r.statusCode());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Telegram message not delivered: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file)) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && !line.trim().startsWith("#")) {
                        settings.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Telegram settings not readable: {}", e.getClass().getSimpleName());
        }
        return settings;
    }
}
