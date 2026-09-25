package com.autotrade.md.zt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.time.MarketTime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns zt-tiger-v2 tick payloads into platform events. Strict: a payload whose hash does not match,
 * or that lacks a required field, is an error, not a silent null.
 */
public final class ZtTickParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final Map<String, Set<String>> KNOWN_KEYS = Map.of(
            "CASH", Set.of("price", "atm", "volume", "close"),
            "FUTURE", Set.of("price", "volume", "oi", "sessionAveragePrice"),
            "COMPONENT", Set.of("weight", "weightSource", "price", "close", "cumulativeVolume", "sessionAveragePrice"),
            "OPTION", Set.of("instrumentToken", "tradingSymbol", "strikePrice", "instrumentType", "ltp", "volume",
                    "oi", "totalBuyQty", "totalSellQty", "bidDepth", "askDepth", "timestamp", "receivedTimestamp",
                    "quoteSource", "optionDelta", "optionGamma", "optionTheta", "optionVega", "optionRho",
                    "impliedVolatility", "derivativeAnalyticsSource", "derivativeAnalyticsTimestamp",
                    "derivativeAnalyticsUnderlyingPrice", "derivativeAnalyticsUnderlyingTimestamp"));

    private final String underlying;
    private final MessageDigest sha256 = newSha256();
    private final Map<String, Long> unknownKeys = new TreeMap<>();

    /** Last weight seen per constituent symbol, and how many distinct weights each had. */
    private final Map<String, Double> weights = new TreeMap<>();
    private final Map<String, String> weightSources = new TreeMap<>();
    private final Map<String, Integer> weightChanges = new TreeMap<>();

    public ZtTickParser(String underlying) {
        this.underlying = underlying;
    }

    /** SHA-256 of the payload as stored; throws when it does not match the source's own hash. */
    public byte[] verifiedHash(SourceTickRow row) {
        byte[] hash = sha256.digest(row.payloadJson().getBytes(StandardCharsets.UTF_8));
        if (!HexFormat.of().formatHex(hash).equalsIgnoreCase(row.payloadHash())) {
            throw new ParseException(row, "payload does not match payload_hash");
        }
        return hash;
    }

    public MarketEvent parse(SourceTickRow row) {
        JsonNode payload = JSON.readTree(row.payloadJson());
        recordUnknownKeys(row.tickType(), payload);
        Instant received = MarketTime.fromIstLocal(row.receivedAtIst());
        Instant exchange = row.exchangeTimestampMs() == null ? null : Instant.ofEpochMilli(row.exchangeTimestampMs());
        return switch (row.tickType()) {
            case "CASH" -> new IndexTick(received, exchange, underlying, row.sequence(),
                    required(row, payload, "price"), optionalDouble(payload, "close"), optionalInt(payload, "atm"));
            case "FUTURE" -> new FutureTick(received, exchange, underlying, row.sequence(),
                    requiredToken(row), requiredSymbol(row), row.contractExpiry(), required(row, payload, "price"),
                    optionalLong(payload, "volume"), optionalDouble(payload, "oi"),
                    optionalDouble(payload, "sessionAveragePrice"));
            case "OPTION" -> option(row, payload, received, exchange);
            case "COMPONENT" -> constituent(row, payload, received, exchange);
            default -> throw new ParseException(row, "unknown tick_type " + row.tickType());
        };
    }

    public Map<String, Long> unknownKeys() {
        return unknownKeys;
    }

    public Map<String, Double> weights() {
        return weights;
    }

    public Map<String, String> weightSources() {
        return weightSources;
    }

    /** Symbols whose weight changed during the session (value = number of changes). */
    public Map<String, Integer> weightChanges() {
        return weightChanges;
    }

    private OptionTick option(SourceTickRow row, JsonNode payload, Instant received, Instant exchange) {
        String type = row.instrumentType() != null ? row.instrumentType() : text(payload, "instrumentType");
        if (type == null || !(type.equals("CE") || type.equals("PE"))) {
            throw new ParseException(row, "option type missing or invalid: " + type);
        }
        String payloadType = text(payload, "instrumentType");
        if (payloadType != null && !payloadType.equals(type)) {
            throw new ParseException(row, "option type differs between column and payload");
        }
        Long analyticsMs = optionalLong(payload, "derivativeAnalyticsTimestamp");
        Instant analyticsTime = analyticsMs == null || analyticsMs <= 0 ? null : Instant.ofEpochMilli(analyticsMs);
        return new OptionTick(received, exchange, underlying, row.sequence(), requiredToken(row), requiredSymbol(row),
                row.contractExpiry(), required(row, payload, "strikePrice"), OptionTick.OptionType.valueOf(type),
                row.contractLotSize(), required(row, payload, "ltp"), optionalLong(payload, "volume"),
                optionalDouble(payload, "oi"), optionalDouble(payload, "totalBuyQty"),
                optionalDouble(payload, "totalSellQty"), depth(row, payload.get("bidDepth")),
                depth(row, payload.get("askDepth")), optionalDouble(payload, "impliedVolatility"),
                optionalDouble(payload, "optionDelta"), optionalDouble(payload, "optionGamma"),
                optionalDouble(payload, "optionTheta"), optionalDouble(payload, "optionVega"),
                optionalDouble(payload, "optionRho"), analyticsTime, Boolean.TRUE.equals(row.analyticsComplete()),
                Boolean.TRUE.equals(row.depthComplete()));
    }

    private ConstituentTick constituent(SourceTickRow row, JsonNode payload, Instant received, Instant exchange) {
        String symbol = requiredSymbol(row);
        Double weight = optionalDouble(payload, "weight");
        if (weight != null) {
            Double previous = weights.put(symbol, weight);
            if (previous != null && !previous.equals(weight)) {
                weightChanges.merge(symbol, 1, Integer::sum);
            }
            String source = text(payload, "weightSource");
            if (source != null) {
                weightSources.put(symbol, source);
            }
        }
        return new ConstituentTick(received, exchange, underlying, row.sequence(), requiredToken(row), symbol,
                required(row, payload, "price"), optionalDouble(payload, "close"),
                optionalLong(payload, "cumulativeVolume"), optionalDouble(payload, "sessionAveragePrice"), weight);
    }

    private static DepthLevels depth(SourceTickRow row, JsonNode levels) {
        if (levels == null || levels.isNull()) {
            return DepthLevels.empty();
        }
        if (!levels.isArray()) {
            throw new ParseException(row, "depth is not an array");
        }
        int size = levels.size();
        double[] prices = new double[size];
        long[] quantities = new long[size];
        int[] orders = new int[size];
        for (int i = 0; i < size; i++) {
            JsonNode level = levels.get(i);
            JsonNode price = level.get("price");
            JsonNode quantity = level.get("quantity");
            if (price == null || !price.isNumber() || quantity == null || !quantity.isNumber()) {
                throw new ParseException(row, "depth level " + i + " lacks price or quantity");
            }
            prices[i] = price.doubleValue();
            quantities[i] = quantity.longValue();
            JsonNode count = level.get("orders");
            orders[i] = count != null && count.isNumber() ? count.intValue() : 0;
        }
        return DepthLevels.of(prices, quantities, orders);
    }

    private void recordUnknownKeys(String tickType, JsonNode payload) {
        Set<String> known = KNOWN_KEYS.getOrDefault(tickType, Set.of());
        for (String key : payload.propertyNames()) {
            if (!known.contains(key)) {
                unknownKeys.merge(tickType + "." + key, 1L, Long::sum);
            }
        }
    }

    private static double required(SourceTickRow row, JsonNode payload, String key) {
        Double value = optionalDouble(payload, key);
        if (value == null) {
            throw new ParseException(row, "missing numeric field " + key);
        }
        return value;
    }

    private static long requiredToken(SourceTickRow row) {
        if (row.instrumentToken() == null) {
            throw new ParseException(row, "missing instrument_token");
        }
        return row.instrumentToken();
    }

    private static String requiredSymbol(SourceTickRow row) {
        if (row.symbol() == null || row.symbol().isBlank()) {
            throw new ParseException(row, "missing symbol");
        }
        return row.symbol();
    }

    private static Double optionalDouble(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        return node == null || !node.isNumber() ? null : node.doubleValue();
    }

    private static Long optionalLong(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        return node == null || !node.isNumber() ? null : node.longValue();
    }

    private static Integer optionalInt(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        return node == null || !node.isNumber() ? null : node.intValue();
    }

    private static String text(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        return node == null || !node.isString() ? null : node.stringValue();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class ParseException extends RuntimeException {

        ParseException(SourceTickRow row, String message) {
            super("seq " + row.sequence() + " (" + row.tickType() + " " + row.symbol() + "): " + message);
        }
    }
}
