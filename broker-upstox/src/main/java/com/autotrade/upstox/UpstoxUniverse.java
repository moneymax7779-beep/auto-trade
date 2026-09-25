package com.autotrade.upstox;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.instruments.Instrument;
import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.instruments.UpstoxInstrumentFile;

/**
 * What auto-trade subscribes to on Upstox and what each instrument key means: the index, India
 * VIX, the nearest future, index constituents (for breadth and the closing auction), and a band of
 * nearest-expiry options around the ATM strike that is re-centred as the index moves.
 */
public final class UpstoxUniverse {

    public enum Kind { INDEX, VIX, FUTURE, OPTION, EQUITY }

    /** Everything the mapper needs to turn a feed message for {@code key} into an event. */
    public record Meta(String key, long token, Kind kind, String underlying, String symbol, LocalDate expiry,
                       double strike, String optionType, Integer lotSize, Double weightPercent) {
    }

    /** A constituent with its current index weight in percent. */
    public record Constituent(UpstoxInstrumentFile.Listing listing, double weightPercent) {
    }

    public static final String VIX_KEY = "NSE_INDEX|India VIX";
    public static final String VIX_UNDERLYING = "INDIA_VIX";
    static final Map<String, String> INDEX_KEYS = Map.of(
            "NIFTY", "NSE_INDEX|Nifty 50",
            "BANKNIFTY", "NSE_INDEX|Nifty Bank",
            "SENSEX", "BSE_INDEX|SENSEX");
    private static final Map<String, Long> SEGMENT_OFFSETS = Map.of(
            "NSE_EQ", 1_000_000_000L, "BSE_EQ", 2_000_000_000L, "NSE_FO", 3_000_000_000L,
            "BSE_FO", 6_000_000_000L, "NSE_INDEX", 7_000_000_000L, "BSE_INDEX", 8_000_000_000L);
    private static final Map<String, String> INDEX_TOKENS = Map.of(
            "NSE_INDEX|Nifty 50", "26000", "NSE_INDEX|Nifty Bank", "26009", "BSE_INDEX|SENSEX", "1",
            VIX_KEY, "26017");

    private final InstrumentMaster master;
    private final LocalDate session;
    private final Map<String, Meta> byKey = new HashMap<>();
    private final Set<String> base = new LinkedHashSet<>();

    public UpstoxUniverse(InstrumentMaster master, LocalDate session, List<String> underlyings,
                          Map<String, List<Constituent>> constituents) {
        this.master = master;
        this.session = session;
        for (String underlying : underlyings) {
            String indexKey = INDEX_KEYS.get(underlying);
            if (indexKey == null) {
                throw new IllegalArgumentException("no Upstox index key for " + underlying);
            }
            add(new Meta(indexKey, token(indexKey, INDEX_TOKENS.get(indexKey)), Kind.INDEX, underlying, underlying, null,
                    0, null, null, null));
            master.nearestFuture(underlying, session).ifPresent(future -> add(new Meta(future.instrumentKey(),
                    token(future.instrumentKey(), future.exchangeToken()), Kind.FUTURE, underlying, future.tradingSymbol(),
                    future.expiry(), 0, null, future.lotSize(), null)));
            for (Constituent constituent : constituents.getOrDefault(underlying, List.of())) {
                UpstoxInstrumentFile.Listing listing = constituent.listing();
                add(new Meta(listing.instrumentKey(), token(listing.instrumentKey(), listing.exchangeToken()),
                        Kind.EQUITY, underlying, listing.tradingSymbol(), null, 0, null, 1, constituent.weightPercent()));
            }
        }
        add(new Meta(VIX_KEY, token(VIX_KEY, INDEX_TOKENS.get(VIX_KEY)), Kind.VIX, VIX_UNDERLYING, "INDIA VIX", null, 0,
                null, null, null));
    }

    private void add(Meta meta) {
        byKey.put(meta.key(), meta);
        base.add(meta.key());
    }

    /** Index, VIX, futures and constituents: subscribed once at connect. */
    public Set<String> baseKeys() {
        return Set.copyOf(base);
    }

    public Meta meta(String key) {
        return byKey.get(key);
    }

    /** Nearest-expiry options (CE and PE) within {@code strikesEachSide} strikes of the ATM strike. */
    public Set<String> optionKeys(String underlying, double spot, int strikesEachSide) {
        Set<String> keys = new LinkedHashSet<>();
        var expiry = master.nearestOptionExpiry(underlying, session);
        if (expiry.isEmpty()) {
            return keys;
        }
        double step = master.strikeStep(underlying, expiry.get());
        double atm = Math.round(spot / step) * step;
        master.instruments(underlying).stream()
                .filter(i -> i.isOption() && expiry.get().equals(i.expiry())
                        && Math.abs(i.strike() - atm) <= strikesEachSide * step + 1e-6)
                .sorted(Comparator.comparingDouble(Instrument::strike))
                .forEach(i -> {
                    byKey.putIfAbsent(i.instrumentKey(), new Meta(i.instrumentKey(), token(i.instrumentKey(),
                            i.exchangeToken()), Kind.OPTION, underlying, i.tradingSymbol(), i.expiry(), i.strike(),
                            i.type(), i.lotSize(), null));
                    keys.add(i.instrumentKey());
                });
        return keys;
    }

    public double strikeStep(String underlying) {
        return master.nearestOptionExpiry(underlying, session).map(e -> master.strikeStep(underlying, e))
                .orElse(Double.NaN);
    }

    /** A numeric token unique across segments: segment offset + exchange token. */
    static long token(String instrumentKey, String exchangeToken) {
        String segment = instrumentKey.substring(0, instrumentKey.indexOf('|'));
        Long offset = SEGMENT_OFFSETS.get(segment);
        if (offset == null || exchangeToken == null) {
            throw new IllegalArgumentException("cannot number " + instrumentKey);
        }
        return offset + Long.parseLong(exchangeToken);
    }
}
