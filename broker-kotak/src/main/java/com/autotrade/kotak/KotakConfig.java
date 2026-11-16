package com.autotrade.kotak;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * One Kotak Neo session as an adapter sees it. {@code baseUrl} is the base URL Kotak returned at the day's login
 * (tradeApiValidate; per account, on kotaksecurities.com), or the local {@link KotakSimulator}'s address;
 * {@link #isRealBroker()} tells them apart.
 * <p>
 * Who may send orders is decided by how the config was made, never by its host (multi-broker production gaps C1, 16 Nov
 * 2026, as Upstox's review of 8 Nov): {@link #simulator} (a loopback simulator only) and {@link #live} (the explicit LIVE
 * connection, behind the LIVE guard) may; {@link #session} and the public constructor are read-only whatever their base,
 * and {@link KotakGateway} refuses every order call made through them.
 * <p>
 * Fields: {@code consumerKey}, the Trade API consumer key (the token on the NEO app's Trade API card), sent only to the
 * scrip master's file-paths call ({@code Authorization}), never logged; {@code product}, MIS (intraday) or NRML
 * (positions of other products are not ours); {@code maxOrdersPerSecond}, our limiter's rate per account (Kotak allows 10
 * orders a second, scope unclear; SEBI's algo threshold is also 10, so this stays below: 8, like Kite);
 * {@code placeLookupWait}, how long to wait after a place of unknown outcome before looking it up by tag;
 * {@code egress}, the account's own source address (production gaps B1: Kotak's session must be created from, and its
 * orders sent from, the client's registered static IP; null = the host's default route).
 */
public final class KotakConfig {

    /** Kotak's domain: a base URL Kotak returns must be HTTPS on a host under it (UNCONFIRMED beyond the login's check). */
    public static final String REAL_DOMAIN = ".kotaksecurities.com";
    /** Every post-login call carries {@code neo-fin-key: neotradeapi} (SDK v3.0.13, OpenAlgo). */
    public static final String NEO_FIN_KEY = "neotradeapi";

    /** What a gateway built on this config may do with order calls. */
    enum Orders {
        /** Reads only: every place, modify and cancel is refused. */
        NONE,
        /** A local simulator (loopback only): DRY_RUN and tests. */
        SIMULATOR,
        /** The LIVE connection (production gaps C1): only through {@link #live}, always behind the LIVE guard. */
        LIVE
    }

    private final String consumerKey;
    private final URI baseUrl;
    private final String product;
    private final Duration timeout;
    private final int maxOrdersPerSecond;
    private final Duration placeLookupWait;
    private final InetAddress egress;
    private final Orders orders;

    /** A read-only config (no order call is ever sent through it, whatever its base URL). */
    public KotakConfig(String consumerKey, URI baseUrl, String product, Duration timeout, int maxOrdersPerSecond,
                       Duration placeLookupWait) {
        this(consumerKey, baseUrl, product, timeout, maxOrdersPerSecond, placeLookupWait, null, Orders.NONE);
    }

    private KotakConfig(String consumerKey, URI baseUrl, String product, Duration timeout, int maxOrdersPerSecond,
                        Duration placeLookupWait, InetAddress egress, Orders orders) {
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
        if (orders == Orders.SIMULATOR && real(baseUrl)) {
            throw new IllegalArgumentException("a simulator config cannot name Kotak's own hosts");
        }
        this.consumerKey = consumerKey == null ? "" : consumerKey;
        this.baseUrl = baseUrl;
        this.product = product == null ? "MIS" : product;
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        this.maxOrdersPerSecond = maxOrdersPerSecond;
        this.placeLookupWait = placeLookupWait == null ? Duration.ofSeconds(1) : placeLookupWait;
        this.egress = egress;
        this.orders = Objects.requireNonNull(orders);
    }

    /** A real session's reads: MIS, 5 s timeouts, 8 orders a second; never sends an order. */
    public static KotakConfig session(String consumerKey, URI baseUrl) {
        return session(consumerKey, baseUrl, null);
    }

    /** A real session's reads from the account's egress address (null: the default route); never sends an order. */
    public static KotakConfig session(String consumerKey, URI baseUrl, InetAddress egress) {
        return new KotakConfig(consumerKey, baseUrl, "MIS", Duration.ofSeconds(5), 8, null, egress, Orders.NONE);
    }

    /**
     * The LIVE connection's config (production gaps C1, 16 Nov 2026): the only kind besides a simulator's whose gateway
     * places, modifies and cancels. MIS, 5 s timeouts, 8 orders a second, from the account's egress address. The base URL
     * is the session's (Kotak's domain; a loopback simulator in the LIVE-against-simulator tests, where nothing leaves
     * the machine). Built only by the Kotak adapter's {@code live()}, whose connection is always behind a LIVE guard.
     */
    public static KotakConfig live(String consumerKey, URI baseUrl, InetAddress egress) {
        return new KotakConfig(consumerKey, baseUrl, "MIS", Duration.ofSeconds(5), 8, null, egress, Orders.LIVE);
    }

    /** Against a local simulator (loopback only): the same shape, short timeouts; may send order calls to it. */
    public static KotakConfig simulator(URI base) {
        return new KotakConfig("sim-consumer-key", base, "MIS", Duration.ofSeconds(2), 8, Duration.ofMillis(50), null,
                Orders.SIMULATOR);
    }

    /**
     * Where a token may be sent: HTTPS on Kotak's domain, or this host's loopback (the simulator; a token sent there never
     * leaves the machine). Plain HTTP to anywhere else, or any other host, is refused. The one rule for a Kotak base URL:
     * the login (KotakNeoLogin) stores only a base URL this accepts, and the adapter uses only such a one.
     */
    public static boolean trustedBase(URI base) {
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1")) {
            return "http".equals(base.getScheme()) || "https".equals(base.getScheme());
        }
        return "https".equals(base.getScheme()) && host.endsWith(REAL_DOMAIN);
    }

    public String consumerKey() {
        return consumerKey;
    }

    public URI baseUrl() {
        return baseUrl;
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

    /** True for a {@link #simulator} or {@link #live} config: the only ones whose gateway sends order calls. */
    public boolean ordersAllowed() {
        return orders != Orders.NONE;
    }

    /** True for a {@link #live} config. */
    public boolean isLive() {
        return orders == Orders.LIVE;
    }

    /** True when order calls would reach Kotak itself: a {@link #live} config on Kotak's domain. */
    public boolean ordersReachKotak() {
        return orders == Orders.LIVE && real(baseUrl);
    }

    /** Whether {@code other} is the same kind of config (reads, simulator or LIVE), product and egress. */
    boolean sameKind(KotakConfig other) {
        return other.orders == orders && other.product.equals(product) && Objects.equals(other.egress, egress);
    }

    /** Never shows the consumer key (code review 8 Nov 2026). */
    @Override
    public String toString() {
        return "KotakConfig[consumerKey=<redacted>, baseUrl=" + baseUrl + ", product=" + product + ", timeout=" + timeout
                + ", maxOrdersPerSecond=" + maxOrdersPerSecond + ", placeLookupWait=" + placeLookupWait + ", egress="
                + (egress == null ? "default" : egress.getHostAddress()) + ", orders=" + orders + "]";
    }

    public boolean isRealBroker() {
        return real(baseUrl);
    }

    private static boolean real(URI u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(REAL_DOMAIN);
    }
}
