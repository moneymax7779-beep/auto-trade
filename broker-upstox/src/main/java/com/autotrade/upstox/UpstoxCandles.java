package com.autotrade.upstox;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.time.MarketTime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upstox historical and intraday candles (public market-data endpoints; no token is sent). Used for
 * reference series such as India VIX that zt-tiger-v2 does not capture.
 */
public final class UpstoxCandles {

    private static final String BASE = "https://api.upstox.com";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Upstox serves at most about a month of one-minute candles per request. */
    private static final int MINUTE_CHUNK_DAYS = 28;

    /** A candle with the session date it belongs to; volume and open interest when the endpoint sends them (0 / NaN). */
    public record Candle(OffsetDateTime start, double open, double high, double low, double close, long volume, double oi) {

        public Candle(OffsetDateTime start, double open, double high, double low, double close) {
            this(start, open, high, low, close, 0, Double.NaN);
        }

        public LocalDate session() {
            return start.atZoneSameInstant(MarketTime.IST).toLocalDate();
        }

        public MinuteBar toMinuteBar() {
            return new MinuteBar(start.toInstant(), open, high, low, close);
        }
    }

    private final HttpClient http;

    public UpstoxCandles() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    UpstoxCandles(HttpClient http) {
        this.http = http;
    }

    /** Daily candles from {@code from} to {@code to} inclusive, oldest first. */
    public List<Candle> daily(String instrumentKey, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        return get(BASE + "/v2/historical-candle/" + encode(instrumentKey) + "/day/" + to + "/" + from);
    }

    /** Completed one-minute candles of past sessions from {@code from} to {@code to} inclusive, oldest first. */
    public List<Candle> minutes(String instrumentKey, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        List<Candle> candles = new ArrayList<>();
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(MINUTE_CHUNK_DAYS)) {
            LocalDate end = start.plusDays(MINUTE_CHUNK_DAYS - 1).isAfter(to) ? to : start.plusDays(MINUTE_CHUNK_DAYS - 1);
            candles.addAll(get(BASE + "/v3/historical-candle/" + encode(instrumentKey) + "/minutes/1/" + end + "/" + start));
        }
        candles.sort(Comparator.comparing(Candle::start));
        return candles;
    }

    /** Today's one-minute candles so far (the forming minute included), oldest first. */
    public List<Candle> intradayMinutes(String instrumentKey) throws IOException, InterruptedException {
        return get(BASE + "/v3/historical-candle/intraday/" + encode(instrumentKey) + "/minutes/1");
    }

    private List<Candle> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Upstox candles HTTP " + response.statusCode() + " for " + url);
        }
        return parse(response.body());
    }

    static List<Candle> parse(String body) {
        JsonNode data = JSON.readTree(body).get("data");
        JsonNode rows = data == null ? null : data.get("candles");
        List<Candle> candles = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return candles;
        }
        for (JsonNode row : rows) {
            candles.add(new Candle(OffsetDateTime.parse(row.get(0).asString()), row.get(1).doubleValue(),
                    row.get(2).doubleValue(), row.get(3).doubleValue(), row.get(4).doubleValue(),
                    row.size() > 5 && row.get(5).isNumber() ? row.get(5).longValue() : 0,
                    row.size() > 6 && row.get(6).isNumber() ? row.get(6).doubleValue() : Double.NaN));
        }
        candles.sort(Comparator.comparing(Candle::start));
        return candles;
    }

    /** Index keys as the historical endpoints want them. */
    public static String indexKey(String underlying) {
        return switch (underlying) {
            case "NIFTY" -> "NSE_INDEX|Nifty 50";
            case "BANKNIFTY" -> "NSE_INDEX|Nifty Bank";
            case "SENSEX" -> "BSE_INDEX|SENSEX";
            case UpstoxUniverse.VIX_UNDERLYING -> UpstoxUniverse.VIX_KEY;
            default -> throw new IllegalArgumentException("no Upstox index key for " + underlying);
        };
    }

    static String encode(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
