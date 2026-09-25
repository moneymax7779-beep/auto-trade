package com.autotrade.md.zt;

import java.util.Map;

/** zt-tiger-v2's identifiers for the underlyings auto-trade supports. */
public final class ZtSourceKeys {

    private static final Map<String, String> UNDERLYING_KEYS = Map.of(
            "NIFTY", "NSE:INDEX:NIFTY",
            "SENSEX", "BSE:INDEX:SENSEX",
            "BANKNIFTY", "NSE:INDEX:BANKNIFTY");

    public static final String COMPONENT_CANDLE_PREFIX = "INDEX_COMPONENT:";

    private ZtSourceKeys() {
    }

    public static String underlyingKey(String underlying) {
        String key = UNDERLYING_KEYS.get(underlying);
        if (key == null) {
            throw new IllegalArgumentException("no zt-tiger-v2 key for underlying " + underlying);
        }
        return key;
    }
}
