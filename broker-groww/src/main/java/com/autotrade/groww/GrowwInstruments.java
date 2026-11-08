package com.autotrade.groww;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groww's contracts as an order names them. Groww's instrument CSV ({@code instrument.csv} on growwapi-assets.groww.in,
 * public) is read by header name; a row of segment FNO, exchange NSE or BSE and type CE or PE is one contract:
 * <ul>
 *   <li>our key is {@code NSE_FO|<exchange_token>} or {@code BSE_FO|<exchange_token>} ({@link #instrumentKey}).
 *       UNCONFIRMED: that Groww's {@code exchange_token} is the exchange's token (the one our keys carry); the daily
 *       mapping check against ref.instrument (expiry, strike, type, lot, tick) blocks every contract that disagrees, so a
 *       wrong assumption refuses orders instead of trading the wrong option;</li>
 *   <li>an order names the contract by the CSV's {@code trading_symbol} (never built by us) and its exchange;</li>
 *   <li>the order book and positions carry only the trading symbol and exchange: they are mapped back through
 *       {@link #bySymbol}.</li>
 * </ul>
 * Units (UNCONFIRMED, all in this class): {@code expiry_date} is YYYY-MM-DD ({@link #EXPIRY_FORMAT}); {@code strike_price}
 * and {@code tick_size} are rupees ({@link #STRIKE_DIVISOR}, {@link #TICK_DIVISOR}); a tick outside {@link #MIN_TICK} ..
 * {@link #MAX_TICK} rupees is taken as a unit error and the contract is not listed.
 */
public final class GrowwInstruments {

    /** {@code tick_size} in rupees (UNCONFIRMED: 0.05 in Groww's examples). */
    static final double TICK_DIVISOR = 1;
    /** {@code strike_price} in rupees (UNCONFIRMED). */
    static final double STRIKE_DIVISOR = 1;
    /** {@code expiry_date}'s format (UNCONFIRMED: YYYY-MM-DD per the docs). */
    static final String EXPIRY_FORMAT = "yyyy-MM-dd";
    static final double MIN_TICK = 0.01, MAX_TICK = 1.0;
    static final Set<String> REQUIRED = Set.of("exchange", "exchange_token", "trading_symbol", "instrument_type", "segment",
            "lot_size", "expiry_date", "strike_price", "tick_size");
    private static final Set<String> TYPES = Set.of("CE", "PE");

    /** One Groww option contract. {@code exchange} is Groww's (NSE or BSE). */
    public record Contract(String instrumentKey, String exchange, String tradingSymbol, String underlying,
                           LocalDate expiry, double strike, String type, int lotSize, double tickSize,
                           long freezeQuantity) {

        public long exchangeToken() {
            return Long.parseLong(instrumentKey.substring(instrumentKey.indexOf('|') + 1));
        }
    }

    private volatile Map<String, Contract> byKey = new ConcurrentHashMap<>();
    private volatile Map<String, Contract> bySymbol = new ConcurrentHashMap<>();

    public GrowwInstruments(List<Contract> contracts) {
        contracts.forEach(this::add);
    }

    /** Adds a contract (the simulator's listings grow as DRY_RUN meets new contracts). */
    public synchronized void add(Contract c) {
        byKey.put(c.instrumentKey(), c);
        bySymbol.put(symbolKey(c.exchange(), c.tradingSymbol()), c);
    }

    /** Today's list in place of the old one, all at once (an exchange token can be reused after its contract expires). */
    public synchronized void replaceWith(GrowwInstruments today) {
        Map<String, Contract> keys = new ConcurrentHashMap<>(today.byKey);
        Map<String, Contract> symbols = new ConcurrentHashMap<>(today.bySymbol);
        byKey = keys;
        bySymbol = symbols;
    }

    /** Takes a contract out (a failed mapping check): orders in it are then refused as not listed. */
    public synchronized void remove(Contract c) {
        byKey.remove(c.instrumentKey());
        bySymbol.remove(symbolKey(c.exchange(), c.tradingSymbol()));
    }

    public List<Contract> contracts() {
        return List.copyOf(byKey.values());
    }

    public int size() {
        return byKey.size();
    }

    public Optional<Contract> byInstrumentKey(String instrumentKey) {
        return instrumentKey == null ? Optional.empty() : Optional.ofNullable(byKey.get(instrumentKey));
    }

    /** By Groww's exchange (NSE, BSE) and trading symbol: the order book and positions carry no token. */
    public Optional<Contract> bySymbol(String exchange, String tradingSymbol) {
        if (exchange == null || tradingSymbol == null || tradingSymbol.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySymbol.get(symbolKey(exchange, tradingSymbol)));
    }

    private static String symbolKey(String exchange, String tradingSymbol) {
        return exchange.trim().toUpperCase(Locale.ROOT) + ":" + tradingSymbol.trim();
    }

    /** Groww's exchange and exchange token as our key; null for an exchange that has no derivatives key of ours. */
    public static String instrumentKey(String exchange, long exchangeToken) {
        String segment = switch (exchange == null ? "" : exchange.trim().toUpperCase(Locale.ROOT)) {
            case "NSE" -> "NSE_FO";
            case "BSE" -> "BSE_FO";
            default -> null;
        };
        return segment == null ? null : segment + "|" + exchangeToken;
    }

    /** Groww's exchange of one of our keys: NSE for NSE_FO, BSE for BSE_FO. */
    public static String exchangeOf(String instrumentKey) {
        return instrumentKey != null && instrumentKey.startsWith("BSE_FO|") ? "BSE" : "NSE";
    }

    /**
     * The CSV, one line at a time, columns by header name: options (CE, PE) of segment FNO on NSE and BSE. A file
     * without a required column is refused whole; a row that does not parse, or whose tick is out of band, is skipped
     * (not listed). Stock options are kept here; the mapping check keeps only contracts of our master.
     */
    public static List<Contract> parse(InputStream file) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(file, StandardCharsets.UTF_8));
        String header = in.readLine();
        if (header == null) {
            throw new IOException("Groww instrument file is empty");
        }
        if (header.startsWith("﻿")) {
            header = header.substring(1);
        }
        List<String> names = fields(header);
        Map<String, Integer> column = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            column.putIfAbsent(names.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        for (String required : REQUIRED) {
            if (!column.containsKey(required)) {
                throw new IOException("Groww instrument file has no column " + required);
            }
        }
        List<Contract> contracts = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            try {
                Contract c = contract(fields(line), column);
                if (c != null) {
                    contracts.add(c);
                }
            } catch (RuntimeException e) {
                // a malformed row: not listed (orders in it are refused)
            }
        }
        return contracts;
    }

    private static Contract contract(List<String> row, Map<String, Integer> column) {
        String segment = get(row, column, "segment").toUpperCase(Locale.ROOT);
        String exchange = get(row, column, "exchange").toUpperCase(Locale.ROOT);
        String type = get(row, column, "instrument_type").toUpperCase(Locale.ROOT);
        if (!segment.equals("FNO") || !TYPES.contains(type) || !(exchange.equals("NSE") || exchange.equals("BSE"))) {
            return null;
        }
        String token = get(row, column, "exchange_token");
        if (token.isEmpty() || !token.chars().allMatch(Character::isDigit) || token.length() > 18) {
            return null;
        }
        String symbol = get(row, column, "trading_symbol");
        double tick = GrowwHttp.number(get(row, column, "tick_size"), 0) / TICK_DIVISOR;
        int lot = (int) GrowwHttp.number(get(row, column, "lot_size"), 0);
        double strike = GrowwHttp.number(get(row, column, "strike_price"), Double.NaN) / STRIKE_DIVISOR;
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(get(row, column, "expiry_date"),
                    java.time.format.DateTimeFormatter.ofPattern(EXPIRY_FORMAT));
        } catch (DateTimeParseException e) {
            return null;
        }
        if (symbol.isEmpty() || !(tick >= MIN_TICK && tick <= MAX_TICK) || lot <= 0 || !(strike > 0)) {
            return null;
        }
        String underlying = get(row, column, "underlying_symbol");
        if (underlying.isEmpty()) {
            underlying = get(row, column, "name");
        }
        return new Contract(instrumentKey(exchange, Long.parseLong(token)), exchange, symbol, underlying, expiry, strike,
                type, lot, tick, (long) GrowwHttp.number(get(row, column, "freeze_quantity"), 0));
    }

    private static String get(List<String> row, Map<String, Integer> column, String name) {
        Integer i = column.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    /** One CSV line's fields: commas separate, double quotes enclose (a doubled quote inside is one quote). */
    static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    f.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(f.toString());
                f.setLength(0);
            } else {
                f.append(ch);
            }
        }
        out.add(f.toString());
        return out;
    }

    /** Today's list from Groww: the instrument CSV (public, no token; once a day). Fails as a whole, never partial. */
    public static GrowwInstruments load(GrowwHttp http) throws IOException {
        return new GrowwInstruments(parse(new ByteArrayInputStream(http.instrumentFile())));
    }
}
