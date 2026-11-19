package com.autotrade.exec.app;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.autotrade.execution.UserSignature;

/**
 * Account-management review, 19 Nov 2026: with {@code AUTOTRADE_PROXY_SECRET} set, a request that names a user
 * ({@link Access#HEADER}) is served only with the UI proxy's signature over it ({@link UserSignature}); anything else on
 * the network naming a user gets 401. Requests naming no user (status, health, the login board, the watchdogs) pass as
 * before. Without the secret nothing changes (a local or single-user setup), and the start-up log says so.
 */
@Component
class SignedUserFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SignedUserFilter.class);

    /** The most a signed request's body may be (the UI's JSON is a few KB). */
    static final int MAX_BODY = 1 << 20;

    private final String secret;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    SignedUserFilter(@Value("${autotrade.proxy-secret:${AUTOTRADE_PROXY_SECRET:}}") String secret) {
        this(secret, Clock.systemUTC());
    }

    SignedUserFilter(String secret, Clock clock) {
        this.secret = secret == null || secret.isBlank() ? null : secret.trim();
        this.clock = clock;
        log.info("signed user header: {}", this.secret == null ? "OFF (no AUTOTRADE_PROXY_SECRET): " + Access.HEADER
                + " is trusted as sent" : "ON (a request naming a user needs the proxy's signature)");
    }

    boolean on() {
        return secret != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getHeader(Access.HEADER);
        if (secret == null || user == null) {
            chain.doFilter(request, response);
            return;
        }
        // review 19 Nov 2026: nothing is buffered for a request that is unsigned or too big (the UI sends small JSON)
        if (request.getHeader(UserSignature.SIG) == null || request.getHeader(UserSignature.TS) == null) {
            refuse(request, response, user, "the user is not signed");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            refuse(request, response, user, "the body is over " + MAX_BODY + " bytes");
            return;
        }
        String query = request.getQueryString();
        String refused = UserSignature.refusal(secret, user, request.getHeader(UserSignature.TS),
                request.getHeader(UserSignature.SIG), request.getMethod(),
                request.getRequestURI() + (query == null ? "" : "?" + query), body, clock.instant().getEpochSecond());
        if (refused != null) {
            refuse(request, response, user, refused);
            return;
        }
        chain.doFilter(new CachedBody(request, body), response);
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response, String user, String why)
            throws IOException {
        log.warn("refused {} {} naming user {} from {}: {}", request.getMethod(), request.getRequestURI(), user,
                request.getRemoteAddr(), why);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getOutputStream().write(("{\"error\":\"" + why + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** The request with its body read once (to check its hash) and served again to the controller. */
    private static final class CachedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous only");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
