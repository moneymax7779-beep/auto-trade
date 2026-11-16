package com.autotrade.groww;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * One Groww session as an order adapter sees it: where its calls go. Groww's Trade API (official docs
 * groww.in/trade-api/docs/curl, SDK growwapi 1.5.0) serves every order call and read on {@link #REAL_API}, and the
 * instrument CSV on {@link #REAL_ASSETS} (public, no token). A local {@link GrowwSimulator} serves both on one address;
 * {@link #isRealBroker()} tells them apart.
 * <p>
 * Who may send orders is decided by how the config was made, never by its host (production gaps C1, 16 Nov 2026; until
 * then any config whose API host was not Groww's could send): {@link #simulator} (a loopback simulator), {@link #live}
 * (the LIVE connection, Groww's hosts, behind the LIVE guard) and {@link #liveSimulator} (the LIVE kind against a loopback
 * simulator, for tests) may; {@link #real}, {@link #at} and the public constructor are read-only whatever their hosts,
 * and {@link GrowwGateway} refuses order calls through them outright.
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
 * @param egress               the account's own source address (production gaps B1: Groww takes orders only from the
 *                             client's registered static IP); null = the host's default route
 */
public final class GrowwConfig {

    public static final URI REAL_API = URI.create("https://api.groww.in");
    public static final URI REAL_ASSETS = URI.create("https://growwapi-assets.groww.in");
    /** Groww's domain: a token is only ever sent to HTTPS on groww.in or a host under it, or to this host's loopback. */
    public static final String REAL_DOMAIN = "groww.in";
    /** Intraday. */
    public static final String INTRADAY = "MIS";
    /** Groww: non-trading calls 20 a second and 500 a minute; our reads stay below. */
    public static final int READS_PER_SECOND = 15, READS_PER_MINUTE = 400;
    public static final int ORDER_PAGE_MAX = 100, TRADE_PAGE_MAX = 50;

    /** What a gateway built on this config may do with order calls. */
    enum Orders {
        /** Reads only. */
        NONE,
        /** A local simulator (loopback only): DRY_RUN and tests. */
        SIMULATOR,
        /** The LIVE connection (Groww's hosts, or a loopback simulator in the LIVE-against-simulator tests). */
        LIVE
    }

    private final URI apiBase;
    private final URI assetsBase;
    private final String product;
    private final Duration timeout;
    private final int maxOrdersPerSecond;
    private final int maxOrdersPerMinute;
    private final Duration placeLookupWait;
    private final int pageSize;
    private final InetAddress egress;
    private final Orders orders;

    /** A read-only config (no order call is ever sent through it, whatever its hosts). */
    public GrowwConfig(URI apiBase, URI assetsBase, String product, Duration timeout, int maxOrdersPerSecond,
                       int maxOrdersPerMinute, Duration placeLookupWait, int pageSize) {
        this(apiBase, assetsBase, product, timeout, maxOrdersPerSecond, maxOrdersPerMinute, placeLookupWait, pageSize,
                null, Orders.NONE);
    }

    private GrowwConfig(URI apiBase, URI assetsBase, String product, Duration timeout, int maxOrdersPerSecond,
                        int maxOrdersPerMinute, Duration placeLookupWait, int pageSize, InetAddress egress,
                        Orders orders) {
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
        if (orders == Orders.SIMULATOR && (real(apiBase) || real(assetsBase))) {
            throw new IllegalArgumentException("a simulator config cannot name Groww's own hosts");
        }
        this.apiBase = apiBase;
        this.assetsBase = assetsBase;
        this.product = product == null ? INTRADAY : product;
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        this.maxOrdersPerSecond = maxOrdersPerSecond;
        this.maxOrdersPerMinute = maxOrdersPerMinute;
        this.placeLookupWait = placeLookupWait == null ? Duration.ofSeconds(1) : placeLookupWait;
        this.pageSize = pageSize;
        this.egress = egress;
        this.orders = Objects.requireNonNull(orders);
    }

    public URI apiBase() {
        return apiBase;
    }

    public URI assetsBase() {
        return assetsBase;
    }

    public String product() {
        return product;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxOrdersPerSecond() {
        return maxOrdersPerSecond;
    }

    public int maxOrdersPerMinute() {
        return maxOrdersPerMinute;
    }

    public Duration placeLookupWait() {
        return placeLookupWait;
    }

    public int pageSize() {
        return pageSize;
    }

    /** The account's source address; null = the host's default route. */
    public InetAddress egress() {
        return egress;
    }

    /** True for a {@link #simulator}, {@link #live} or {@link #liveSimulator} config: the only ones that send orders. */
    public boolean ordersAllowed() {
        return orders != Orders.NONE;
    }

    /** True for a {@link #live} or {@link #liveSimulator} config. */
    public boolean isLive() {
        return orders == Orders.LIVE;
    }

    /** The same config (its kind kept: a read config stays read-only), its calls leaving from {@code egress}. */
    public GrowwConfig withEgress(InetAddress egress) {
        return new GrowwConfig(apiBase, assetsBase, product, timeout, maxOrdersPerSecond, maxOrdersPerMinute,
                placeLookupWait, pageSize, egress, orders);
    }

    /** A real session's reads: MIS, 5 s timeouts, 8 orders a second, 200 a minute, pages of 100. */
    public static GrowwConfig real() {
        return new GrowwConfig(REAL_API, REAL_ASSETS, INTRADAY, Duration.ofSeconds(5), 8, 200, null, ORDER_PAGE_MAX);
    }

    /** Every call to one base (a local simulator standing for Groww): the same shape, a real session's timeouts. */
    public static GrowwConfig at(URI base) {
        return new GrowwConfig(base, base, INTRADAY, Duration.ofSeconds(5), 8, 200, null, ORDER_PAGE_MAX);
    }

    /** Against a local simulator: short timeouts; may send order calls to it. */
    public static GrowwConfig simulator(URI base) {
        return new GrowwConfig(base, base, INTRADAY, Duration.ofSeconds(2), 8, 200, Duration.ofMillis(50), ORDER_PAGE_MAX,
                null, Orders.SIMULATOR);
    }

    /**
     * The LIVE connection's config (production gaps C1, 16 Nov 2026): Groww's own hosts, MIS, 5 s timeouts, 8 orders a
     * second, 200 a minute, pages of 100. Used only by the Groww adapter's {@code live()}, behind the LIVE guard, with the
     * account's egress address ({@link #withEgress}).
     */
    public static GrowwConfig live() {
        return new GrowwConfig(REAL_API, REAL_ASSETS, INTRADAY, Duration.ofSeconds(5), 8, 200, null, ORDER_PAGE_MAX, null,
                Orders.LIVE);
    }

    /**
     * A LIVE config against a local simulator (tests of the LIVE connection end to end): the LIVE kind on one loopback
     * base, so nothing it sends can leave this machine; any other host is refused.
     */
    public static GrowwConfig liveSimulator(URI base) {
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1"))) {
            throw new IllegalArgumentException("a LIVE-against-simulator config takes a loopback base only: " + host);
        }
        return new GrowwConfig(base, base, INTRADAY, Duration.ofSeconds(2), 8, 200, Duration.ofMillis(50), ORDER_PAGE_MAX,
                null, Orders.LIVE);
    }

    /** The same with another order-list page size (tests of the pagination). */
    public GrowwConfig withPageSize(int size) {
        return new GrowwConfig(apiBase, assetsBase, product, timeout, maxOrdersPerSecond, maxOrdersPerMinute,
                placeLookupWait, size, egress, orders);
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
