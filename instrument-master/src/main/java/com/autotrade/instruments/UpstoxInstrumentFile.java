package com.autotrade.instruments;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.autotrade.core.time.MarketTime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Upstox's instrument file ({@code NSE.json.gz}, {@code BSE.json.gz}, published daily at
 * https://assets.upstox.com/market-quote/instruments/exchange/). Upstox gives tick size in paise
 * and expiry as the last millisecond of the expiry day in IST.
 */
public final class UpstoxInstrumentFile {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> DERIVATIVE_SEGMENTS = Set.of("NSE_FO", "BSE_FO");
    private static final Set<String> TYPES = Set.of("CE", "PE", "FUT");

    private UpstoxInstrumentFile() {
    }

    /** Index derivatives (options and futures) on the given underlyings, e.g. NIFTY, BANKNIFTY, SENSEX. */
    public static List<Instrument> read(Path file, Set<String> underlyings) throws IOException {
        JsonNode root;
        try (InputStream in = open(file)) {
            root = JSON.readTree(in);
        }
        if (root == null || !root.isArray()) {
            throw new IOException(file + " is not a JSON array of instruments");
        }
        List<Instrument> instruments = new ArrayList<>();
        for (JsonNode node : root) {
            String segment = text(node, "segment");
            String type = text(node, "instrument_type");
            String underlying = text(node, "underlying_symbol");
            if (!DERIVATIVE_SEGMENTS.contains(segment) || !TYPES.contains(type) || !underlyings.contains(underlying)
                    || !"INDEX".equals(text(node, "underlying_type"))) {
                continue;
            }
            instruments.add(new Instrument(
                    text(node, "instrument_key"),
                    text(node, "exchange_token"),
                    segment,
                    text(node, "exchange"),
                    type,
                    underlying,
                    text(node, "underlying_key"),
                    text(node, "trading_symbol"),
                    expiry(node.get("expiry")),
                    number(node, "strike_price"),
                    (int) number(node, "lot_size"),
                    number(node, "tick_size") / 100.0,
                    (long) number(node, "freeze_quantity"),
                    node.has("weekly") && node.get("weekly").booleanValue()));
        }
        return instruments;
    }

    private static InputStream open(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file));
        return file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    private static LocalDate expiry(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return Instant.ofEpochMilli(node.longValue()).atZone(MarketTime.IST).toLocalDate();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static double number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || !value.isNumber() ? 0 : value.doubleValue();
    }
}
