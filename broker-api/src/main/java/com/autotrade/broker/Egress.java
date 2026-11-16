package com.autotrade.broker;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * The source address a broker account's HTTP calls leave from (multi-broker production gaps B1, 16 Nov 2026): since 1 Apr
 * 2026 an algo order must come from the static IP registered for that client with the broker (Kotak also refuses a
 * session made from another IP, stCode 1037; Upstox allows one IP per user and changing it logs every session out;
 * Groww takes orders only from a registered IP). Each LIVE account gets its own address on the order host, and every
 * HTTP client built for that account (reads, orders, instrument downloads, the daily login) binds to it through
 * {@link HttpClient.Builder#localAddress}. A null address is the host's default route, exactly as before.
 * <p>
 * An address is only ever an IPv4 or IPv6 literal ({@link #parse}): never a host name, so no DNS lookup can choose it.
 * Before a route uses one it is checked to be assigned to an interface of this host ({@link #assigned}); if it is not,
 * the account's route fails closed, and a client bound to it cannot connect at all: there is never a silent fallback to
 * the default route.
 */
public final class Egress {

    /** Four decimal octets 0-255, no leading zeros (an octal-looking "010" is refused, never read as 8 or 10). */
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");
    /** Hex groups and colons, with an optional dotted IPv4 tail; no zone id ("%en0"), no brackets. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]+(:[0-9.]+)?");

    private Egress() {
    }

    /**
     * An IPv4 or IPv6 literal as an address, without any DNS lookup. Refused (IllegalArgumentException): a blank value, a
     * host name, a zone id, an IPv4 with leading zeros or out-of-range octets, the wildcard (0.0.0.0, ::), a multicast or
     * broadcast address. The checks run before {@link InetAddress#getByName}, which then only parses.
     */
    public static InetAddress parse(String literal) {
        String v = literal == null ? "" : literal.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("egress address is blank");
        }
        boolean v4 = IPV4.matcher(v).matches();
        boolean v6 = !v4 && v.indexOf(':') >= 0 && IPV6.matcher(v).matches();
        if (!v4 && !v6) {
            throw new IllegalArgumentException("egress address must be an IPv4 or IPv6 literal (no host names): " + v);
        }
        InetAddress a;
        try {
            a = InetAddress.getByName(v);                   // a literal: parsed, never looked up
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("egress address is not a valid IP literal: " + v, e);
        }
        if (v4 && !(a instanceof Inet4Address)) {
            throw new IllegalArgumentException("egress address is not a valid IP literal: " + v);
        }
        if (a.isAnyLocalAddress() || a.isMulticastAddress() || "255.255.255.255".equals(a.getHostAddress())) {
            throw new IllegalArgumentException("egress address cannot be the wildcard, multicast or broadcast: " + v);
        }
        if (a instanceof Inet6Address six && six.getScopeId() != 0) {
            throw new IllegalArgumentException("egress address cannot carry a zone id: " + v);
        }
        return a;
    }

    /** Whether {@code address} is assigned to an interface of this host now (false when the interfaces cannot be read). */
    public static boolean assigned(InetAddress address) {
        if (address == null) {
            return false;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            return false;
        }
    }

    /**
     * An HTTP client with the codebase's connect timeout, bound to {@code egress} when it is not null; with a null egress
     * exactly {@code HttpClient.newBuilder().connectTimeout(connectTimeout).build()} (an account without an egress keeps
     * the host's default route, unchanged).
     */
    public static HttpClient client(Duration connectTimeout, InetAddress egress) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (egress != null) {
            b.localAddress(egress);
        }
        return b.build();
    }

    /** The address as text for messages and logs ("default route" for none). */
    public static String describe(InetAddress egress) {
        return egress == null ? "default route" : egress.getHostAddress();
    }
}
