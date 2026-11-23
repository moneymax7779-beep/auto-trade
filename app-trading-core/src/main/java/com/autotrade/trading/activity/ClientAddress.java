package com.autotrade.trading.activity;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The browser's own address behind our proxies (user activity plan, 23 Nov 2026). X-Forwarded-For is read right to
 * left and the first address that is not one of our proxies is the client's: anything to its left was sent by the
 * browser itself and could be made up. On GCP the chain arrives as {@code [what the browser sent,] client, load
 * balancer's address, Google front end} (host nginx appends the front end), so the load balancer's address and Google's
 * front-end ranges are configured as trusted. Only address literals are parsed (never a DNS lookup).
 */
public final class ClientAddress {

    /** Loopback, private ranges and Google's load-balancer front ends (35.191.0.0/16, 130.211.0.0/22). */
    public static final List<String> DEFAULT_TRUSTED = List.of("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16", "35.191.0.0/16", "130.211.0.0/22", "::1/128", "fc00::/7");

    private static final Pattern LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");

    private record Cidr(byte[] network, int bits) {
        boolean contains(InetAddress a) {
            byte[] b = a.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = bits / 8, rest = bits % 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (b[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Cidr> trusted;

    /** @param trusted CIDRs (or single addresses) of our own proxies; null or empty: {@link #DEFAULT_TRUSTED} */
    public ClientAddress(List<String> trusted) {
        List<Cidr> out = new ArrayList<>();
        for (String c : trusted == null || trusted.isEmpty() ? DEFAULT_TRUSTED : trusted) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String[] parts = c.trim().split("/", 2);
            InetAddress a = literal(parts[0]);
            if (a == null) {
                throw new IllegalArgumentException("trusted proxy is not an address or CIDR: " + c);
            }
            int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : a.getAddress().length * 8;
            if (bits < 0 || bits > a.getAddress().length * 8) {
                throw new IllegalArgumentException("trusted proxy prefix out of range: " + c);
            }
            out.add(new Cidr(a.getAddress(), bits));
        }
        this.trusted = List.copyOf(out);
    }

    /**
     * The client's address: the rightmost entry of {@code forwardedFor} that is not trusted; when every entry is trusted
     * (a call from the host itself), the leftmost; with no header, {@code remoteAddr}. Null when nothing parses.
     */
    public String resolve(String forwardedFor, String remoteAddr) {
        InetAddress peer = literal(remoteAddr);
        if (forwardedFor == null || forwardedFor.isBlank() || (peer != null && !trusted(peer))) {
            return peer == null ? null : peer.getHostAddress();    // no proxy in front: the TCP peer is the client
        }
        String[] hops = forwardedFor.split(",");
        InetAddress leftmost = null;
        for (int i = hops.length - 1; i >= 0; i--) {
            InetAddress a = literal(hops[i]);
            if (a == null) {
                continue;
            }
            leftmost = a;
            if (!trusted(a)) {
                return a.getHostAddress();
            }
        }
        return leftmost != null ? leftmost.getHostAddress() : peer == null ? null : peer.getHostAddress();
    }

    /** The client's address of this request (the raw header, under any wrapper that hides it). */
    public String resolve(HttpServletRequest request) {
        ServletRequest raw = innermost(request);
        String xff = raw instanceof HttpServletRequest h ? h.getHeader("X-Forwarded-For") : null;
        return resolve(xff, raw.getRemoteAddr());
    }

    /** The X-Forwarded-For chain as received (kept with the sign-in so the rule can be checked), at most 200 chars. */
    public static String chain(HttpServletRequest request) {
        ServletRequest raw = innermost(request);
        String xff = raw instanceof HttpServletRequest h ? h.getHeader("X-Forwarded-For") : null;
        return xff == null ? null : xff.length() <= 200 ? xff : xff.substring(0, 200);
    }

    /** Spring's forwarded-header filter hides X-Forwarded-For behind a wrapper: read the request it wraps. */
    static ServletRequest innermost(ServletRequest request) {
        ServletRequest r = request;
        while (r instanceof ServletRequestWrapper w) {
            r = w.getRequest();
        }
        return r;
    }

    private boolean trusted(InetAddress a) {
        for (Cidr c : trusted) {
            if (c.contains(a)) {
                return true;
            }
        }
        return false;
    }

    /** An IPv4 or IPv6 literal (a port or brackets removed), or null; never resolves a name. */
    static InetAddress literal(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("[")) {                                   // [v6]:port
            int end = t.indexOf(']');
            t = end > 0 ? t.substring(1, end) : t;
        } else if (t.indexOf(':') == t.lastIndexOf(':') && t.indexOf(':') > 0 && t.contains(".")) {
            t = t.substring(0, t.indexOf(':'));                    // v4:port
        }
        boolean v4 = IPV4.matcher(t).matches(), v6 = t.contains(":") && LITERAL.matcher(t).matches();
        if (!v4 && !v6) {
            return null;
        }
        try {
            return InetAddress.getByName(t);                       // a literal: no lookup
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
