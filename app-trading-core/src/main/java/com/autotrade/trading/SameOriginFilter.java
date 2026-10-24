package com.autotrade.trading;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cross-site request guard for every state-changing API call (code review 24 Oct 2026): the public site is protected
 * only by the nginx password gate, whose credentials a browser also attaches to requests another site triggers (a
 * hidden form posting to /api/execution/flatten-all or a kill switch). A POST, PUT, PATCH or DELETE under /api is
 * refused when the browser says it came from another site ({@code Sec-Fetch-Site} other than same-origin / none) or
 * its {@code Origin} host is not this request's host. Requests without either header (curl, scripts on the host)
 * pass; the UI's own calls are same-origin.
 */
@Component
@Order(0)
class SameOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String reason = refusal(request.getHeader("Sec-Fetch-Site"), request.getHeader("Origin"), request.getHeader("Host"));
        if (reason != null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, reason);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Why a state-changing request is refused, or null when it may pass. */
    static String refusal(String fetchSite, String origin, String host) {
        if (fetchSite != null && !fetchSite.equalsIgnoreCase("same-origin") && !fetchSite.equalsIgnoreCase("none")) {
            return "cross-site request refused (" + fetchSite + ")";
        }
        if (origin != null && !origin.isBlank()) {
            String originHost;
            try {
                originHost = URI.create(origin.trim()).getHost();
            } catch (IllegalArgumentException e) {
                return "cross-site request refused (bad Origin)";
            }
            if (originHost == null || host == null || !originHost.equalsIgnoreCase(hostOnly(host))) {
                return "cross-site request refused (Origin " + originHost + ")";
            }
        }
        return null;
    }

    private static String hostOnly(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon > 0 ? h.substring(0, colon) : h;
    }
}
