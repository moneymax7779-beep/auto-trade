package com.autotrade.upstox.orders;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.autotrade.broker.BrokerException.Kind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Upstox's API over plain HTTP (official docs upstox.com/developer/api-documentation, 2026; paths and field names as in
 * Upstox's own Java SDK 1.29): headers {@code Authorization: Bearer <token>}, {@code Accept: application/json},
 * {@code Content-Type: application/json} on a body, {@code Api-Version: 2.0} on the v2 reads (the SDK sends it) and
 * {@code 3.0} on the v3 funds call; answers {@code {"status": "success", "data": ...}} or
 * {@code {"status": "error", "errors": [{"errorCode"|"error_code": "UDAPI...", "message": ...}]}}.
 * <p>
 * Errors onto {@link Kind} ({@link #kind}): an invalid or expired token (UDAPI100050, HTTP 401) and a request from an IP
 * not registered for the Upstox user (UDAPI1154, HTTP 403) are SESSION; the rate limit (UDAPI10005, HTTP 429) and the
 * funds service's night window (UDAPI100072, HTTP 423) are RETRYABLE; market protection (UDAPI1158/1159/1160), an invalid
 * or restricted instrument (UDAPI100011, UDAPI100049), a modify or cancel of a finished order (UDAPI100040/100041), an
 * order not found (UDAPI100010) and an order outside the API's hours (UDAPI100074) are TERMINAL; HTTP 5xx, a timeout or a
 * dropped connection are UNKNOWN for an order call (it may have been processed: look it up by tag) and RETRYABLE for a
 * read; a refused connection never reached Upstox. Anything else Upstox refuses (field validation) is TERMINAL. The
 * token and request or response bodies are never logged or put in a message.
 */
public final class UpstoxHttp implements AutoCloseable {

    /** A read (book, history, trades, positions, funds: safe to retry) or an order call (place, modify, cancel). */
    public enum Call { READ, ORDER }

    // Upstox error codes (docs' error list and the 1 Apr 2026 mandate notes)
    static final String TOKEN_INVALID = "UDAPI100050";
    static final String STATIC_IP = "UDAPI1154";
    static final String RATE_LIMIT = "UDAPI10005";
    static final String FUNDS_DOWN = "UDAPI100072";
    static final String NOT_FOUND = "UDAPI100010";
    static final String OUTSIDE_HOURS = "UDAPI100074";
    static final String ORDER_FINISHED_CANCEL = "UDAPI100040";
    static final String ORDER_FINISHED_MODIFY = "UDAPI100041";
    static final String INVALID_INSTRUMENT = "UDAPI100011";
    static final String RESTRICTED = "UDAPI100049";
    static final Set<String> MARKET_PROTECTION = Set.of("UDAPI1158", "UDAPI1159", "UDAPI1160");
    private static final Set<String> SESSION_CODES = Set.of(TOKEN_INVALID, STATIC_IP);
    private static final Set<String> RETRYABLE_CODES = Set.of(RATE_LIMIT, FUNDS_DOWN);
    private static final Set<String> TERMINAL_CODES = Set.of(NOT_FOUND, OUTSIDE_HOURS, ORDER_FINISHED_CANCEL,
            ORDER_FINISHED_MODIFY, INVALID_INSTRUMENT, RESTRICTED, "UDAPI1158", "UDAPI1159", "UDAPI1160");

    // paths (SDK 1.29: OrderApiV3, OrderApi, PortfolioApi, UserApi)
    static final String PLACE = "/v3/order/place";
    static final String MODIFY = "/v3/order/modify";
    static final String CANCEL = "/v3/order/cancel";
    static final String ORDERS = "/v2/order/retrieve-all";
    static final String HISTORY = "/v2/order/history";
    static final String TRADES = "/v2/order/trades/get-trades-for-day";
    static final String POSITIONS = "/v2/portfolio/short-term-positions";
    /** v3 funds (data.available_to_trade.total: the SDK's model; UNCONFIRMED on a real account). */
    static final String FUNDS = "/v3/user/get-funds-and-margin";
    /** v2 funds (data.equity.available_margin; what the daily login reads): when v3 is missing or of another shape. */
    static final String FUNDS_V2 = "/v2/user/get-funds-and-margin";
    /** The day's instrument files (public; gzipped JSON arrays). */
    static final String INSTRUMENTS = "/market-quote/instruments/exchange/";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /**
     * Upstox's timestamp formats: "2026-11-09 09:15:47" (order_timestamp, docs); exchange_timestamp has been shown both so
     * and as "09-Nov-2026 09:15:47" (UNCONFIRMED); an unknown format reads as no time.
     */
    private static final List<DateTimeFormatter> TIMES = List.of(pattern("yyyy-MM-dd HH:mm:ss"),
            pattern("dd-MMM-yyyy HH:mm:ss"), pattern("dd-MM-yyyy HH:mm:ss"), pattern("yyyy/MM/dd HH:mm:ss"),
            pattern("dd/MM/yyyy HH:mm:ss"), pattern("yyyy-MM-dd'T'HH:mm:ss"));

    private final UpstoxConfig config;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile String token;
    private volatile java.util.function.BiConsumer<String, String> tap;

    public UpstoxHttp(UpstoxConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    /** Ends the client's connections and threads (a closed route). */
    @Override
    public void close() {
        client.close();
    }

    /** The day's access token (from the member's own Upstox login); never logged. */
    public void session(String accessToken) {
        this.token = accessToken;
    }

    public UpstoxConfig config() {
        return config;
    }

    /**
     * Sees every answer this client reads (READ_LIVE probe: the raw bodies become contract fixtures): called with
     * {@code "<METHOD> <path>?<query>"} and the response body, after the response arrives and before it is judged; never
     * the headers or the token. A tap that throws changes nothing about the call. Null = off.
     */
    public void tap(java.util.function.BiConsumer<String, String> tap) {
        this.tap = tap;
    }

    private void tapped(HttpRequest request, String body) {
        java.util.function.BiConsumer<String, String> t = tap;
        if (t == null) {
            return;
        }
        try {
            String query = request.uri().getRawQuery();
            t.accept(request.method() + " " + request.uri().getRawPath() + (query == null ? "" : "?" + query), body);
        } catch (RuntimeException e) {
            // a tap never affects the call
        }
    }

    ObjectNode object() {
        return json.createObjectNode();
    }

    /** A read on the read host ({@code pathAndQuery} already encoded). */
    JsonNode get(String pathAndQuery) {
        String version = pathAndQuery.startsWith("/v3/") ? "3.0" : "2.0";
        return send(request(config.readBase(), pathAndQuery).header("Api-Version", version).GET(), Call.READ);
    }

    /** An order call with a JSON body on the order host: POST (place) or PUT (modify). */
    JsonNode send(String method, String path, ObjectNode body) {
        return send(request(config.orderBase(), path).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))), Call.ORDER);
    }

    /** DELETE on the order host (cancel). */
    JsonNode delete(String pathAndQuery) {
        return send(request(config.orderBase(), pathAndQuery).DELETE(), Call.ORDER);
    }

    static String query(String name, String value) {
        return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * One of the day's instrument files ({@code NSE.json.gz}, {@code BSE.json.gz}): a plain GET without the token (the
     * files are public), only from HTTPS on Upstox's domain or the local simulator.
     */
    byte[] instrumentFile(String exchange) {
        return instrumentFileWithDate(exchange).bytes();
    }

    /** One instrument file and its {@code Last-Modified} (null when the server sends none or it does not parse). */
    record File(byte[] bytes, Instant lastModified) {
    }

    File instrumentFileWithDate(String exchange) {
        URI uri = URI.create(config.assetsBase() + INSTRUMENTS + exchange + ".json.gz");
        if (!UpstoxConfig.trustedBase(uri)) {
            throw new UpstoxException(Kind.TERMINAL, "instrument file not on Upstox's domain: " + uri.getHost(), 0, "",
                    false, null);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(config.timeout().multipliedBy(12)).GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new UpstoxException(kind(response.statusCode(), "", Call.READ), "GET instrument file " + exchange
                        + ": HTTP " + response.statusCode(), response.statusCode(), "", true, null);
            }
            Instant modified = response.headers().firstValue("Last-Modified").map(v -> {
                try {
                    return java.time.ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                } catch (DateTimeParseException e) {
                    return null;
                }
            }).orElse(null);
            return new File(response.body(), modified);
        } catch (IOException e) {
            throw new UpstoxException(Kind.RETRYABLE, "GET instrument file " + exchange + ": "
                    + e.getClass().getSimpleName(), 0, "", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstoxException(Kind.UNKNOWN, "interrupted", 0, "", true, e);
        }
    }

    private HttpRequest.Builder request(URI base, String pathAndQuery) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(config.timeout())
                .header("Accept", "application/json");
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
        } catch (IOException e) {
            throw transportFailure(e, call, what, request.uri().getHost());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstoxException(Kind.UNKNOWN, "interrupted", 0, "", true, e);
        }
        tapped(request, response.body());
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException e) {
            body = null;
        }
        int code = response.statusCode();
        String status = body == null ? "" : body.path("status").asString("");
        if (code >= 200 && code < 300 && body != null && body.isObject() && status.equalsIgnoreCase("success")) {
            return body;
        }
        if ((body == null || !body.isObject() || status.isEmpty()) && code >= 200 && code < 300) {
            // not Upstox's envelope (a proxy's page, a cut-off body): the call may have been processed
            throw new UpstoxException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + ": HTTP " + code
                    + " without Upstox's answer", code, "", true, null);
        }
        JsonNode error = body == null ? null : body.path("errors").path(0);
        String errorCode = error == null ? "" : firstText(error, "errorCode", "error_code");
        String message = error == null ? "" : firstText(error, "message");
        Kind kind = kind(code, errorCode, call);
        boolean reached = !(code == 429 || RATE_LIMIT.equals(errorCode));
        throw new UpstoxException(kind, what + ": " + explain(code, errorCode, message), code, errorCode, reached, null);
    }

    /**
     * A call that got no answer onto {@link Kind}: a connect timeout or a refused connection never sent anything
     * (RETRYABLE, not reached; review 8 Nov 2026: a connect timeout is also a {@link HttpTimeoutException} and was taken
     * as "may have reached Upstox", so the checks run in this order); a timeout waiting for the answer or a dropped
     * connection may have been processed (UNKNOWN for an order call, RETRYABLE for a read).
     */
    static UpstoxException transportFailure(IOException e, Call call, String what, String host) {
        if (e instanceof HttpConnectTimeoutException) {
            return new UpstoxException(Kind.RETRYABLE, "cannot connect to " + host + " (connect timed out)", 0, "", false,
                    e);
        }
        if (e instanceof ConnectException) {
            return new UpstoxException(Kind.RETRYABLE, "cannot connect to " + host, 0, "", false, e);
        }
        if (e instanceof HttpTimeoutException) {
            return new UpstoxException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + " timed out", 0, "",
                    true, e);
        }
        return new UpstoxException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + ": "
                + e.getClass().getSimpleName(), 0, "", true, e);
    }

    /** Upstox's answer onto {@link Kind}; the order of the checks matters (an error code is more precise than HTTP). */
    static Kind kind(int httpCode, String errorCode, Call call) {
        String c = errorCode == null ? "" : errorCode.trim().toUpperCase(Locale.ROOT);
        if (SESSION_CODES.contains(c)) {
            return Kind.SESSION;
        }
        if (RETRYABLE_CODES.contains(c)) {
            return Kind.RETRYABLE;
        }
        if (TERMINAL_CODES.contains(c)) {
            return Kind.TERMINAL;
        }
        if (httpCode == 401 || httpCode == 403) {
            return Kind.SESSION;
        }
        if (httpCode == 429 || httpCode == 423) {
            return Kind.RETRYABLE;
        }
        if (httpCode >= 500) {
            return call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE;
        }
        return Kind.TERMINAL;
    }

    private static String explain(int httpCode, String errorCode, String message) {
        String m = message == null || message.isBlank() ? "" : message.length() > 200 ? message.substring(0, 200) : message;
        String prefix = switch (errorCode == null ? "" : errorCode) {
            case STATIC_IP -> "Upstox refused the request from this IP (UDAPI1154): the static IP is not registered for "
                    + "this Upstox user. Register the account's static IP in the Upstox app (a change logs every session "
                    + "out), then log in again";
            case TOKEN_INVALID -> "Upstox token invalid or expired (UDAPI100050): log in again";
            case FUNDS_DOWN -> "Upstox's funds service is closed now (UDAPI100072, 00:00-05:30 IST)";
            case RATE_LIMIT -> "Upstox rate limit (UDAPI10005)";
            default -> !errorCode.isEmpty() ? "Upstox " + errorCode : "HTTP " + httpCode;
        };
        return m.isEmpty() ? prefix : prefix + ": " + m;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String n : names) {
            String v = node.path(n).asString("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /** A number as Upstox sends it (JSON number, or a string); blank or missing = {@code otherwise}. */
    static double number(JsonNode n, double otherwise) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return otherwise;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        String s = n.asString("").trim().replace(",", "");
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

    /** Upstox's timestamps (IST; see {@link #TIMES}); blank or unknown = null. */
    static Instant time(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("null")) {
            return null;
        }
        String v = value.trim();
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
}
