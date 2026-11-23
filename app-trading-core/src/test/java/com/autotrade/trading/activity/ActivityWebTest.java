package com.autotrade.trading.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class ActivityWebTest {

    private static OAuth2AuthenticationToken signedIn(String name, String sid, Instant authTime) {
        Map<String, Object> claims = sid == null
                ? Map.of("sub", "id-" + name, "preferred_username", name, "auth_time", authTime)
                : Map.of("sub", "id-" + name, "preferred_username", name, "auth_time", authTime, "sid", sid);
        OidcIdToken token = new OidcIdToken("t", authTime, authTime.plusSeconds(300), claims);
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token, "preferred_username");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");
    }

    private static ActivityWeb web() {
        ActivityRecorder recorder = ActivityRecorder.manual(null, new ActivityProperties(true, null, null, null, null, null),
                Clock.systemUTC());
        return new ActivityWeb(recorder, new ClientAddress(List.of("127.0.0.0/8", "35.191.0.0/16", "136.82.45.194")));
    }

    @Test
    void aSignInIsKeyedByKeycloaksSessionAndKeptInTheHttpSession() {
        ActivityWeb web = web();
        Instant auth = Instant.parse("2026-11-24T03:30:00Z");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/keycloak");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "49.36.10.20, 136.82.45.194, 35.191.9.8");
        request.addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) Version/18.1 Mobile/15E148 Safari/604.1");
        request.getSession(true);
        web.signedIn(request, signedIn("anna", "kc-sid-1", auth));
        String key = ActivityRecorder.loginKey("kc-sid-1", "anna", auth);
        assertThat(request.getSession().getAttribute(ActivityRecorder.SESSION_KEY)).isEqualTo(key);
        assertThat(web.recorder().counters()).containsEntry("queued", 1);
        assertThat(web.key(request)).isEqualTo(key);
        assertThat(ActivityRecorder.loginKey("kc-sid-1", "someone-else", Instant.EPOCH)).as("the sid alone decides").isEqualTo(key);
    }

    @Test
    void withoutASidTheUserAndAuthTimeAreTheKey() {
        Instant auth = Instant.parse("2026-11-24T03:30:00Z");
        assertThat(ActivityWeb.keyOf(signedIn("ben", null, auth))).isEqualTo(ActivityRecorder.loginKey(null, "ben", auth));
        assertThat(ActivityWeb.keyOf(null)).isNull();
    }

    @Test
    void aRequestWithoutTheKeyInItsSessionGetsItFromTheSignedInUser() throws Exception {
        ActivityWeb web = web();
        Instant auth = Instant.parse("2026-11-24T03:30:00Z");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/status");
        request.getSession(true);
        SecurityContextHolder.getContext().setAuthentication(signedIn("cara", "kc-sid-9", auth));
        try {
            web.seenFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
            assertThat(request.getSession().getAttribute(ActivityRecorder.SESSION_KEY))
                    .isEqualTo(ActivityRecorder.loginKey("kc-sid-9", "cara", auth));
        } finally {
            SecurityContextHolder.clearContext();
        }
        MockHttpServletRequest noSession = new MockHttpServletRequest("GET", "/api/status");
        assertThat(web.key(noSession)).isNull();
    }

    @Test
    void offDoesNothingAtSignIn() {
        ActivityWeb off = new ActivityWeb(ActivityRecorder.disabled(), new ClientAddress(null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        off.signedIn(request, signedIn("dev", "s", Instant.now()));
        assertThat(request.getSession().getAttribute(ActivityRecorder.SESSION_KEY)).isNull();
    }
}
