package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.trading.activity.ActivityProperties;
import com.autotrade.trading.activity.ActivityRecorder;
import com.autotrade.trading.activity.ActivityWeb;
import com.autotrade.trading.activity.ClientAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/** Code review 23 Nov 2026: recording a sign-in must not change where the browser goes back to, nor break start-up. */
class SignInRecordTest {

    private static OAuth2AuthenticationToken anna() {
        Instant at = Instant.parse("2026-11-24T03:30:00Z");
        OidcIdToken token = new OidcIdToken("t", at, at.plusSeconds(300),
                Map.of("sub", "id-anna", "preferred_username", "anna", "auth_time", at, "sid", "kc-1"));
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token, "preferred_username");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");
    }

    /** Off: only the redirect is under test here. */
    private static ActivityWeb recording() {
        return new ActivityWeb(ActivityRecorder.disabled(), new ClientAddress(null));
    }

    @Test
    void afterSignInTheBrowserReturnsToThePageItAskedFor() throws Exception {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest asked = new MockHttpServletRequest("GET", "/index/NIFTY");
        asked.setSession(session);
        asked.setQueryString("date=2026-11-20");
        cache.saveRequest(asked, new MockHttpServletResponse());

        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/keycloak");
        callback.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.recordThenReturn(recording(), cache).onAuthenticationSuccess(callback, response, anna());
        // Spring Security's request cache marks the return with "continue" (as before this change)
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/index/NIFTY?date=2026-11-20&continue");
    }

    @Test
    void withNothingSavedItGoesHomeAndTheSignInIsKeptInTheSession() throws Exception {
        ActivityRecorder on = ActivityRecorder.manual(null, new ActivityProperties(true, null, null, null, null, null),
                java.time.Clock.systemUTC());          // no writer thread, never flushed: no database in this test
        {
            MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/keycloak");
            callback.setSession(new MockHttpSession());
            MockHttpServletResponse response = new MockHttpServletResponse();
            SecurityConfig.recordThenReturn(new ActivityWeb(on, new ClientAddress(null)), new HttpSessionRequestCache())
                    .onAuthenticationSuccess(callback, response, anna());
            assertThat(response.getRedirectedUrl()).isEqualTo("/");
            assertThat(callback.getSession().getAttribute(ActivityRecorder.SESSION_KEY)).isNotNull();
            assertThat(on.counters()).containsEntry("queued", 1);
        }
    }

    @Test
    void blankEnvironmentValuesBindToTheDefaults() {
        // application.yml passes ${AUTOTRADE_ACTIVITY_ENABLED:} and ${AUTOTRADE_ACTIVITY_TRUSTED_PROXIES:}: blank when unset
        ActivityProperties p = new Binder(new MapConfigurationPropertySource(Map.of(
                "autotrade.activity.enabled", "", "autotrade.activity.trusted-proxies", "", "autotrade.activity.idle", "15m",
                "autotrade.activity.raw-days", "14"))).bind("autotrade.activity", ActivityProperties.class).get();
        assertThat(p.enabled()).isNull();
        assertThat(p.on(true)).isTrue();
        assertThat(p.on(false)).isFalse();
        assertThat(p.trustedProxies()).isEqualTo(ClientAddress.DEFAULT_TRUSTED);
        assertThat(p.idle()).isEqualTo(Duration.ofMinutes(15));

        ActivityProperties gcp = new Binder(new MapConfigurationPropertySource(Map.of(
                "autotrade.activity.trusted-proxies", "127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,35.191.0.0/16,136.82.45.194")))
                .bind("autotrade.activity", ActivityProperties.class).get();
        assertThat(gcp.trustedProxies()).containsExactly("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "35.191.0.0/16", "136.82.45.194");
        assertThat(new ClientAddress(gcp.trustedProxies()).resolve("49.36.10.20, 136.82.45.194, 35.191.9.8", "172.18.0.1"))
                .isEqualTo("49.36.10.20");
    }
}
