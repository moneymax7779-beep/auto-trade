package com.autotrade.zerodha;

import java.net.URI;
import java.time.Duration;

/**
 * One Kite Connect app as an adapter sees it. {@code apiBase} is api.kite.trade for the real broker, or the local
 * {@link KiteSimulator}'s address; {@link #isRealBroker()} tells them apart, and the execution workers refuse a real
 * address unless the account is LIVE (which itself needs the user's explicit go-ahead).
 *
 * @param product          MIS (intraday, squared off by the broker at its cut-off) or NRML
 * @param maxOrdersPerSecond our limiter's rate per account; Kite allows 10 a second (HTTP 429 above), SEBI's algo
 *                         threshold is also 10, so this stays below
 * @param placeLookupWait  after a place call timed out: how long to wait before looking the order up by tag
 */
public record KiteConfig(String apiKey, URI apiBase, URI loginBase, String product, Duration timeout,
                         int maxOrdersPerSecond, Duration placeLookupWait) {

    public static final URI REAL_API = URI.create("https://api.kite.trade");
    public static final URI REAL_LOGIN = URI.create("https://kite.zerodha.com");

    public KiteConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Kite api key is required");
        }
        if (!(maxOrdersPerSecond >= 1 && maxOrdersPerSecond < 10)) {
            throw new IllegalArgumentException("orders per second must be 1-9 (Kite and SEBI: 10)");
        }
        product = product == null ? "MIS" : product;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        placeLookupWait = placeLookupWait == null ? Duration.ofSeconds(1) : placeLookupWait;
    }

    /** Against a local simulator: the same app shape, short timeouts. */
    public static KiteConfig simulator(URI base) {
        return new KiteConfig("sim-api-key", base, base, "MIS", Duration.ofSeconds(2), 8, Duration.ofMillis(50));
    }

    public boolean isRealBroker() {
        return REAL_API.getHost().equalsIgnoreCase(apiBase.getHost());
    }
}
