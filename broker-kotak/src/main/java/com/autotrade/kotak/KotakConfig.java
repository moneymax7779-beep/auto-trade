package com.autotrade.kotak;

import java.net.URI;
import java.time.Duration;

/**
 * One Kotak Neo session as an adapter sees it. {@code baseUrl} is the base URL Kotak returned at the day's login
 * (tradeApiValidate; per account, on kotaksecurities.com), or the local {@link KotakSimulator}'s address;
 * {@link #isRealBroker()} tells them apart. This build has no LIVE Kotak connection: a real base URL is only ever read
 * (DRY_RUN with the broker's market).
 *
 * @param consumerKey        the Trade API consumer key (the token on the NEO app's Trade API card): sent only to the
 *                           scrip master's file-paths call ({@code Authorization}); never logged
 * @param product            MIS (intraday) or NRML; positions of other products are not ours
 * @param maxOrdersPerSecond our limiter's rate per account: Kotak allows 10 orders a second (scope unclear), SEBI's algo
 *                           threshold is also 10, so this stays below (8, like Kite)
 * @param placeLookupWait    after a place whose outcome is unknown: how long to wait before looking the order up by tag
 */
public record KotakConfig(String consumerKey, URI baseUrl, String product, Duration timeout, int maxOrdersPerSecond,
                          Duration placeLookupWait) {

    /** Kotak's domain: a base URL Kotak returns must be HTTPS on a host under it (UNCONFIRMED beyond the login's check). */
    public static final String REAL_DOMAIN = ".kotaksecurities.com";
    /** Every post-login call carries {@code neo-fin-key: neotradeapi} (SDK v3.0.13, OpenAlgo). */
    public static final String NEO_FIN_KEY = "neotradeapi";

    public KotakConfig {
        if (baseUrl == null) {
            throw new IllegalArgumentException("Kotak base URL is required (from the day's login)");
        }
        if (!trustedBase(baseUrl)) {
            throw new IllegalArgumentException("Kotak base URL must be https on " + REAL_DOMAIN.substring(1)
                    + " (or a local simulator): " + baseUrl.getHost());
        }
        if (!(maxOrdersPerSecond >= 1 && maxOrdersPerSecond < 10)) {
            throw new IllegalArgumentException("orders per second must be 1-9 (Kotak and SEBI: 10)");
        }
        consumerKey = consumerKey == null ? "" : consumerKey;
        product = product == null ? "MIS" : product;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        placeLookupWait = placeLookupWait == null ? Duration.ofSeconds(1) : placeLookupWait;
    }

    /** A real session's reads: MIS, 5 s timeouts, 8 orders a second. */
    public static KotakConfig session(String consumerKey, URI baseUrl) {
        return new KotakConfig(consumerKey, baseUrl, "MIS", Duration.ofSeconds(5), 8, null);
    }

    /** Against a local simulator: the same shape, short timeouts. */
    public static KotakConfig simulator(URI base) {
        return new KotakConfig("sim-consumer-key", base, "MIS", Duration.ofSeconds(2), 8, Duration.ofMillis(50));
    }

    /**
     * Where a token may be sent: HTTPS on Kotak's domain, or this host's loopback (the simulator; a token sent there never
     * leaves the machine). Plain HTTP to anywhere else, or any other host, is refused. The one rule for a Kotak base URL:
     * the login (KotakNeoLogin) stores only a base URL this accepts, and the adapter uses only such a one.
     */
    public static boolean trustedBase(URI base) {
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1")) {
            return "http".equals(base.getScheme()) || "https".equals(base.getScheme());
        }
        return "https".equals(base.getScheme()) && host.endsWith(REAL_DOMAIN);
    }

    /** Never shows the consumer key (code review 8 Nov 2026: a record's own toString would). */
    @Override
    public String toString() {
        return "KotakConfig[consumerKey=<redacted>, baseUrl=" + baseUrl + ", product=" + product + ", timeout=" + timeout
                + ", maxOrdersPerSecond=" + maxOrdersPerSecond + ", placeLookupWait=" + placeLookupWait + "]";
    }

    public boolean isRealBroker() {
        String host = baseUrl.getHost() == null ? "" : baseUrl.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.endsWith(REAL_DOMAIN);
    }
}
