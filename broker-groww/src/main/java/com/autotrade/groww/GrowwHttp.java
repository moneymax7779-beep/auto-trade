package com.autotrade.groww;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.autotrade.broker.BrokerException.Kind;
import com.autotrade.broker.RateLimiter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Groww's Trade API over plain HTTP (official docs groww.in/trade-api/docs/curl; SDK growwapi 1.5.0): headers
 * {@code Authorization: Bearer <access token>}, {@code Accept: application/json}, {@code X-API-VERSION: 1.0} and
 * {@code Content-Type: application/json} on a POST; answers {@code {"status": "SUCCESS", "payload": ...}} or
 * {@code {"status": "FAILURE", "error": {"code": "GA...", "message", "metadata"}}}.
 * <p>
 * Errors onto {@link Kind} ({@link #kind}): HTTP 401 and 403 are SESSION (403 is taken to be the static-IP or
 * authorisation refusal: UNCONFIRMED, {@link #STATIC_IP_MESSAGE}); GA005 (not authorised) is SESSION too (UNCONFIRMED);
 * HTTP 429 is RETRYABLE (never reached Groww); GA000 (internal), GA003 (unavailable), HTTP 5xx, a timeout or a dropped
 * connection are UNKNOWN for an order call (it may have been processed: look it up by reference) and RETRYABLE for a read;
 * GA001 (bad request), GA004 (not found), GA006 (cannot process), GA007 (duplicate reference) and anything else Groww
 * refuses are TERMINAL (the gateway adopts a GA007 place by reference). A refused connection never reached Groww. The
 * token and request or response bodies are never logged or put in a message.
 * <p>
 * Reads are throttled here ({@link GrowwConfig#READS_PER_SECOND} a second and {@link GrowwConfig#READS_PER_MINUTE} a
 * minute: Groww allows 20 and 500); order calls by the gateway's own limiter.
 */
public final class GrowwHttp {

    /** A read (list, status, detail, trades, positions, margins: safe to retry) or an order call. */
    public enum Call { READ, ORDER }

    // Groww's error codes (docs' error table)
    static final String INTERNAL = "GA000";
    static final String BAD_REQUEST = "GA001";
    static final String UNAVAILABLE = "GA003";
    static final String NOT_FOUND = "GA004";
    static final String NOT_AUTHORISED = "GA005";
    static final String CANNOT_PROCESS = "GA006";
    static final String DUPLICATE_REFERENCE = "GA007";
    private static final Set<String> SESSION_CODES = Set.of(NOT_AUTHORISED);
    private static final Set<String> MAYBE_PROCESSED_CODES = Set.of(INTERNAL, UNAVAILABLE);
    /** What an HTTP 403 is taken to mean (UNCONFIRMED: Groww's docs do not describe the static-IP refusal). */
    static final String STATIC_IP_MESSAGE = "Groww refused the request (HTTP 403): a static IP or authorisation refusal. "
            + "Orders must come from the static IP registered for this Groww client; check the IP and the API "
            + "subscription, then log in again";

    // paths (docs' curl examples)
    static final String PLACE = "/v1/order/create";
    static final String MODIFY = "/v1/order/modify";
    static final String CANCEL = "/v1/order/cancel";
    static final String LIST = "/v1/order/list";
    static final String STATUS_BY_REFERENCE = "/v1/order/status/reference/";
    static final String DETAIL = "/v1/order/detail/";
    static final String TRADES = "/v1/order/trades/";
    static final String POSITIONS = "/v1/positions/user";
    static final String MARGINS = "/v1/margins/detail/user";
    /** The instrument CSV (public). */
    static final String INSTRUMENTS = "/instruments/instrument.csv";
    static final String SEGMENT = "FNO";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Groww's timestamps are IST without a zone (UNCONFIRMED: "2026-11-09T09:15:47" in examples; others tried). */
    private static final List<DateTimeFormatter> TIMES = List.of(pattern("yyyy-MM-dd'T'HH:mm:ss"),
            pattern("yyyy-MM-dd HH:mm:ss"), pattern("dd-MM-yyyy HH:mm:ss"), pattern("dd-MMM-yyyy HH:mm:ss"),
            pattern("yyyy/MM/dd HH:mm:ss"));

    private final GrowwConfig config;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Limiter reads = new Limiter(GrowwConfig.READS_PER_SECOND, GrowwConfig.READS_PER_MINUTE,
            Duration.ofMinutes(1));
    private volatile String token;

    public GrowwHttp(GrowwConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    /** The day's access token (from the member's own Groww login); never logged. */
    public void session(String accessToken) {
        this.token = accessToken;
    }

    public GrowwConfig config() {
        return config;
    }

    ObjectNode object() {
        return json.createObjectNode();
    }

    /** A read ({@code pathAndQuery} already encoded), throttled. */
    JsonNode get(String pathAndQuery) {
        reads.acquire();
        return send(request(config.apiBase(), pathAndQuery).GET(), Call.READ);
    }

    /** An order call: POST with a JSON body. */
    JsonNode post(String path, ObjectNode body) {
        return send(request(config.apiBase(), path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))), Call.ORDER);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * The instrument CSV: a plain GET without the token (the file is public), only from HTTPS on Groww's domain or the
     * local simulator.
     */
    byte[] instrumentFile() {
        URI uri = URI.create(config.assetsBase() + INSTRUMENTS);
        if (!GrowwConfig.trustedBase(uri)) {
            throw new GrowwException(Kind.TERMINAL, "instrument file not on Groww's domain: " + uri.getHost(), 0, "",
                    false, null);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(config.timeout().multipliedBy(12)).GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new GrowwException(kind(response.statusCode(), "", Call.READ), "GET instrument file: HTTP "
                        + response.statusCode(), response.statusCode(), "", true, null);
            }
            return response.body();
        } catch (IOException e) {
            throw new GrowwException(Kind.RETRYABLE, "GET instrument file: " + e.getClass().getSimpleName(), 0, "", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GrowwException(Kind.UNKNOWN, "interrupted", 0, "", true, e);
        }
    }

    private HttpRequest.Builder request(URI base, String pathAndQuery) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(config.timeout())
                .header("Accept", "application/json").header("X-API-VERSION", "1.0");
        String t = token;
        if (t != null && !t.isBlank()) {
            b.header("Authorization", "Bearer " + t);
        }
        return b;
    }

    private JsonNode send(HttpRequest.Builder builder, Call call) {
        HttpRequest request = builder.build();
        String what = request.method() + " " + request.uri().getPath();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new GrowwException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + " timed out", 0, "", true,
                    e);
        } catch (ConnectException e) {
            throw new GrowwException(Kind.RETRYABLE, "cannot connect to " + request.uri().getHost(), 0, "", false, e);
        } catch (IOException e) {
            throw new GrowwException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + ": "
                    + e.getClass().getSimpleName(), 0, "", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GrowwException(Kind.UNKNOWN, "interrupted", 0, "", true, e);
        }
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException e) {
            body = null;
        }
        int code = response.statusCode();
        String status = body == null ? "" : body.path("status").asString("");
        if (code >= 200 && code < 300 && body != null && body.isObject() && status.equalsIgnoreCase("SUCCESS")) {
            return body;
        }
        if ((body == null || !body.isObject() || status.isEmpty()) && code >= 200 && code < 300) {
            // not Groww's envelope (a proxy's page, a cut-off body): the call may have been processed
            throw new GrowwException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + ": HTTP " + code
                    + " without Groww's answer", code, "", true, null);
        }
        JsonNode error = body == null ? null : body.path("error");
        String errorCode = error == null ? "" : error.path("code").asString("").trim();
        String message = error == null ? "" : error.path("message").asString("");
        Kind kind = kind(code, errorCode, call);
        boolean reached = code != 429;
        throw new GrowwException(kind, what + ": " + explain(code, errorCode, message), code, errorCode, reached, null);
    }

    /** Groww's answer onto {@link Kind}; HTTP 401 / 403 / 429 first, then Groww's code, then the HTTP class. */
    static Kind kind(int httpCode, String errorCode, Call call) {
        String c = errorCode == null ? "" : errorCode.trim().toUpperCase(Locale.ROOT);
        if (httpCode == 401 || httpCode == 403 || SESSION_CODES.contains(c)) {
            return Kind.SESSION;
        }
        if (httpCode == 429) {
            return Kind.RETRYABLE;
        }
        if (MAYBE_PROCESSED_CODES.contains(c) || httpCode >= 500) {
            return call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE;
        }
        return Kind.TERMINAL;
    }

    private static String explain(int httpCode, String errorCode, String message) {
        String m = message == null || message.isBlank() ? "" : message.length() > 200 ? message.substring(0, 200) : message;
        String prefix;
        if (httpCode == 403) {
            prefix = STATIC_IP_MESSAGE + (errorCode.isEmpty() ? "" : " (" + errorCode + ")");
        } else if (httpCode == 401) {
            prefix = "Groww token invalid or expired (HTTP 401" + (errorCode.isEmpty() ? "" : ", " + errorCode)
                    + "): log in again";
        } else if (httpCode == 429) {
            prefix = "Groww rate limit (HTTP 429)";
        } else if (errorCode.equals(DUPLICATE_REFERENCE)) {
            prefix = "Groww: duplicate order reference id (GA007)";
        } else if (errorCode.equals(NOT_AUTHORISED)) {
            prefix = "Groww: not authorised (GA005): log in again (and check the API subscription)";
        } else {
            prefix = !errorCode.isEmpty() ? "Groww " + errorCode : "HTTP " + httpCode;
        }
        return m.isEmpty() ? prefix : prefix + ": " + m;
    }

    /** A number as Groww sends it (JSON number, or a string); blank or missing = {@code otherwise}. */
    static double number(JsonNode n, double otherwise) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return otherwise;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        return number(n.asString(""), otherwise);
    }

    static double number(String raw, double otherwise) {
        String s = raw == null ? "" : raw.trim().replace(",", "");
        if (s.isEmpty() || s.equalsIgnoreCase("null")) {
            return otherwise;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    static long whole(JsonNode n) {
        return Math.round(number(n, 0));
    }

    /** Groww's timestamps (IST unless they carry an offset; see {@link #TIMES}); blank or unknown = null. */
    static Instant time(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("null")) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            // no offset: IST
        }
        if (v.length() > 20 && v.charAt(19) == '.') {
            v = v.substring(0, 19);
        }
        for (DateTimeFormatter f : TIMES) {
            try {
                return LocalDateTime.parse(v, f).atZone(IST).toInstant();
            } catch (DateTimeParseException e) {
                // the next format
            }
        }
        return null;
    }

    private static DateTimeFormatter pattern(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
    }

    /**
     * At most {@code perSecond} permits in any rolling second and {@code perWindow} in any rolling {@code window} (Groww
     * limits both a second and a minute). Callers wait, one at a time.
     */
    static final class Limiter {

        private final RateLimiter second;
        private final long[] stamps;
        private final long windowNanos;
        private int next;

        Limiter(int perSecond, int perWindow, Duration window) {
            this.second = new RateLimiter(perSecond);
            this.stamps = new long[perWindow];
            this.windowNanos = window.toNanos();
            java.util.Arrays.fill(stamps, Long.MIN_VALUE);
        }

        synchronized void acquire() {
            second.acquire();
            long now = System.nanoTime();
            long oldest = stamps[next];
            long wait = oldest == Long.MIN_VALUE ? 0 : oldest + windowNanos - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                now = System.nanoTime();
            }
            stamps[next] = now;
            next = (next + 1) % stamps.length;
        }
    }
}
