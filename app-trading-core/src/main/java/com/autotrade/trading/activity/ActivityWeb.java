package com.autotrade.trading.activity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The sign-in chain's side of activity recording: a sign-in (with the browser, its address and Keycloak's session), its
 * end (logout, the 12 h cap), and every request's "last seen" stamp. Nothing here may fail a sign-in or a request: every
 * step is guarded and only hands data to the {@link ActivityRecorder}'s queue.
 */
public final class ActivityWeb {

    private static final Logger log = LoggerFactory.getLogger(ActivityWeb.class);

    private final ActivityRecorder recorder;
    private final ClientAddress addresses;

    public ActivityWeb(ActivityRecorder recorder, ClientAddress addresses) {
        this.recorder = recorder;
        this.addresses = addresses;
    }

    public ActivityRecorder recorder() {
        return recorder;
    }

    /** The sign-in's key from its OIDC user (Keycloak's sid, else the user and auth_time); null for anything else. */
    static String keyOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            String sid = user.getIdToken() == null ? null : user.getIdToken().getClaimAsString("sid");
            Instant auth = user.getAuthenticatedAt() != null ? user.getAuthenticatedAt() : user.getIssuedAt();
            return ActivityRecorder.loginKey(sid, authentication.getName(), auth);
        }
        return null;
    }

    /** This request's sign-in key: kept in the session once known, else made from the signed-in user. */
    public String key(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null) {
                return null;
            }
            Object kept = session.getAttribute(ActivityRecorder.SESSION_KEY);
            if (kept instanceof String s) {
                return s;
            }
            String key = keyOf(SecurityContextHolder.getContext().getAuthentication());
            if (key != null) {
                session.setAttribute(ActivityRecorder.SESSION_KEY, key);
            }
            return key;
        } catch (IllegalStateException e) {                      // the session was invalidated during the request
            return null;
        }
    }

    /** At a successful sign-in (before the redirect back to the page). */
    public void signedIn(HttpServletRequest request, Authentication authentication) {
        if (!recorder.enabled()) {
            return;
        }
        try {
            String key = keyOf(authentication);
            if (key == null) {
                return;
            }
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.setAttribute(ActivityRecorder.SESSION_KEY, key);
            }
            OidcUser user = (OidcUser) authentication.getPrincipal();
            String ua = request.getHeader("User-Agent");
            UserAgents.Device device = UserAgents.parse(ua, request.getHeader("Sec-CH-UA"),
                    request.getHeader("Sec-CH-UA-Platform"), request.getHeader("Sec-CH-UA-Mobile"));
            recorder.signIn(key, authentication.getName(), user.getAuthenticatedAt(), addresses.resolve(request),
                    ClientAddress.chain(request), ua, device);
        } catch (RuntimeException e) {
            log.warn("activity: sign-in not recorded ({})", e.toString());
        }
    }

    /** A sign-in ended here: logout, or the maximum age. */
    public void ended(Authentication authentication, ActivityRecorder.EndReason reason) {
        try {
            recorder.end(keyOf(authentication), reason);
        } catch (RuntimeException e) {
            log.warn("activity: end of a sign-in not recorded ({})", e.toString());
        }
    }

    /** Stamps every signed-in request's time on its sign-in (runs after the authorization check). */
    public OncePerRequestFilter seenFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (recorder.enabled()) {
                    try {
                        recorder.seen(key(request));
                    } catch (RuntimeException e) {
                        // never fail a request for this
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }
}
