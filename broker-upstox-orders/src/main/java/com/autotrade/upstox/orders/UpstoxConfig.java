package com.autotrade.upstox.orders;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * One Upstox session as an order adapter sees it: where its calls go. Upstox splits its API over hosts (official docs,
 * 2026; SDK 1.29's base paths): order calls (v3 place / modify / cancel) on {@link #REAL_ORDER_API}, every read (order
 * book, history, trades, positions, funds) on {@link #REAL_READ_API}, and the daily instrument file on
 * {@link #REAL_ASSETS} (public, no token). A local {@link UpstoxSimulator} serves all three on one address;
 * {@link #isRealBroker()} tells them apart. This build has no LIVE Upstox connection: a real host is only ever read
 * (DRY_RUN with the broker's market), and {@link UpstoxGateway} refuses order calls to it outright.
 * <p>
 * The member's own Upstox app and login only: zt-tiger-v2's Upstox token and app are never used here (its user must never
 * be a LIVE account: registering a static IP for it would log zt out).
 *
 * @param orderBase          v3 order calls (api-hft.upstox.com)
 * @param readBase           v2/v3 reads (api.upstox.com)
 * @param assetsBase         the instrument files (assets.upstox.com)
 * @param product            I (intraday) or D; positions of other products are not ours
 * @param maxOrdersPerSecond our limiter's rate per account: Upstox allows 10 orders a second (also 500 a minute and 2,000
 *                           in 30 minutes per user), SEBI's algo threshold is 10, so this stays below (8, like Kite)
 * @param placeLookupWait    after a place whose outcome is unknown: how long to wait before looking the order up by tag
 */
public record UpstoxConfig(URI orderBase, URI readBase, URI assetsBase, String product, Duration timeout,
                           int maxOrdersPerSecond, Duration placeLookupWait) {

    public static final URI REAL_ORDER_API = URI.create("https://api-hft.upstox.com");
    public static final URI REAL_READ_API = URI.create("https://api.upstox.com");
    public static final URI REAL_ASSETS = URI.create("https://assets.upstox.com");
    /** Upstox's domain: a token is only ever sent to HTTPS on a host under it, or to this host's loopback. */
    public static final String REAL_DOMAIN = ".upstox.com";
    /** Intraday (MIS). */
    public static final String INTRADAY = "I";

    public UpstoxConfig {
        if (orderBase == null || readBase == null || assetsBase == null) {
            throw new IllegalArgumentException("Upstox order, read and assets bases are required");
        }
        for (URI u : new URI[] {orderBase, readBase, assetsBase}) {
            if (!trustedBase(u)) {
                throw new IllegalArgumentException("Upstox base must be https on " + REAL_DOMAIN.substring(1)
                        + " (or a local simulator): " + u.getHost());
            }
        }
        if (!(maxOrdersPerSecond >= 1 && maxOrdersPerSecond < 10)) {
            throw new IllegalArgumentException("orders per second must be 1-9 (Upstox and SEBI: 10)");
        }
        product = product == null ? INTRADAY : product;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        placeLookupWait = placeLookupWait == null ? Duration.ofSeconds(1) : placeLookupWait;
    }

    /** A real session's reads: intraday, 5 s timeouts, 8 orders a second. */
    public static UpstoxConfig real() {
        return new UpstoxConfig(REAL_ORDER_API, REAL_READ_API, REAL_ASSETS, INTRADAY, Duration.ofSeconds(5), 8, null);
    }

    /** Every call to one base (a local simulator standing for Upstox): the same shape, a real session's timeouts. */
    public static UpstoxConfig at(URI base) {
        return new UpstoxConfig(base, base, base, INTRADAY, Duration.ofSeconds(5), 8, null);
    }

    /** Against a local simulator: short timeouts. */
    public static UpstoxConfig simulator(URI base) {
        return new UpstoxConfig(base, base, base, INTRADAY, Duration.ofSeconds(2), 8, Duration.ofMillis(50));
    }

    /**
     * Where a token may be sent: HTTPS on Upstox's domain, or this host's loopback (the simulator; a token sent there never
     * leaves the machine). Plain HTTP to anywhere else, or any other host, is refused.
     */
    public static boolean trustedBase(URI base) {
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1")) {
            return "http".equals(base.getScheme()) || "https".equals(base.getScheme());
        }
        return "https".equals(base.getScheme()) && host.endsWith(REAL_DOMAIN);
    }

    /** True when any base is Upstox's own host (never the case for a DRY_RUN order connection). */
    public boolean isRealBroker() {
        return real(orderBase) || real(readBase) || real(assetsBase);
    }

    /** True when order calls would reach Upstox itself. */
    public boolean ordersReachUpstox() {
        return real(orderBase);
    }

    private static boolean real(URI u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(REAL_DOMAIN);
    }
}
