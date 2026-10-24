package com.autotrade.trading;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user logins (user 24 Oct 2026: "start per-user logins with Keycloak").
 * <ul>
 *   <li>{@code autotrade.auth.mode=none} (default): no login in the app; every request passes, as before (the site's own
 *       gate is in front).</li>
 *   <li>{@code autotrade.auth.mode=oidc}: Keycloak sign-in (authorization code with PKCE, this service as a confidential
 *       client) on every page and API except health and the login flow itself. A script's API call without a session
 *       gets 401 with {@value #SIGN_IN_HEADER} (the UI then reloads the page, which signs in again); a page, or an API
 *       link opened in a browser (the daily Kite login link, Zerodha's redirect back), goes to the Keycloak sign-in and
 *       comes back to it afterwards. The PAPER system controls (kill, release, stop, exit-all) need the realm role
 *       {@code operator}. A sign-in lasts at most {@code autotrade.auth.max-session} (default 12 h, Keycloak's own
 *       maximum), however busy the page is, so disabling a user or removing a role in Keycloak takes effect by then.
 *       The signed-in user's name is who acts on the execution API ({@link ExecutionProxyController}); what they may see
 *       and do there is decided by their tenant roles.</li>
 * </ul>
 * One chain, chosen by {@link AuthProperties#oidc()}, so the chain and every other reader of the mode always agree
 * (code review 24 Oct 2026). CSRF tokens are not used: the UI is a script that sends JSON, and {@link SameOriginFilter}
 * refuses state-changing requests from another site; the session cookie is SameSite=Lax.
 * <p>
 * The browser reaches Keycloak at its public address; this service reaches it inside the host network (tokens, keys,
 * user info), so no request of ours passes the site's password gate. The client registration is built here, not
 * discovered, so the ID token's issuer is still checked against the public realm address.
 */
@Configuration
class SecurityConfig {

    /** On a 401 from the sign-in check (not from anything behind it): the UI reloads the page to sign in again. */
    static final String SIGN_IN_HEADER = "X-Autotrade-Sign-In";

    /** The PAPER system controls on trading-core's own API. */
    private static final String[] OPERATOR_POSTS = {"/api/kill", "/api/kill/release", "/api/stop", "/api/exit-all"};

    @Bean
    SecurityFilterChain securityChain(HttpSecurity http, AuthProperties auth) throws Exception {
        http.csrf(c -> c.disable()).httpBasic(b -> b.disable()).formLogin(f -> f.disable());
        if (!auth.oidc()) {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
        ClientRegistrationRepository registrations = registrations(auth);
        OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/");
        // Only what a person opened in a browser is returned to after sign-in, never a script's API call.
        HttpSessionRequestCache returnTo = new HttpSessionRequestCache();
        returnTo.setRequestMatcher(request -> "GET".equals(request.getMethod()) && !scriptCall(request));
        AuthenticationEntryPoint signInAgain = (request, response, e) -> {
            response.setHeader(SIGN_IN_HEADER, "required");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        };
        RequestMatcher scripts = SecurityConfig::scriptCall;
        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/login/**", "/oauth2/**", "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, OPERATOR_POSTS).hasAuthority(auth.operatorAuthority())
                        .anyRequest().authenticated())
                .oauth2Login(o -> o.clientRegistrationRepository(registrations)
                        .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                        .userInfoEndpoint(u -> u.oidcUserService(rolesFromClaims())))
                .requestCache(c -> c.requestCache(returnTo))
                .logout(l -> l.logoutSuccessHandler(logout))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(signInAgain, scripts))
                .addFilterBefore(new MaxSessionAge(auth.maxSession(), Clock.systemUTC()), AuthorizationFilter.class)
                .build();
    }

    /** An API call from a script (the UI's fetch, curl), not a page or link a person opened in a browser. */
    static boolean scriptCall(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return request.getRequestURI().startsWith("/api/") && (accept == null || !accept.contains("text/html"));
    }

    static ClientRegistrationRepository registrations(AuthProperties auth) {
        if (auth.publicBase() == null || auth.clientSecret() == null || auth.clientSecret().isBlank()) {
            throw new IllegalStateException("autotrade.auth.mode=oidc needs autotrade.auth.public-base and the client secret");
        }
        ClientRegistration keycloak = ClientRegistration.withRegistrationId("keycloak")
                .clientId(auth.clientId())
                .clientSecret(auth.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .authorizationUri(auth.publicRealm() + "/protocol/openid-connect/auth")
                .tokenUri(auth.internalRealm() + "/protocol/openid-connect/token")
                .jwkSetUri(auth.internalRealm() + "/protocol/openid-connect/certs")
                .userInfoUri(auth.internalRealm() + "/protocol/openid-connect/userinfo")
                .userNameAttributeName("preferred_username")
                .issuerUri(auth.publicRealm())
                .providerConfigurationMetadata(Map.of("end_session_endpoint",
                        auth.publicRealm() + "/protocol/openid-connect/logout"))
                .clientName("autotrade")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())  // PKCE (S256)
                .build();
        return new InMemoryClientRegistrationRepository(keycloak);
    }

    /** Keycloak's realm roles (claim {@code roles}, added by the realm's mapper) as ROLE_ authorities. */
    static OAuth2UserService<OidcUserRequest, OidcUser> rolesFromClaims() {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            return new DefaultOidcUser(authorities(user.getAuthorities(), user.getClaims().get("roles")), user.getIdToken(),
                    user.getUserInfo(), "preferred_username");
        };
    }

    static Collection<GrantedAuthority> authorities(Collection<? extends GrantedAuthority> base, Object roles) {
        List<GrantedAuthority> out = new ArrayList<>(base);
        if (roles instanceof Collection<?> list) {
            for (Object role : list) {
                if (role != null && !role.toString().isBlank()) {
                    out.add(new SimpleGrantedAuthority(AuthProperties.authority(role.toString())));
                }
            }
        }
        return out;
    }

    /**
     * Ends a sign-in older than the maximum (counted from Keycloak's auth_time): the UI polls every few seconds, so the
     * idle timeout alone would keep a browser signed in for ever and never re-check the user and roles in Keycloak.
     */
    static final class MaxSessionAge extends OncePerRequestFilter {
        private final Duration max;
        private final Clock clock;

        MaxSessionAge(Duration max, Clock clock) {
            this.max = max;
            this.clock = clock;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();
            if (signedIn instanceof OAuth2AuthenticationToken token && token.getPrincipal() instanceof OidcUser user) {
                Instant since = user.getAuthenticatedAt() != null ? user.getAuthenticatedAt() : user.getIssuedAt();
                if (since == null || since.plus(max).isBefore(clock.instant())) {
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        }
    }
}
