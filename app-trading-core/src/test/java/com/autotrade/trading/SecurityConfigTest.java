package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Per-user logins: mode none keeps everything open (as before); mode oidc signs in every page and API. */
class SecurityConfigTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableConfigurationProperties(AuthProperties.class)
    @Import({SecurityConfig.class, MeController.class, Controls.class})
    static class App {
    }

    /** Stand-ins for TradingController's system controls. */
    @RestController
    static class Controls {
        @PostMapping({"/api/kill", "/api/stop", "/api/exit-all", "/api/kill/release"})
        String control() {
            return "ok";
        }

        @PostMapping("/api/replay")
        String other() {
            return "ok";
        }
    }

    static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor user(String name, String... roles) {
        return user(Instant.now(), name, roles);
    }

    static RequestPostProcessor user(Instant signedInAt, String name, String... roles) {
        var token = new OidcIdToken("t", signedInAt, signedInAt.plusSeconds(300),
                Map.of("sub", "id-" + name, "preferred_username", name, "auth_time", signedInAt));
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        return oidcLogin().oidcUser(new DefaultOidcUser(authorities, token, "preferred_username"));
    }

    @Nested
    @SpringJUnitWebConfig(App.class)
    class ModeNone {
        @Test
        void everythingIsOpenAsBefore(WebApplicationContext context) throws Exception {
            MockMvc mvc = mvc(context);
            mvc.perform(get("/api/me")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("none"))
                    .andExpect(jsonPath("$.user").value("local-owner")).andExpect(jsonPath("$.operator").value(true));
            mvc.perform(post("/api/kill")).andExpect(status().isOk());
        }
    }

    /** Set but empty (docker run -e AUTOTRADE_AUTH_MODE=): AuthProperties reads none, and so does the chain. */
    @Nested
    @SpringJUnitWebConfig(App.class)
    @TestPropertySource(properties = "autotrade.auth.mode=")
    class ModeEmpty {
        @Test
        void isModeNoneEverywhere(WebApplicationContext context) throws Exception {
            MockMvc mvc = mvc(context);
            mvc.perform(get("/api/me")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("none"));
            mvc.perform(post("/api/kill")).andExpect(status().isOk());
        }
    }

    @Nested
    @SpringJUnitWebConfig(App.class)
    @TestPropertySource(properties = {"autotrade.auth.mode=oidc", "autotrade.auth.public-base=http://localhost:8180/auth/",
            "autotrade.auth.internal-base=http://keycloak:8180/auth", "autotrade.auth.client-secret=test-only"})
    class ModeOidc {
        @Test
        void withoutASessionApiCallsGet401AndPagesGoToKeycloak(WebApplicationContext context) throws Exception {
            MockMvc mvc = mvc(context);
            mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                    .andExpect(header().string(SecurityConfig.SIGN_IN_HEADER, "required"));
            mvc.perform(post("/api/kill")).andExpect(status().isUnauthorized());
            mvc.perform(get("/live").accept("text/html")).andExpect(status().isFound())
                    .andExpect(header().string("Location", "/oauth2/authorization/keycloak"));
            mvc.perform(get("/oauth2/authorization/keycloak")).andExpect(status().isFound())
                    .andExpect(header().string("Location",
                            org.hamcrest.Matchers.startsWith("http://localhost:8180/auth/realms/autotrade/protocol/openid-connect/auth?")));
        }

        @Test
        void aLinkOpenedInABrowserSignsInAndComesBack(WebApplicationContext context) throws Exception {
            MockMvc mvc = mvc(context);
            String link = "/api/execution/accounts/1/kite/login";
            var opened = mvc.perform(get(link).accept("text/html,application/xhtml+xml"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/oauth2/authorization/keycloak")).andReturn();
            var saved = (org.springframework.security.web.savedrequest.SavedRequest) opened.getRequest().getSession()
                    .getAttribute("SPRING_SECURITY_SAVED_REQUEST");
            assertThat(saved.getRedirectUrl()).as("the Kite login link is where sign-in returns to").contains(link);
        }

        @Test
        void aScriptCallIsNeverWhereSignInReturnsTo(WebApplicationContext context) throws Exception {
            var call = mvc(context).perform(get("/api/status")).andExpect(status().isUnauthorized()).andReturn();
            var session = call.getRequest().getSession(false);
            assertThat(session == null ? null : session.getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNull();
        }

        @Test
        void aSignInEndsAfterTheMaximumHoweverBusy(WebApplicationContext context) throws Exception {
            MockMvc mvc = mvc(context);
            mvc.perform(get("/api/me").with(user(Instant.now().minus(java.time.Duration.ofHours(11)), "satya")))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/me").with(user(Instant.now().minus(java.time.Duration.ofHours(13)), "satya")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(SecurityConfig.SIGN_IN_HEADER, "required"));
            mvc.perform(post("/api/kill").with(user(Instant.now().minus(java.time.Duration.ofHours(13)), "satya",
                    "ROLE_OPERATOR"))).andExpect(status().isUnauthorized());
        }

        @Test
        void healthIsOpen(WebApplicationContext context) throws Exception {
            int code = mvc(context).perform(get("/actuator/health")).andReturn().getResponse().getStatus();
            assertThat(code).as("no handler in this test, but not sent to sign in").isEqualTo(404);
        }

        @Test
        void theSignedInUserIsWhoTheApiSees(WebApplicationContext context) throws Exception {
            mvc(context).perform(get("/api/me").with(user("satya", "ROLE_OPERATOR"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("oidc")).andExpect(jsonPath("$.user").value("satya"))
                    .andExpect(jsonPath("$.operator").value(true));
            mvc(context).perform(get("/api/me").with(user("viewer"))).andExpect(jsonPath("$.operator").value(false));
        }

        @Test
        void systemControlsNeedTheOperatorRole(WebApplicationContext context) throws Exception {
            MockMvc mvc = mvc(context);
            for (String path : List.of("/api/kill", "/api/kill/release", "/api/stop", "/api/exit-all")) {
                mvc.perform(post(path).with(user("viewer"))).andExpect(status().isForbidden());
                mvc.perform(post(path).with(user("satya", "ROLE_OPERATOR"))).andExpect(status().isOk());
            }
            mvc.perform(post("/api/replay").with(user("viewer"))).andExpect(status().isOk());
        }
    }

    @Test
    void realmRolesBecomeAuthorities() {
        assertThat(SecurityConfig.authorities(List.of(), List.of("operator", " viewer ", "")))
                .extracting(a -> a.getAuthority()).containsExactly("ROLE_OPERATOR", "ROLE_VIEWER");
        assertThat(SecurityConfig.authorities(List.of(), null)).isEmpty();
    }

    @Test
    void settingsAreChecked() {
        AuthProperties auth = new AuthProperties(" ", null, null, null, null, "s3cret", null, null);
        assertThat(auth.oidc()).isFalse();
        assertThat(auth.toString()).doesNotContain("s3cret");
        assertThat(auth.maxSession()).hasHours(12);
        assertThat(auth.operatorAuthority()).isEqualTo("ROLE_OPERATOR");
        assertThat(new AuthProperties(" OIDC ", "http://h/auth/", null, null, null, null, null, null).internalRealm())
                .isEqualTo("http://h/auth/realms/autotrade");
        assertThatThrownBy(() -> new AuthProperties("basic", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecurityConfig.registrations(new AuthProperties("oidc", "http://h", null, null, null, "",
                null, null))).isInstanceOf(IllegalStateException.class);
    }
}
