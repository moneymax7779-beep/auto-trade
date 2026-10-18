package com.autotrade.zerodha;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.StringJoiner;

import com.autotrade.broker.BrokerException.Kind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kite Connect v3 over plain HTTP: form-encoded requests, the {@code X-Kite-Version: 3} header, the
 * {@code token api_key:access_token} authorisation, and the {@code {"status": "success" | "error", ...}} envelope.
 * Errors are mapped onto {@link Kind}: TokenException and PermissionException (also a non-whitelisted IP) are SESSION;
 * NetworkException, HTTP 429 and 5xx are RETRYABLE; InputException, OrderException, MarginException and other 4xx are
 * TERMINAL; a timeout or a dropped connection after the request was sent is UNKNOWN (the request may have reached the
 * broker), a refused connection RETRYABLE (it did not).
 */
public final class KiteHttp {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KiteConfig config;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile String accessToken;

    public KiteHttp(KiteConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    /** The day's access token (from {@link KiteAuth#exchange}); never logged. */
    public void accessToken(String token) {
        this.accessToken = token;
    }

    JsonNode get(String path, Map<String, String> query) {
        String q = query == null || query.isEmpty() ? "" : "?" + form(query);
        return send(request(path + q).GET());
    }

    /** GET with a repeated parameter (Kite's quote call: {@code ?i=NFO:A&i=NFO:B}). */
    JsonNode getRepeated(String path, String key, Iterable<String> values) {
        StringJoiner q = new StringJoiner("&", "?", "");
        values.forEach(v -> q.add(key + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return send(request(path + q).GET());
    }

    /** A plain-text GET (Kite's instrument CSV is not wrapped in the JSON envelope). */
    String getText(String path) {
        HttpRequest request = request(path).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new KiteException(kind(response.statusCode(), ""), "GET " + path + ": HTTP " + response.statusCode(),
                        response.statusCode(), "", true, null);
            }
            return response.body();
        } catch (IOException e) {
            throw new KiteException(Kind.RETRYABLE, "GET " + path + ": " + e.getMessage(), 0, "", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KiteException(Kind.UNKNOWN, "interrupted", 0, "", true, e);
        }
    }

    JsonNode post(String path, Map<String, String> form) {
        return send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(form))));
    }

    JsonNode put(String path, Map<String, String> form) {
        return send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString(form(form))));
    }

    JsonNode delete(String path) {
        return send(request(path).DELETE());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.apiBase() + path)).timeout(config.timeout())
                .header("X-Kite-Version", "3");
        String token = accessToken;
        if (token != null) {
            b.header("Authorization", "token " + config.apiKey() + ":" + token);
        }
        return b;
    }

    private JsonNode send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new KiteException(Kind.UNKNOWN, request.method() + " " + request.uri().getPath() + " timed out", 0, "",
                    true, e);
        } catch (ConnectException e) {
            throw new KiteException(Kind.RETRYABLE, "cannot connect to " + config.apiBase().getHost(), 0, "", false, e);
        } catch (IOException e) {
            throw new KiteException(Kind.UNKNOWN, request.method() + " " + request.uri().getPath() + ": " + e.getMessage(),
                    0, "", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KiteException(Kind.UNKNOWN, "interrupted", 0, "", true, e);
        }
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException e) {
            body = null;
        }
        int code = response.statusCode();
        if (code == 200 && body != null && "success".equals(body.path("status").asString(""))) {
            return body.path("data");
        }
        String type = body == null ? "" : body.path("error_type").asString("");
        String message = body == null ? "HTTP " + code : body.path("message").asString("HTTP " + code);
        throw new KiteException(kind(code, type), type.isEmpty() ? message : type + ": " + message, code, type, code != 429,
                null);
    }

    static Kind kind(int httpCode, String errorType) {
        return switch (errorType) {
            case "TokenException", "PermissionException" -> Kind.SESSION;
            case "NetworkException" -> Kind.RETRYABLE;
            case "InputException", "OrderException", "MarginException", "DataException" -> Kind.TERMINAL;
            default -> httpCode == 403 ? Kind.SESSION
                    : httpCode == 429 || httpCode >= 500 ? Kind.RETRYABLE : Kind.TERMINAL;
        };
    }

    private static String form(Map<String, String> values) {
        StringJoiner j = new StringJoiner("&");
        values.forEach((k, v) -> {
            if (v != null) {
                j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        return j.toString();
    }

    /** Kite's timestamps ("2026-10-19 09:15:02", IST); blank = null. */
    static Instant time(String value) {
        if (value == null || value.isBlank() || value.equals("null")) {
            return null;
        }
        String v = value.length() > 19 ? value.substring(0, 19) : value;
        return LocalDateTime.parse(v.replace('T', ' '), TIME).atZone(IST).toInstant();
    }

    JsonMapper json() {
        return json;
    }
}
