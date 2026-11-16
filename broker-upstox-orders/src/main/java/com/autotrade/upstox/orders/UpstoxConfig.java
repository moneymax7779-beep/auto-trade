package com.autotrade.upstox.orders;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * One Upstox session as an order adapter sees it: where its calls go. Upstox splits its API over hosts (official docs,
 * 2026; SDK 1.29's base paths): order calls (v3 place / modify / cancel) on {@link #REAL_ORDER_API}, every read (order
 * book, history, trades, positions, funds) on {@link #REAL_READ_API}, and the daily instrument file on
 * {@link #REAL_ASSETS} (public, no token). A local {@link UpstoxSimulator} serves all three on one address;
 * {@link #isRealBroker()} tells them apart. A read config ({@link #real}, {@link #at}) is only ever read (DRY_RUN with
 * the broker's market), and {@link UpstoxGateway} refuses order calls through it outright; only {@link #live} (the LIVE
 * connection, production gaps C1, 16 Nov 2026, always behind the LIVE guard) sends orders to Upstox.
 * <p>
 * The member's own Upstox app and login only: zt-tiger-v2's Upstox token and app are never used here (its user must never
 * be a LIVE account: registering a static IP for it would log zt out).
 *
 * <p>
 * Order calls are allowed only for a config made by {@link #simulator}, {@link #live} or {@link #liveSimulator}
 * ({@link #ordersAllowed()}); {@link #real}, {@link #at} and the public constructor are read-only whatever their hosts,
 * and {@link #withEgress} keeps a config's kind.
 *
 * @param orderBase          v3 order calls (api-hft.upstox.com)
 * @param readBase           v2/v3 reads (api.upstox.com)
 * @param assetsBase         the instrument files (assets.upstox.com)
 * @param product            I (intraday) or D; positions of other products are not ours
 * @param maxOrdersPerSecond our limiter's rate per account: Upstox allows 10 orders a second (also 500 a minute and 2,000
 *                           in 30 minutes per user), SEBI's algo threshold is 10, so this stays below (8, like Kite)
 * @param placeLookupWait    after a place whose outcome is unknown: how long to wait before looking the order up by tag
 * @param egress             the account's own source address (production gaps B1: Upstox takes orders only from the
 *                           user's registered static IP); null = the host's default route
 */
public final class UpstoxConfig {

    public static final URI REAL_ORDER_API = URI.create("https://api-hft.upstox.com");
    public static final URI REAL_READ_API = URI.create("https://api.upstox.com");
    public static final URI REAL_ASSETS = URI.create("https://assets.upstox.com");
    /** Upstox's domain: a token is only ever sent to HTTPS on a host under it, or to this host's loopback. */
    public static final String REAL_DOMAIN = ".upstox.com";
    /** Intraday (MIS). */
    public static final String INTRADAY = "I";

    private final URI orderBase;
    private final URI readBase;
    private final URI assetsBase;
    private final String product;
    private final Duration timeout;
    private final int maxOrdersPerSecond;
    private final Duration placeLookupWait;
    private final InetAddress egress;
    /**
     * Set only by {@link #simulator}, {@link #live} and {@link #liveSimulator}: the kinds of config whose gateway may
     * place, modify or cancel (review 8 Nov 2026: not decided by the host, so no other constructor, however its bases
     * look, can send an order anywhere).
     */
    private final Orders orders;

    /** What a gateway built on this config may do with order calls. */
    enum Orders {
        /** Reads only. */
        NONE,
        /** A local simulator (loopback only): DRY_RUN and tests. */
        SIMULATOR,
        /** The LIVE connection (Upstox's hosts, or a loopback simulator in the LIVE-against-simulator tests). */
        LIVE
    }

    public UpstoxConfig(URI orderBase, URI readBase, URI assetsBase, String product, Duration timeout,
                        int maxOrdersPerSecond, Duration placeLookupWait) {
        this(orderBase, readBase, assetsBase, product, timeout, maxOrdersPerSecond, placeLookupWait, null, Orders.NONE);
    }

    private UpstoxConfig(URI orderBase, URI readBase, URI assetsBase, String product, Duration timeout,
                         int maxOrdersPerSecond, Duration placeLookupWait, InetAddress egress, Orders orders) {
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
        if (orders == Orders.SIMULATOR && (real(orderBase) || real(readBase) || real(assetsBase))) {
            throw new IllegalArgumentException("a simulator config cannot name Upstox's own hosts");
        }
        this.orderBase = orderBase;
        this.readBase = readBase;
        this.assetsBase = assetsBase;
        this.product = product == null ? INTRADAY : product;
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        this.maxOrdersPerSecond = maxOrdersPerSecond;
        this.placeLookupWait = placeLookupWait == null ? Duration.ofSeconds(1) : placeLookupWait;
        this.egress = egress;
        this.orders = orders;
    }

    public URI orderBase() {
        return orderBase;
    }

    public URI readBase() {
        return readBase;
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

    public Duration placeLookupWait() {
        return placeLookupWait;
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
    public UpstoxConfig withEgress(InetAddress egress) {
        return new UpstoxConfig(orderBase, readBase, assetsBase, product, timeout, maxOrdersPerSecond, placeLookupWait,
                egress, orders);
    }

    /** A real session's reads: intraday, 5 s timeouts, 8 orders a second. */
    public static UpstoxConfig real() {
        return new UpstoxConfig(REAL_ORDER_API, REAL_READ_API, REAL_ASSETS, INTRADAY, Duration.ofSeconds(5), 8, null);
    }

    /** Every call to one base (a local simulator standing for Upstox): the same shape, a real session's timeouts. */
    public static UpstoxConfig at(URI base) {
        return new UpstoxConfig(base, base, base, INTRADAY, Duration.ofSeconds(5), 8, null);
    }

    /** Against a local simulator: short timeouts; may send order calls to it. */
    public static UpstoxConfig simulator(URI base) {
        return new UpstoxConfig(base, base, base, INTRADAY, Duration.ofSeconds(2), 8, Duration.ofMillis(50), null,
                Orders.SIMULATOR);
    }

    /**
     * The LIVE connection's config (production gaps C1, 16 Nov 2026): Upstox's own hosts, intraday, 5 s timeouts, 8
     * orders a second; orders go to api-hft.upstox.com. Used only by the Upstox adapter's {@code live()}, behind the LIVE
     * guard, with the account's egress address ({@link #withEgress}).
     */
    public static UpstoxConfig live() {
        return new UpstoxConfig(REAL_ORDER_API, REAL_READ_API, REAL_ASSETS, INTRADAY, Duration.ofSeconds(5), 8, null,
                null, Orders.LIVE);
    }

    /**
     * A LIVE config against a local simulator (tests of the LIVE connection end to end): the LIVE kind on one loopback
     * base, so nothing it sends can leave this machine; any other host is refused.
     */
    public static UpstoxConfig liveSimulator(URI base) {
        if (!loopback(base)) {
            throw new IllegalArgumentException("a LIVE-against-simulator config takes a loopback base only: "
                    + base.getHost());
        }
        return new UpstoxConfig(base, base, base, INTRADAY, Duration.ofSeconds(2), 8, Duration.ofMillis(50), null,
                Orders.LIVE);
    }

    private static boolean loopback(URI base) {
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
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
