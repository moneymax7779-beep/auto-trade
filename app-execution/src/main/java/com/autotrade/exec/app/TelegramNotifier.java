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
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Messages to the owner's Telegram chat ({@code TELEGRAM_BOT_TOKEN} / {@code TELEGRAM_CHAT_ID} in the file trading-core
 * uses). No file or no settings: the message is logged instead. A failed send is logged, never thrown; the bot token is
 * never logged.
 */
@Component
class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final Path file;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    TelegramNotifier(KiteLoginProperties props) {
        this.file = Path.of(props.telegramFile());
    }

    void send(String text) {
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
            String token = settings.get("TELEGRAM_BOT_TOKEN"), chat = settings.get("TELEGRAM_CHAT_ID");
            if (token == null || chat == null) {
                log.info("no Telegram settings in {}: {}", file, text);
                return;
            }
            String body = "chat_id=" + URLEncoder.encode(chat, StandardCharsets.UTF_8) + "&text="
                    + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&disable_web_page_preview=true";
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token
                    + "/sendMessage")).timeout(Duration.ofSeconds(15)).header("Content-Type",
                    "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("Telegram message not delivered: HTTP {}", r.statusCode());
            }
        } catch (Exception e) {
            log.warn("Telegram message not delivered: {}", e.getClass().getSimpleName());
        }
    }
}
