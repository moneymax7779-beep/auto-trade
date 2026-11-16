package com.autotrade.exec.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Bot API calls the command gateway needs (Telegram controls T0 + T1, 16 Nov 2026): long-poll {@code getUpdates}
 * with an offset, send and edit a message with inline buttons, answer a button tap, read the webhook. The token is
 * asked for at each call (the file can be rotated without a restart) and never logged; a failed call is logged by its
 * method and HTTP status only. A 429 is retried once after Telegram's {@code retry_after} (at most 5 s), so an alert
 * burst on the same bot does not swallow a command's answer.
 */
class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One inline button: its label and the callback data sent back on a tap (at most 64 bytes). */
    record Button(String text, String data) {
    }

    /** A getUpdates answer: HTTP status (0: not reached), the updates (empty unless 200) and a short error. */
    record Polled(int status, List<JsonNode> updates, String error) {
    }

    private final Supplier<String> token;
    private final String api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    TelegramBot(Supplier<String> token, String api) {
        this.token = token;
        this.api = api.replaceAll("/+$", "");
    }

    boolean configured() {
        return token.get() != null;
    }

    /** Long poll: waits up to {@code timeoutSeconds} for updates after {@code offset - 1} (and so uses those up). */
    Polled getUpdates(long offset, int timeoutSeconds) {
        String t = token.get();
        if (t == null) {
            return new Polled(0, List.of(), "no bot token");
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api + "/bot" + t + "/getUpdates?offset="
                            + offset + "&timeout=" + timeoutSeconds
                            + "&allowed_updates=%5B%22message%22%2C%22callback_query%22%5D"))
                    .timeout(Duration.ofSeconds(timeoutSeconds + 10L)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return new Polled(r.statusCode(), List.of(), "HTTP " + r.statusCode());
            }
            List<JsonNode> updates = new java.util.ArrayList<>();
            JSON.readTree(r.body()).path("result").forEach(updates::add);
            return new Polled(200, updates, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Polled(0, List.of(), "interrupted");
        } catch (Exception e) {
            return new Polled(0, List.of(), e.getClass().getSimpleName());
        }
    }

    /** Telegram refuses a text over 4,096 characters (HTTP 400: the person would get nothing at all). */
    static final int MAX_TEXT = 4096;

    static String fit(String text) {
        String t = text == null || text.isEmpty() ? "-" : text;
        return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT - 40) + "\n… (cut: the rest is on the web)";
    }

    /** Sends a message (buttons: rows of buttons, or null); returns its message id, or null when not delivered. */
    Long send(String chat, String text, List<List<Button>> buttons) {
        ObjectNode body = JSON.createObjectNode().put("chat_id", chat).put("text", fit(text))
                .put("disable_web_page_preview", true);
        if (buttons != null) {
            body.set("reply_markup", keyboard(buttons));
        }
        JsonNode r = call("sendMessage", body);
        return r == null || !r.path("result").path("message_id").isNumber() ? null
                : r.path("result").path("message_id").asLong();
    }

    /** Replaces a message's text and buttons (null: no buttons left). */
    boolean edit(String chat, long messageId, String text, List<List<Button>> buttons) {
        ObjectNode body = JSON.createObjectNode().put("chat_id", chat).put("message_id", messageId).put("text", fit(text))
                .put("disable_web_page_preview", true);
        body.set("reply_markup", keyboard(buttons == null ? List.of() : buttons));
        return call("editMessageText", body) != null;
    }

    /** Ends the spinner on a tapped button, with a short notice. */
    void answer(String callbackId, String text) {
        call("answerCallbackQuery", JSON.createObjectNode().put("callback_query_id", callbackId).put("text", text));
    }

    /** The webhook URL set on the bot ("" none), or null when it cannot be read. */
    String webhookUrl() {
        JsonNode r = call("getWebhookInfo", JSON.createObjectNode());
        return r == null ? null : r.path("result").path("url").asString("");
    }

    private static ObjectNode keyboard(List<List<Button>> rows) {
        ObjectNode markup = JSON.createObjectNode();
        ArrayNode keyboard = markup.putArray("inline_keyboard");
        for (List<Button> row : rows) {
            ArrayNode r = keyboard.addArray();
            for (Button b : row) {
                r.addObject().put("text", b.text()).put("callback_data", b.data());
            }
        }
        return markup;
    }

    private JsonNode call(String method, ObjectNode body) {
        String t = token.get();
        if (t == null) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api + "/bot" + t + "/" + method))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return JSON.readTree(r.body());
                }
                if (r.statusCode() == 429 && attempt == 0) {
                    long wait = Math.min(5, Math.max(1, JSON.readTree(r.body()).path("parameters").path("retry_after")
                            .asLong(1)));
                    Thread.sleep(wait * 1000);
                    continue;
                }
                log.warn("Telegram {} refused: HTTP {}", method, r.statusCode());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("Telegram {} failed: {}", method, e.getClass().getSimpleName());
                return null;
            }
        }
        return null;
    }
}
