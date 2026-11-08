package com.autotrade.kotak;

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
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.autotrade.broker.BrokerException.Kind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Kotak Neo Trade API over plain HTTP (Kotak's GitHub docs, SDK kotakneoapi v3.0.13, OpenAlgo's plugin): paths
 * relative to the session's base URL; the headers {@code accept: application/json}, {@code Sid}, {@code Auth} (the
 * trade token) and {@code neo-fin-key: neotradeapi}; POST bodies form-encoded with one field {@code jData} holding a JSON
 * object whose every value is a string; answers {@code {"stat": "Ok" | "Not_Ok", "stCode": N, "emsg"|"errMsg": ...}}.
 * <p>
 * Errors onto {@link Kind} ({@link #kind}): an invalid session (stCode 1003, HTTP 401/403), a session made from another IP
 * (1037) and an IP not whitelisted (100008) are SESSION; insufficient balance (1004), invalid symbol (1007), invalid
 * quantity (1009) and a modify or cancel of a finished order (1006, 1021) are TERMINAL; 1005, HTTP 5xx, a timeout or a
 * dropped connection are UNKNOWN for an order call (it may have been processed: look it up by tag) and RETRYABLE for a
 * read; HTTP 429 and a refused connection are RETRYABLE and never reached the broker; anything else Kotak refuses is
 * TERMINAL. The token, the sid, the consumer key and request or response bodies are never logged or put in a message.
 */
public final class KotakHttp {

    /** A read (book, positions, limits: safe to retry) or an order call (place, modify, cancel). */
    public enum Call { READ, ORDER }

    // Kotak stCodes (Kotak's docs list 1003/1004/1005/1006/1007/1009/1021/1037; 100008 from the static-IP page)
    static final int SESSION_INVALID = 1003;
    static final int INSUFFICIENT_BALANCE = 1004;
    static final int SERVER_ERROR = 1005;
    static final int ORDER_FINISHED = 1006;
    static final int INVALID_SYMBOL = 1007;
    static final int INVALID_QUANTITY = 1009;
    static final int ORDER_NOT_MODIFIABLE = 1021;
    static final int WRONG_IP = 1037;
    static final int IP_NOT_WHITELISTED = 100008;
    private static final Set<Integer> TERMINAL_CODES = Set.of(INSUFFICIENT_BALANCE, ORDER_FINISHED, INVALID_SYMBOL,
            INVALID_QUANTITY, ORDER_NOT_MODIFIABLE);

    static final String PLACE = "/quick/order/rule/ms/place";
    static final String MODIFY = "/quick/order/vr/modify";
    static final String CANCEL = "/quick/order/cancel";
    static final String ORDERS = "/quick/user/orders";
    static final String TRADES = "/quick/user/trades";
    static final String POSITIONS = "/quick/user/positions";
    static final String LIMITS = "/quick/user/limits";
    static final String SCRIP_PATHS = "/script-details/1.0/masterscrip/file-paths";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /**
     * Kotak's timestamp formats (UNCONFIRMED: sources show "19-Jan-2024 14:23:45" for ordDtTm and "2024/01/19 14:23:45"
     * for hsUpTm); an unknown format reads as no time.
     */
    private static final List<DateTimeFormatter> TIMES = List.of(pattern("dd-MMM-yyyy HH:mm:ss"),
            pattern("yyyy/MM/dd HH:mm:ss"), pattern("yyyy-MM-dd HH:mm:ss"), pattern("dd/MM/yyyy HH:mm:ss"),
            pattern("dd-MM-yyyy HH:mm:ss"), pattern("dd MMM yyyy HH:mm:ss"));

    private final KotakConfig config;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile String token;
    private volatile String sid;

    public KotakHttp(KotakConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    /** The day's trade token and sid (from the login's tradeApiValidate); never logged. */
    public void session(String token, String sid) {
        this.token = token;
        this.sid = sid;
    }

    public KotakConfig config() {
        return config;
    }

    JsonNode get(String path, Call call) {
        return send(request(path).GET(), call);
    }

    /** POST with {@code jData=<JSON object of strings>}. */
    JsonNode post(String path, Map<String, String> jData, Call call) {
        return send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(jData))), call);
    }

    /** The form body: {@code jData=} and the JSON object, every value a string (null values left out). */
    String form(Map<String, String> jData) {
        ObjectNode o = json.createObjectNode();
        jData.forEach((k, v) -> {
            if (v != null) {
                o.put(k, v);
            }
        });
        return "jData=" + URLEncoder.encode(json.writeValueAsString(o), StandardCharsets.UTF_8);
    }

    /** The scrip master's file URLs ({@code data.filesPaths}); the consumer key is the only credential sent. */
    List<String> scripFilePaths() {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl() + SCRIP_PATHS))
                .timeout(config.timeout().multipliedBy(4)).header("accept", "application/json")
                .header("Authorization", config.consumerKey()).GET();
        JsonNode body = send(b, Call.READ);
        List<String> paths = new java.util.ArrayList<>();
        for (JsonNode p : body.path("data").path("filesPaths")) {
            paths.add(p.asString(""));
        }
        return paths;
    }

    /**
     * A scrip master CSV: a plain GET, no credentials. Only HTTPS on Kotak's domain or the local simulator (UNCONFIRMED
     * which host serves the files; another host fails the load, and orders stay refused until it loads).
     */
    String getText(String url) {
        URI uri = URI.create(url);
        if (!trustedFile(uri)) {
            throw new KotakException(Kind.TERMINAL, "scrip master file not on Kotak's domain: " + uri.getHost(), 0, 0,
                    false, null);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(config.timeout().multipliedBy(12)).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new KotakException(kind(response.statusCode(), 0, Call.READ), "GET scrip file: HTTP "
                        + response.statusCode(), response.statusCode(), 0, true, null);
            }
            return response.body();
        } catch (IOException e) {
            throw new KotakException(Kind.RETRYABLE, "GET scrip file: " + e.getClass().getSimpleName(), 0, 0, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KotakException(Kind.UNKNOWN, "interrupted", 0, 0, true, e);
        }
    }

    static boolean trustedFile(URI uri) {
        return KotakConfig.trustedBase(uri);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl() + path)).timeout(config.timeout())
                .header("accept", "application/json").header("neo-fin-key", KotakConfig.NEO_FIN_KEY);
        String t = token, s = sid;
        if (t != null && s != null) {
            b.header("Sid", s).header("Auth", t);
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
            throw new KotakException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + " timed out", 0, 0, true, e);
        } catch (ConnectException e) {
            throw new KotakException(Kind.RETRYABLE, "cannot connect to " + config.baseUrl().getHost(), 0, 0, false, e);
        } catch (IOException e) {
            throw new KotakException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + ": "
                    + e.getClass().getSimpleName(), 0, 0, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KotakException(Kind.UNKNOWN, "interrupted", 0, 0, true, e);
        }
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException e) {
            body = null;
        }
        int code = response.statusCode();
        String stat = body == null ? "" : body.path("stat").asString("");
        if (code >= 200 && code < 300 && body != null && body.isObject() && (stat.isEmpty() || stat.equalsIgnoreCase("Ok"))) {
            return body;
        }
        if ((body == null || !body.isObject()) && code >= 200 && code < 300) {
            // not Kotak's envelope (a proxy's page, a cut-off body): the call may have been processed
            throw new KotakException(call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE, what + ": HTTP " + code
                    + " without Kotak's answer", code, 0, true, null);
        }
        int stCode = body == null ? 0 : (int) KotakHttp.number(body.path("stCode"), 0);
        String message = body == null ? "" : firstText(body, "emsg", "errMsg", "message", "error");
        Kind kind = kind(code, stCode, call);
        throw new KotakException(kind, what + ": " + explain(code, stCode, message), code, stCode,
                code != 429, null);
    }

    /** Kotak's answer onto {@link Kind}; the order of the checks matters (a body code is more precise than HTTP). */
    static Kind kind(int httpCode, int stCode, Call call) {
        if (stCode == SESSION_INVALID || stCode == WRONG_IP || stCode == IP_NOT_WHITELISTED) {
            return Kind.SESSION;
        }
        if (TERMINAL_CODES.contains(stCode)) {
            return Kind.TERMINAL;
        }
        if (httpCode == 401 || httpCode == 403) {
            return Kind.SESSION;
        }
        if (httpCode == 429) {
            return Kind.RETRYABLE;
        }
        if (stCode == SERVER_ERROR || httpCode >= 500) {
            return call == Call.ORDER ? Kind.UNKNOWN : Kind.RETRYABLE;
        }
        return Kind.TERMINAL;
    }

    private static String explain(int httpCode, int stCode, String message) {
        String m = message == null || message.isBlank() ? "" : message.length() > 200 ? message.substring(0, 200) : message;
        String prefix = switch (stCode) {
            case WRONG_IP -> "Kotak session was created from another IP than this request's (1037): log in again "
                    + "through the app page on the order host";
            case IP_NOT_WHITELISTED -> "this IP is not whitelisted for the Kotak app (100008): register the account's "
                    + "static IP with Kotak";
            case SESSION_INVALID -> "Kotak session invalid or expired (1003): log in again";
            default -> stCode != 0 ? "Kotak stCode " + stCode : "HTTP " + httpCode;
        };
        return m.isEmpty() ? prefix : prefix + ": " + m;
    }

    private static String firstText(JsonNode body, String... names) {
        for (String n : names) {
            String v = body.path(n).asString("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /** A Kotak number: JSON number or string ("12.50", " 65 ", "1,234.5"); blank or missing = {@code otherwise}. */
    static double number(JsonNode n, double otherwise) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return otherwise;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        String s = n.asString("").trim().replace(",", "");
        if (s.isEmpty() || s.equalsIgnoreCase("NA") || s.equalsIgnoreCase("null")) {
            return otherwise;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    static long whole(JsonNode n) {
        double d = number(n, 0);
        return Math.round(d);
    }

    /** Kotak's timestamps (IST; several formats, see {@link #TIMES}); blank or unknown = null. */
    static Instant time(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("NA")) {
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
