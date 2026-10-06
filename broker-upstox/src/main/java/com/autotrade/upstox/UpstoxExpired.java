package com.autotrade.upstox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upstox Expired Instruments API (Upstox Plus): the expiries of an underlying, its expired option and future contracts,
 * and one-minute candles (OHLC, volume, open interest) of an expired contract. Every call carries the access token;
 * the token is never logged. Requests pause briefly and back off on 429 / 5xx.
 */
public final class UpstoxExpired {

    private static final Logger log = LoggerFactory.getLogger(UpstoxExpired.class);
    private static final String BASE = "https://api.upstox.com/v2/expired-instruments";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** About a month of one-minute candles per request, as the live candle endpoints. */
    static final int MINUTE_CHUNK_DAYS = 28;
    public static final long PAUSE_MS = 150;

    /** An expired contract as Upstox lists it. */
    public record Contract(String expiredKey, String tradingSymbol, LocalDate expiry, double strike, String optionType,
                           int lotSize, String exchange) {
        public boolean isFuture() {
            return optionType == null;
        }
    }

    private final UpstoxToken token;
    private final HttpClient http;

    public UpstoxExpired(UpstoxToken token) {
        this(token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    UpstoxExpired(UpstoxToken token, HttpClient http) {
        this.token = token;
        this.http = http;
    }

    /** Every expiry Upstox knows for the underlying (index key), oldest first. */
    public List<LocalDate> expiries(String underlyingKey) throws IOException, InterruptedException {
        JsonNode data = get(BASE + "/expiries?instrument_key=" + UpstoxCandles.encode(underlyingKey)).get("data");
        List<LocalDate> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode d : data) {
                out.add(LocalDate.parse(d.asString()));
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    public List<Contract> optionContracts(String underlyingKey, LocalDate expiry) throws IOException, InterruptedException {
        return contracts(BASE + "/option/contract?instrument_key=" + UpstoxCandles.encode(underlyingKey) + "&expiry_date=" + expiry);
    }

    public List<Contract> futureContracts(String underlyingKey, LocalDate expiry) throws IOException, InterruptedException {
        return contracts(BASE + "/future/contract?instrument_key=" + UpstoxCandles.encode(underlyingKey) + "&expiry_date=" + expiry);
    }

    /** One-minute candles of an expired contract from {@code from} to {@code to} inclusive, oldest first. */
    public List<UpstoxCandles.Candle> minutes(String expiredKey, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        List<UpstoxCandles.Candle> candles = new ArrayList<>();
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(MINUTE_CHUNK_DAYS)) {
            LocalDate end = start.plusDays(MINUTE_CHUNK_DAYS - 1).isAfter(to) ? to : start.plusDays(MINUTE_CHUNK_DAYS - 1);
            candles.addAll(UpstoxCandles.parse(get(BASE + "/historical-candle/" + UpstoxCandles.encode(expiredKey)
                    + "/1minute/" + end + "/" + start).toString()));
        }
        candles.sort(Comparator.comparing(UpstoxCandles.Candle::start));
        return candles;
    }

    private List<Contract> contracts(String url) throws IOException, InterruptedException {
        JsonNode data = get(url).get("data");
        return parseContracts(data);
    }

    /** Field names as Upstox documents them, with the older spellings accepted too. */
    static List<Contract> parseContracts(JsonNode data) {
        List<Contract> out = new ArrayList<>();
        if (data == null || !data.isArray()) {
            return out;
        }
        for (JsonNode c : data) {
            String key = text(c, "expired_instrument_key", "instrument_key");
            if (key == null) {
                continue;
            }
            String type = text(c, "instrument_type", "option_type");
            if (type != null && !type.equals("CE") && !type.equals("PE")) {
                type = null;                                           // FUT, or an unknown label
            }
            double strike = number(c, "strike_price", "strike");
            String expiry = text(c, "expiry", "expiry_date");
            String exchange = text(c, "exchange", "segment");
            out.add(new Contract(key, text(c, "trading_symbol", "tradingsymbol", "name"),
                    expiry == null ? null : LocalDate.parse(expiry.length() > 10 ? expiry.substring(0, 10) : expiry),
                    Double.isNaN(strike) ? 0 : strike, type, (int) Math.max(0, number(c, "lot_size", "minimum_lot")),
                    exchange == null ? null : exchange.startsWith("BSE") ? "BSE" : "NSE"));
        }
        return out;
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Thread.sleep(PAUSE_MS);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token.accessToken()).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return JSON.readTree(response.body());
            }
            String body = response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
            last = new IOException("Upstox HTTP " + response.statusCode() + " for " + url.replace(BASE, "…")
                    + (body.isEmpty() ? "" : ": " + body.substring(0, Math.min(200, body.length()))));
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                long wait = 2000L << attempt;
                log.warn("{}; retrying in {} s", last.getMessage(), wait / 1000);
                Thread.sleep(wait);
                continue;
            }
            throw last;
        }
        throw last;
    }

    private static String text(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull()) {
                return v.asString();
            }
        }
        return null;
    }

    private static double number(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && v.isNumber()) {
                return v.doubleValue();
            }
            if (v != null && v.isString()) {
                try {
                    return Double.parseDouble(v.asString());
                } catch (NumberFormatException ignored) {
                    // not a number
                }
            }
        }
        return Double.NaN;
    }
}
