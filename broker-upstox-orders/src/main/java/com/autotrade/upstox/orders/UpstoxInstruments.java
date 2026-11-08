package com.autotrade.upstox.orders;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upstox's contracts as an order names them. Our instrument key is Upstox's own {@code instrument_key}
 * ({@code NSE_FO|<exchange token>}, {@code BSE_FO|<exchange token>}), and an order's {@code instrument_token} is that key,
 * so the mapping is the identity; what this list adds is each contract's lot, tick, expiry, strike and type from Upstox's
 * instrument file of the day ({@code NSE.json.gz}, {@code BSE.json.gz} on assets.upstox.com), which the daily mapping
 * check compares with our contract master (ref.instrument) before any of them may be traded. Only index options (CE, PE)
 * of NSE_FO and BSE_FO are kept.
 * <p>
 * Units: {@code tick_size} is in paise ({@link #TICK_DIVISOR}; the same division ref.instrument's loader,
 * {@code UpstoxInstrumentFile}, applies: 5.0 = Rs 0.05; Upstox's docs do not state the unit: UNCONFIRMED), a tick outside
 * {@link #MIN_TICK}..{@link #MAX_TICK} rupees is taken as a unit error and the contract is not listed (orders in it are
 * refused); {@code expiry} is epoch milliseconds (the last millisecond of the expiry day, IST); {@code strike_price} and
 * {@code lot_size} are plain (units).
 */
public final class UpstoxInstruments {

    /** {@code tick_size} is in paise (ref.instrument's loader divides by 100 too). UNCONFIRMED by Upstox's docs. */
    static final double TICK_DIVISOR = 100;
    /** An index option's tick in rupees is 0.05 today; anything outside this band is a unit error, never traded. */
    static final double MIN_TICK = 0.01, MAX_TICK = 1.0;
    private static final Set<String> SEGMENTS = Set.of("NSE_FO", "BSE_FO");
    private static final Set<String> TYPES = Set.of("CE", "PE");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One Upstox option contract; {@code instrumentKey} is both ours and Upstox's {@code instrument_token}. */
    public record Contract(String instrumentKey, String tradingSymbol, String underlying, LocalDate expiry, double strike,
                           String type, int lotSize, double tickSize, long freezeQuantity) {

        public String segment() {
            return instrumentKey.substring(0, instrumentKey.indexOf('|'));
        }

        public long exchangeToken() {
            return Long.parseLong(instrumentKey.substring(instrumentKey.indexOf('|') + 1));
        }
    }

    private volatile Map<String, Contract> byKey = new ConcurrentHashMap<>();

    public UpstoxInstruments(List<Contract> contracts) {
        contracts.forEach(this::add);
    }

    /** Adds a contract (the simulator's listings grow as DRY_RUN meets new contracts). */
    public synchronized void add(Contract c) {
        byKey.put(c.instrumentKey(), c);
    }

    /** Today's list in place of the old one, all at once (an exchange token can be reused after its contract expires). */
    public synchronized void replaceWith(UpstoxInstruments today) {
        byKey = new ConcurrentHashMap<>(today.byKey);
    }

    /** Takes a contract out (a failed mapping check): orders in it are then refused as not listed. */
    public synchronized void remove(Contract c) {
        byKey.remove(c.instrumentKey());
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

    /**
     * One instrument file (gzipped or plain JSON array of objects), read one element at a time: index options of NSE_FO
     * and BSE_FO. A row that does not parse, or whose tick is out of band, is skipped (not listed).
     */
    public static List<Contract> parse(InputStream file) throws IOException {
        InputStream in = new BufferedInputStream(file);
        in.mark(2);
        int b1 = in.read(), b2 = in.read();
        in.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            in = new GZIPInputStream(in);
        }
        List<Contract> contracts = new ArrayList<>();
        try (MappingIterator<JsonNode> rows = JSON.readerFor(JsonNode.class).readValues(in)) {
            while (rows.hasNext()) {
                JsonNode n = rows.next();
                try {
                    Contract c = contract(n);
                    if (c != null) {
                        contracts.add(c);
                    }
                } catch (RuntimeException e) {
                    // a malformed row: not listed (orders in it are refused)
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Upstox instrument file is not a JSON array of instruments ("
                    + e.getClass().getSimpleName() + ")", e);
        }
        return contracts;
    }

    private static Contract contract(JsonNode n) {
        String segment = n.path("segment").asString("");
        String type = n.path("instrument_type").asString("");
        if (!SEGMENTS.contains(segment) || !TYPES.contains(type)) {
            return null;
        }
        String underlyingType = n.path("underlying_type").asString("");
        if (!underlyingType.isEmpty() && !underlyingType.equals("INDEX")) {
            return null;                                        // stock options are not ours
        }
        String key = n.path("instrument_key").asString("");
        if (!key.startsWith(segment + "|") || !key.substring(segment.length() + 1).chars().allMatch(Character::isDigit)
                || key.length() == segment.length() + 1) {
            return null;
        }
        double tick = UpstoxHttp.number(n.path("tick_size"), 0) / TICK_DIVISOR;
        int lot = (int) UpstoxHttp.number(n.path("lot_size"), 0);
        long expiryMs = (long) UpstoxHttp.number(n.path("expiry"), 0);
        if (!(tick >= MIN_TICK && tick <= MAX_TICK) || lot <= 0 || expiryMs <= 0) {
            return null;
        }
        LocalDate expiry = Instant.ofEpochMilli(expiryMs).atZone(IST).toLocalDate();
        return new Contract(key, n.path("trading_symbol").asString(""), n.path("underlying_symbol").asString(""), expiry,
                UpstoxHttp.number(n.path("strike_price"), 0), type, lot, tick,
                (long) UpstoxHttp.number(n.path("freeze_quantity"), 0));
    }

    /**
     * Today's list from Upstox: the NSE and BSE files (public, no token; tens of MB unpacked; once a day). Fails when
     * either is missing, so a partial list is never used, and when the files are not today's ({@link #load(UpstoxHttp,
     * LocalDate)}).
     */
    public static UpstoxInstruments load(UpstoxHttp http) throws IOException {
        return load(http, LocalDate.now(IST));
    }

    /**
     * The list as of {@code today} (IST): refused as stale (review 8 Nov 2026: yesterday's file would map tokens that
     * may be reused) when a file's {@code Last-Modified} is before {@code today}, or when no listed option expires on or
     * after {@code today} (a file of past contracts only; an empty list also has none). UNCONFIRMED: when Upstox publishes
     * the day's file (around 06:00 IST per its docs); a load before then fails and is retried later in the day.
     */
    public static UpstoxInstruments load(UpstoxHttp http, LocalDate today) throws IOException {
        List<Contract> all = new ArrayList<>();
        for (String exchange : List.of("NSE", "BSE")) {
            UpstoxHttp.File file = http.instrumentFileWithDate(exchange);
            if (file.lastModified() != null && file.lastModified().atZone(IST).toLocalDate().isBefore(today)) {
                throw new IOException("stale Upstox instrument file " + exchange + ": last modified "
                        + file.lastModified().atZone(IST).toLocalDate() + ", before " + today);
            }
            all.addAll(parse(new ByteArrayInputStream(file.bytes())));
        }
        if (all.stream().noneMatch(c -> c.expiry() != null && !c.expiry().isBefore(today))) {
            throw new IOException("stale Upstox instrument file: no option expires on or after " + today + " ("
                    + all.size() + " listed)");
        }
        return new UpstoxInstruments(all);
    }
}
