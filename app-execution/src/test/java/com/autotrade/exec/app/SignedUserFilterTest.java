package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.autotrade.execution.UserSignature;

/**
 * Review 19 Nov 2026: with the shared secret a request naming a user is served only with the proxy's signature; a request
 * naming no user passes; without the secret nothing changes. The controller still reads the body after the check.
 */
class SignedUserFilterTest {

    private static final String SECRET = "test-secret-not-real";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(2_000_000_000L), ZoneOffset.UTC);

    private static MockHttpServletRequest post(String user, String body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/execution/accounts/1/settings");
        r.setQueryString("why=x");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (user != null) {
            r.addHeader(Access.HEADER, user);
        }
        return r;
    }

    private static void sign(MockHttpServletRequest r, String user, String body) {
        long now = CLOCK.instant().getEpochSecond();
        r.addHeader(UserSignature.TS, String.valueOf(now));
        r.addHeader(UserSignature.SIG, UserSignature.sign(SECRET, user, now, "POST", "/api/execution/accounts/1/settings?why=x",
                body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Runs the filter; returns the status and what the controller would have read (null when not reached). */
    private static String[] run(SignedUserFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(new String(req.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)));
        return new String[] {String.valueOf(response.getStatus()), seen.get()};
    }

    @Test
    void aSignedUserPassesWithItsBodyAndAnUnsignedOrForgedOneIsRefused() throws Exception {
        SignedUserFilter filter = new SignedUserFilter(SECRET, CLOCK);
        MockHttpServletRequest good = post("satyacp99", "{\"enabled\":false}");
        sign(good, "satyacp99", "{\"enabled\":false}");
        assertThat(run(filter, good)).containsExactly("200", "{\"enabled\":false}");

        assertThat(run(filter, post("satyacp99", "{}"))[0]).as("no signature").isEqualTo("401");
        MockHttpServletRequest forged = post("satyacp99", "{\"enabled\":true}");
        sign(forged, "satyacp99", "{\"enabled\":false}");                 // signed for another body
        assertThat(run(filter, forged)).containsExactly("401", null);
        MockHttpServletRequest other = post("local-owner", "{}");
        sign(other, "satyacp99", "{}");                                    // signed for another user
        assertThat(run(filter, other)[0]).isEqualTo("401");
    }

    @Test
    void aRequestNamingNoUserAndASetupWithoutTheSecretAreUnchanged() throws Exception {
        assertThat(run(new SignedUserFilter(SECRET, CLOCK), post(null, "{}"))).containsExactly("200", "{}");
        assertThat(run(new SignedUserFilter(" ", CLOCK), post("satyacp99", "{}"))).containsExactly("200", "{}");
        assertThat(new SignedUserFilter(null, CLOCK).on()).isFalse();
    }

    /** Review 19 Nov 2026: an unsigned request is refused before its body is read; a body over the limit is refused. */
    @Test
    void anUnsignedRequestIsRefusedUnreadAndAnOversizedBodyIsRefused() throws Exception {
        SignedUserFilter filter = new SignedUserFilter(SECRET, CLOCK);
        MockHttpServletRequest unsigned = post("satyacp99", "{}");
        assertThat(run(filter, unsigned)[0]).isEqualTo("401");
        assertThat(unsigned.getInputStream().readAllBytes()).as("not consumed").hasSize(2);
        String big = "x".repeat(SignedUserFilter.MAX_BODY + 1);
        MockHttpServletRequest huge = post("satyacp99", big);
        sign(huge, "satyacp99", big);
        assertThat(run(filter, huge)).containsExactly("401", null);
    }
}
