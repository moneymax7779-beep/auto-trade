package com.autotrade.trading;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Per-user logins (user 24 Oct 2026: "start per-user logins with Keycloak").
 * <ul>
 *   <li>{@code autotrade.auth.mode=none} (default): no login in the app; every request passes, as before (the site's own
 *       gate is in front).</li>
 *   <li>{@code autotrade.auth.mode=oidc}: Keycloak sign-in (authorization code, this service as a confidential client)
 *       on every page and API except health and the login flow itself. An API call without a session gets 401 (the UI
 *       then starts the sign-in); a page gets the Keycloak login. The PAPER system controls (kill, release, stop,
 *       exit-all) need the realm role {@code operator}. The signed-in user's name is who acts on the execution API
 *       ({@link ExecutionProxyController}); what they may see and do there is decided by their tenant roles.</li>
 * </ul>
 * CSRF tokens are not used: the UI is a script that sends JSON, and {@link SameOriginFilter} refuses state-changing
 * requests from another site; the session cookie is SameSite=Lax.
 * <p>
 * The browser reaches Keycloak at its public address; this service reaches it inside the host network (tokens, keys,
 * user info), so no request of ours passes the site's password gate. The client registration is built here, not
 * discovered, so the ID token's issuer is still checked against the public realm address.
 */
@Configuration
class SecurityConfig {

    /** The PAPER system controls on trading-core's own API. */
    private static final String[] OPERATOR_POSTS = {"/api/kill", "/api/kill/release", "/api/stop", "/api/exit-all"};

    @Bean
    @ConditionalOnProperty(name = "autotrade.auth.mode", havingValue = "none", matchIfMissing = true)
    SecurityFilterChain openChain(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "autotrade.auth.mode", havingValue = "oidc")
    ClientRegistrationRepository keycloakRegistration(AuthProperties auth) {
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

    @Bean
    @ConditionalOnProperty(name = "autotrade.auth.mode", havingValue = "oidc")
    SecurityFilterChain oidcChain(HttpSecurity http, AuthProperties auth, ClientRegistrationRepository registrations)
            throws Exception {
        OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/");
        RequestMatcher api = request -> request.getRequestURI().startsWith("/api/");
        String operator = "ROLE_" + auth.operatorRole().toUpperCase(Locale.ROOT);
        return http.csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/login/**", "/oauth2/**", "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, OPERATOR_POSTS).hasAuthority(operator)
                        .anyRequest().authenticated())
                .oauth2Login(o -> o.userInfoEndpoint(u -> u.oidcUserService(rolesFromClaims())))
                .logout(l -> l.logoutSuccessHandler(logout))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api))
                .build();
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
                    out.add(new SimpleGrantedAuthority("ROLE_" + role.toString().trim().toUpperCase(Locale.ROOT)));
                }
            }
        }
        return out;
    }
}
