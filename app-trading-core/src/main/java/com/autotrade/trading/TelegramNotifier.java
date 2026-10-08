package com.autotrade.trading;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends alert text to one Telegram chat through the Bot API, on its own thread so trading never waits on it.
 * The bot token is read from a local file at each send (so it can be added or rotated without a restart)
 * and is never logged.
 */
final class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final Path file;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("telegram-alerts").daemon(true).unstarted(runnable));
    private volatile boolean warnedMissing;

    TelegramNotifier(Path file) {
        this.file = file;
    }

    /** True when the settings file holds a token and a chat id. */
    boolean configured() {
        Map<String, String> settings = settings();
        return settings.containsKey("TELEGRAM_BOT_TOKEN") && settings.containsKey("TELEGRAM_CHAT_ID");
    }

    void send(String text) {
        sender.submit(() -> deliver(text, "TELEGRAM_CHAT_ID"));
    }

    /**
     * Sends to the admin chat (TELEGRAM_CHAT_ID) and, when the file names one, the group chat (TELEGRAM_GROUP_ID):
     * the daily P&amp;L summary goes to both.
     */
    void sendToAdminAndGroup(String text) {
        sender.submit(() -> {
            deliver(text, "TELEGRAM_CHAT_ID");
            if (settings().containsKey("TELEGRAM_GROUP_ID")) {
                deliver(text, "TELEGRAM_GROUP_ID");
            }
        });
    }

    /** True when the settings file also names a group chat. */
    boolean groupConfigured() {
        return settings().containsKey("TELEGRAM_GROUP_ID");
    }

    private void deliver(String text, String chatKey) {
        Map<String, String> settings = settings();
        String token = settings.get("TELEGRAM_BOT_TOKEN");
        String chat = settings.get(chatKey);
        if (token == null || chat == null) {
            if (!warnedMissing) {
                log.info("telegram alerts off: {} has no TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID", file);
                warnedMissing = true;
            }
            return;
        }
        warnedMissing = false;
        String body = "chat_id=" + URLEncoder.encode(chat, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&disable_web_page_preview=true";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("telegram message to {} not delivered: HTTP {}", chatKey, response.statusCode());   // body not logged
            }
        } catch (IOException e) {
            log.warn("telegram message to {} not delivered: {}", chatKey, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, String> settings() {
        Map<String, String> values = new HashMap<>();
        try {
            if (!Files.isRegularFile(file)) {
                return values;
            }
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            log.warn("cannot read {}: {}", file, e.getClass().getSimpleName());
        }
        return values;
    }
}
