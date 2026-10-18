package com.autotrade.execution;

/**
 * Our exchange key of a contract ({@code NSE_FO|<exchange token>}, {@code BSE_FO|<exchange token>}): what a broker
 * adapter maps to its own id. Decisions from live capture carry it already (the Upstox key); decisions from replays of
 * zt-tiger-v2 data carry {@code ZT|<token>}, where the token is segment offset + exchange token (UpstoxUniverse's
 * scheme: NSE_FO 3,000,000,000, BSE_FO 6,000,000,000), so the key follows from the token exactly.
 */
public final class InstrumentKeys {

    private static final long NSE_FO = 3_000_000_000L, BSE_FO = 6_000_000_000L, SEGMENT = 1_000_000_000L;

    private InstrumentKeys() {
    }

    /** The exchange key, or the given key unchanged when neither it nor the token says which contract it is. */
    public static String normalize(String key, long token) {
        if (key != null && (key.startsWith("NSE_FO|") || key.startsWith("BSE_FO|"))) {
            return key;
        }
        long segment = token / SEGMENT * SEGMENT;
        if (segment == NSE_FO) {
            return "NSE_FO|" + (token - NSE_FO);
        }
        if (segment == BSE_FO) {
            return "BSE_FO|" + (token - BSE_FO);
        }
        return key;
    }
}
