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
        sender.submit(() -> deliver(text));
    }

    private void deliver(String text) {
        Map<String, String> settings = settings();
        String token = settings.get("TELEGRAM_BOT_TOKEN");
        String chat = settings.get("TELEGRAM_CHAT_ID");
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
                log.warn("telegram alert not delivered: HTTP {}", response.statusCode());   // body may echo details; not logged
            }
        } catch (IOException e) {
            log.warn("telegram alert not delivered: {}", e.getClass().getSimpleName());
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
