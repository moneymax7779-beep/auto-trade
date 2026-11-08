package com.autotrade.groww;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * One Groww session as an order adapter sees it: where its calls go. Groww's Trade API (official docs
 * groww.in/trade-api/docs/curl, SDK growwapi 1.5.0) serves every order call and read on {@link #REAL_API}, and the
 * instrument CSV on {@link #REAL_ASSETS} (public, no token). A local {@link GrowwSimulator} serves both on one address;
 * {@link #isRealBroker()} tells them apart. This build has no LIVE Groww connection: a real host is only ever read (DRY_RUN
 * with the broker's market), and {@link GrowwGateway} refuses order calls to it outright.
 *
 * @param apiBase              orders and reads (api.groww.in)
 * @param assetsBase           the instrument CSV (growwapi-assets.groww.in)
 * @param product              MIS (intraday); positions of other products are not ours
 * @param maxOrdersPerSecond   our order limiter's rate per account: Groww allows 10 orders a second (and 250 a minute),
 *                             SEBI's algo threshold is 10, so this stays below (8, like Kite)
 * @param maxOrdersPerMinute   our order limiter's minute cap: Groww allows 250 a minute, so this stays below (200)
 * @param placeLookupWait      after a place whose outcome is unknown: how long to wait before looking it up by reference
 * @param pageSize             the order list's page size (Groww: page_size up to 100, UNCONFIRMED as the maximum); the
 *                             per-order trade list uses at most {@link #TRADE_PAGE_MAX}
 */
public record GrowwConfig(URI apiBase, URI assetsBase, String product, Duration timeout, int maxOrdersPerSecond,
                          int maxOrdersPerMinute, Duration placeLookupWait, int pageSize) {

    public static final URI REAL_API = URI.create("https://api.groww.in");
    public static final URI REAL_ASSETS = URI.create("https://growwapi-assets.groww.in");
    /** Groww's domain: a token is only ever sent to HTTPS on groww.in or a host under it, or to this host's loopback. */
    public static final String REAL_DOMAIN = "groww.in";
    /** Intraday. */
    public static final String INTRADAY = "MIS";
    /** Groww: non-trading calls 20 a second and 500 a minute; our reads stay below. */
    public static final int READS_PER_SECOND = 15, READS_PER_MINUTE = 400;
    public static final int ORDER_PAGE_MAX = 100, TRADE_PAGE_MAX = 50;

    public GrowwConfig {
        if (apiBase == null || assetsBase == null) {
            throw new IllegalArgumentException("Groww API and assets bases are required");
        }
        for (URI u : new URI[] {apiBase, assetsBase}) {
            if (!trustedBase(u)) {
                throw new IllegalArgumentException("Groww base must be https on " + REAL_DOMAIN
                        + " (or a local simulator): " + u.getHost());
            }
        }
        if (!(maxOrdersPerSecond >= 1 && maxOrdersPerSecond < 10)) {
            throw new IllegalArgumentException("orders per second must be 1-9 (Groww and SEBI: 10)");
        }
        if (!(maxOrdersPerMinute >= 1 && maxOrdersPerMinute < 250)) {
            throw new IllegalArgumentException("orders per minute must be 1-249 (Groww: 250)");
        }
        if (!(pageSize >= 1 && pageSize <= ORDER_PAGE_MAX)) {
            throw new IllegalArgumentException("page size must be 1-" + ORDER_PAGE_MAX);
        }
        product = product == null ? INTRADAY : product;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        placeLookupWait = placeLookupWait == null ? Duration.ofSeconds(1) : placeLookupWait;
    }

    /** A real session's reads: MIS, 5 s timeouts, 8 orders a second, 200 a minute, pages of 100. */
    public static GrowwConfig real() {
        return new GrowwConfig(REAL_API, REAL_ASSETS, INTRADAY, Duration.ofSeconds(5), 8, 200, null, ORDER_PAGE_MAX);
    }

    /** Every call to one base (a local simulator standing for Groww): the same shape, a real session's timeouts. */
    public static GrowwConfig at(URI base) {
        return new GrowwConfig(base, base, INTRADAY, Duration.ofSeconds(5), 8, 200, null, ORDER_PAGE_MAX);
    }

    /** Against a local simulator: short timeouts. */
    public static GrowwConfig simulator(URI base) {
        return new GrowwConfig(base, base, INTRADAY, Duration.ofSeconds(2), 8, 200, Duration.ofMillis(50), ORDER_PAGE_MAX);
    }

    /** The same with another order-list page size (tests of the pagination). */
    public GrowwConfig withPageSize(int size) {
        return new GrowwConfig(apiBase, assetsBase, product, timeout, maxOrdersPerSecond, maxOrdersPerMinute,
                placeLookupWait, size);
    }

    /** The per-order trade list's page size. */
    public int tradePageSize() {
        return Math.min(pageSize, TRADE_PAGE_MAX);
    }

    /**
     * Where a token may be sent: HTTPS on Groww's domain, or this host's loopback (the simulator; a token sent there never
     * leaves the machine). Plain HTTP to anywhere else, or any other host, is refused.
     */
    public static boolean trustedBase(URI base) {
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1")) {
            return "http".equals(base.getScheme()) || "https".equals(base.getScheme());
        }
        return "https".equals(base.getScheme()) && real(base);
    }

    /** True when any base is Groww's own host (never the case for a DRY_RUN order connection). */
    public boolean isRealBroker() {
        return real(apiBase) || real(assetsBase);
    }

    /** True when order calls would reach Groww itself. */
    public boolean ordersReachGroww() {
        return real(apiBase);
    }

    /** True when reads go to Groww itself (they are then throttled to Groww's read limits; a simulator's are not). */
    public boolean readsReachGroww() {
        return real(apiBase);
    }

    private static boolean real(URI u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        return host.equals(REAL_DOMAIN) || host.endsWith("." + REAL_DOMAIN);
    }
}
