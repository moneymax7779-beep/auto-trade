package com.autotrade.core.market;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An instrument that derivatives are written on. {@code code} is the canonical short name used in
 * every table and event ("NIFTY", "SENSEX", "RELIANCE").
 */
public record Underlying(String code, Exchange exchange, AssetClass assetClass) {

    public static final Underlying NIFTY = new Underlying("NIFTY", Exchange.NSE, AssetClass.INDEX);
    public static final Underlying BANKNIFTY = new Underlying("BANKNIFTY", Exchange.NSE, AssetClass.INDEX);
    public static final Underlying SENSEX = new Underlying("SENSEX", Exchange.BSE, AssetClass.INDEX);

    private static final Map<String, Underlying> INDICES =
            Map.of(NIFTY.code, NIFTY, BANKNIFTY.code, BANKNIFTY, SENSEX.code, SENSEX);

    public Underlying {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(assetClass, "assetClass");
        if (code.isBlank()) {
            throw new IllegalArgumentException("underlying code is blank");
        }
    }

    public static Underlying stock(String code, Exchange exchange) {
        return new Underlying(code.toUpperCase(Locale.ROOT), exchange, AssetClass.STOCK);
    }

    /** Looks up one of the supported indices by code, case-insensitively. */
    public static Underlying index(String code) {
        Underlying underlying = INDICES.get(code.trim().toUpperCase(Locale.ROOT));
        if (underlying == null) {
            throw new IllegalArgumentException("unknown index underlying: " + code);
        }
        return underlying;
    }

    public boolean isIndex() {
        return assetClass == AssetClass.INDEX;
    }
}
