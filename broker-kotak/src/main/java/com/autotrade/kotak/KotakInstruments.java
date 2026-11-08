package com.autotrade.kotak;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kotak Neo's scrip master (GET {@code {baseUrl}/script-details/1.0/masterscrip/file-paths}, then the day's
 * {@code nse_fo.csv} and {@code bse_fo.csv}), mapped from our instrument keys. Our signals carry the Upstox key,
 * {@code NSE_FO|<exchange token>} or {@code BSE_FO|<exchange token>}; Kotak's {@code pSymbol} is taken to be the same
 * exchange token (UNCONFIRMED: the daily mapping check against ref.instrument blocks any contract where it is not), so
 * the map is: segment NSE_FO = {@code nse_fo}, BSE_FO = {@code bse_fo}, token = pSymbol. An order names
 * {@code pTrdSymbol} exactly as Kotak lists it; a trading symbol is never built here.
 * <p>
 * Every conversion whose scale the sources do not confirm is in one place below ({@link #NSE_FO_EXPIRY_OFFSET},
 * {@link #STRIKE_DIVISOR}, {@link #TICK_DIVISOR}); the mapping check compares expiry, strike, type, lot and tick with our
 * contract master, so a wrong scale blocks trading instead of buying the wrong option.
 */
public final class KotakInstruments {

    /**
     * nse_fo {@code lExpiryDate}: seconds since 1 Jan 1980 (an epoch offset of 315,511,200 s per the SDK; OpenAlgo uses
     * 315,513,000; both give the same IST date for an expiry in trading hours). UNCONFIRMED by Kotak's docs.
     */
    static final long NSE_FO_EXPIRY_OFFSET = 315_511_200L;
    /** {@code dStrikePrice} is in paise for F&amp;O (OpenAlgo divides by 100). UNCONFIRMED. */
    static final double STRIKE_DIVISOR = 100;
    /** {@code dTickSize} is in paise (OpenAlgo divides by 100). UNCONFIRMED. */
    static final double TICK_DIVISOR = 100;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One Kotak contract: what an order names (segment + trading symbol) and its exchange token. */
    public record Contract(String segment, long exchangeToken, String tradingSymbol, String name, LocalDate expiry,
                           double strike, String type, int lotSize, double tickSize) {

        /** Our key of this contract ({@code NSE_FO|47168}). */
        public String instrumentKey() {
            return KotakInstruments.instrumentKey(segment, exchangeToken);
        }
    }

    private volatile Map<String, Contract> byKey = new ConcurrentHashMap<>();
    private volatile Map<String, Contract> bySymbol = new ConcurrentHashMap<>();

    public KotakInstruments(List<Contract> contracts) {
        contracts.forEach(this::add);
    }

    /** Adds a contract (the simulator's listings grow as DRY_RUN meets new contracts). */
    public synchronized void add(Contract c) {
        byKey.put(c.instrumentKey(), c);
        bySymbol.put(c.segment() + ":" + c.tradingSymbol(), c);
    }

    /** Today's list in place of the old one, all at once (an exchange token can be reused after its contract expires). */
    public synchronized void replaceWith(KotakInstruments today) {
        Map<String, Contract> keys = new ConcurrentHashMap<>(today.byKey);
        Map<String, Contract> symbols = new ConcurrentHashMap<>(today.bySymbol);
        byKey = keys;
        bySymbol = symbols;
    }

    /** Takes a contract out (a failed mapping check): orders in it are then refused as not listed. */
    public synchronized void remove(Contract c) {
        byKey.remove(c.instrumentKey());
        bySymbol.remove(c.segment() + ":" + c.tradingSymbol());
    }

    public List<Contract> contracts() {
        return List.copyOf(byKey.values());
    }

    public int size() {
        return byKey.size();
    }

    /** Our key ({@code NSE_FO|47168}) to Kotak's contract; empty = not mapped (entries in it are blocked). */
    public Optional<Contract> byInstrumentKey(String instrumentKey) {
        return instrumentKey == null ? Optional.empty() : Optional.ofNullable(byKey.get(instrumentKey));
    }

    /** By Kotak's segment ({@code nse_fo}) and trading symbol (its trade book carries no token). */
    public Optional<Contract> bySymbol(String segment, String tradingSymbol) {
        if (segment == null || tradingSymbol == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySymbol.get(segment.trim().toLowerCase(Locale.ROOT) + ":" + tradingSymbol.trim()));
    }

    /**
     * Kotak's segment and token back to our key. Only nse_fo and bse_fo are our derivatives keys; another segment keeps
     * its own name ({@code NSE_CM|2885}), so a share can never be taken for an option with the same token.
     */
    public static String instrumentKey(String segment, long exchangeToken) {
        String s = segment == null ? "" : segment.trim().toLowerCase(Locale.ROOT);
        String ours = switch (s) {
            case "nse_fo" -> "NSE_FO";
            case "bse_fo" -> "BSE_FO";
            default -> s.toUpperCase(Locale.ROOT);
        };
        return ours + "|" + exchangeToken;
    }

    /** Kotak's segment of our key's segment ({@code NSE_FO} = {@code nse_fo}); null for anything else. */
    public static String segment(String instrumentKey) {
        if (instrumentKey == null) {
            return null;
        }
        if (instrumentKey.startsWith("NSE_FO|")) {
            return "nse_fo";
        }
        return instrumentKey.startsWith("BSE_FO|") ? "bse_fo" : null;
    }

    /** {@code lExpiryDate} of a segment to its IST date; 0 or negative = none. */
    static LocalDate expiry(String segment, long raw) {
        if (raw <= 0) {
            return null;
        }
        long epoch = "nse_fo".equals(segment) ? raw + NSE_FO_EXPIRY_OFFSET : raw;
        return Instant.ofEpochSecond(epoch).atZone(IST).toLocalDate();
    }

    /** The inverse (the simulator writes its CSVs with it): the expiry day at 14:30 IST in Kotak's encoding. */
    static long rawExpiry(String segment, LocalDate expiry) {
        long epoch = expiry.atTime(14, 30).atZone(IST).toEpochSecond();
        return "nse_fo".equals(segment) ? epoch - NSE_FO_EXPIRY_OFFSET : epoch;
    }

    /**
     * Parses one scrip master CSV: columns by trimmed header name (Kotak's headers can carry stray ';' and spaces, e.g.
     * {@code "dStrikePrice;"} or {@code "lExpiryDate "}): pSymbol, pExchSeg, pTrdSymbol, pSymbolName, lLotSize,
     * lExpiryDate, dStrikePrice, dTickSize, pOptionType. Only nse_fo and bse_fo options (CE, PE) are kept; a row that does
     * not parse is skipped.
     */
    public static List<Contract> parse(Reader csv) throws IOException {
        BufferedReader in = new BufferedReader(csv);
        String header = in.readLine();
        if (header == null) {
            return List.of();
        }
        if (header.startsWith("﻿")) {
            header = header.substring(1);
        }
        List<String> names = split(header);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            col.put(names.get(i).replaceAll("[;\\s\"]", ""), i);
        }
        for (String required : List.of("pSymbol", "pExchSeg", "pTrdSymbol", "lLotSize", "lExpiryDate", "dStrikePrice",
                "dTickSize", "pOptionType")) {
            if (!col.containsKey(required)) {
                throw new IOException("Kotak scrip master has no column " + required);
            }
        }
        List<Contract> contracts = new ArrayList<>();
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = split(line);
            try {
                String segment = field(f, col, "pExchSeg").toLowerCase(Locale.ROOT);
                String type = field(f, col, "pOptionType").toUpperCase(Locale.ROOT);
                if (!(segment.equals("nse_fo") || segment.equals("bse_fo")) || !(type.equals("CE") || type.equals("PE"))) {
                    continue;
                }
                long token = Long.parseLong(field(f, col, "pSymbol"));
                String symbol = field(f, col, "pTrdSymbol");
                String name = col.containsKey("pSymbolName") ? field(f, col, "pSymbolName") : "";
                int lot = (int) Double.parseDouble(field(f, col, "lLotSize"));
                long rawExpiry = (long) Double.parseDouble(field(f, col, "lExpiryDate"));
                double strike = Double.parseDouble(field(f, col, "dStrikePrice")) / STRIKE_DIVISOR;
                double tick = Double.parseDouble(field(f, col, "dTickSize")) / TICK_DIVISOR;
                if (symbol.isEmpty() || lot <= 0) {
                    continue;
                }
                contracts.add(new Contract(segment, token, symbol, name, expiry(segment, rawExpiry), strike, type, lot,
                        tick));
            } catch (RuntimeException e) {
                // a malformed row: not listed (orders in it are refused)
            }
        }
        return contracts;
    }

    /**
     * Today's list from Kotak: the file paths, then the nse_fo and bse_fo files (a few MB; once a day). Fails when either
     * is missing, so a partial list is never used.
     */
    public static KotakInstruments load(KotakHttp http) throws IOException {
        List<String> paths = http.scripFilePaths();
        List<Contract> all = new ArrayList<>();
        for (String segment : List.of("nse_fo", "bse_fo")) {
            String url = paths.stream().filter(p -> pathOf(p).endsWith(segment + ".csv")).findFirst()
                    .orElseThrow(() -> new IOException("Kotak scrip master lists no " + segment + ".csv"));
            all.addAll(parse(new StringReader(http.getText(url))));
        }
        return new KotakInstruments(all);
    }

    private static String pathOf(String url) {
        int q = url.indexOf('?');
        return (q < 0 ? url : url.substring(0, q)).toLowerCase(Locale.ROOT);
    }

    private static String field(List<String> f, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= f.size() ? "" : f.get(i).trim();
    }

    /** One CSV line: commas outside double quotes separate fields; quotes are removed ("" is a quote). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
