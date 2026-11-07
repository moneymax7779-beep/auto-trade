package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The members' Keycloak logins against a stand-in for Keycloak's admin API: a member is created with a one-time password
 * and the first-sign-in actions, never twice; a lost authenticator is removed (the password stays); disabling signs out;
 * a refused service account or Keycloak error is a Failure without any secret in it; not configured, nothing is called.
 */
class KeycloakAdminTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final Map<String, JsonNode> users = new ConcurrentHashMap<>();
    private volatile boolean refuseServiceAccount;
    private int tokens;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KeycloakAdmin admin() {
        return new KeycloakAdmin(new KeycloakAdminProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/auth/",
                null, null, "the-client-secret"));
    }

    private synchronized void handle(HttpExchange x) throws IOException {
        String method = x.getRequestMethod(), path = x.getRequestURI().getPath(), query = x.getRequestURI().getRawQuery();
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(method + " " + path.replace("/auth/admin/realms/autotrade", "") + (query == null ? "" : "?" + query));
        if (path.endsWith("/protocol/openid-connect/token")) {
            tokens++;
            if (refuseServiceAccount || !body.contains("client_secret=the-client-secret")) {
                reply(x, 401, "{\"error\":\"unauthorized_client\"}");
            } else {
                reply(x, 200, "{\"access_token\":\"t" + tokens + "\",\"expires_in\":300}");
            }
            return;
        }
        if (!"Bearer t1".equals(x.getRequestHeaders().getFirst("Authorization"))) {
            reply(x, 401, "{}");
            return;
        }
        String users = "/auth/admin/realms/autotrade/users";
        if (path.equals("/auth/admin/realms/autotrade")) {
            reply(x, 200, "{\"realm\":\"autotrade\",\"smtpServer\":{}}");
        } else if (path.equals(users) && method.equals("GET")) {
            String name = query.replaceAll(".*username=([^&]+).*", "$1");
            JsonNode u = this.users.get(name);
            reply(x, 200, u == null ? "[]" : "[" + u + "]");
        } else if (path.equals(users) && method.equals("POST")) {
            JsonNode u = JSON.readTree(body);
            if (this.users.containsKey(u.path("username").asString())) {
                reply(x, 409, "{\"errorMessage\":\"User exists with same username\"}");
                return;
            }
            this.users.put(u.path("username").asString(), JSON.readTree("{\"id\":\"kc-" + u.path("username").asString()
                    + "\",\"username\":\"" + u.path("username").asString() + "\",\"requiredActions\":[]}"));
            lastCreated = u;
            reply(x, 201, "");
        } else if (path.matches(users + "/[^/]+/credentials") && method.equals("GET")) {
            reply(x, 200, "[{\"id\":\"c1\",\"type\":\"password\"},{\"id\":\"c2\",\"type\":\"otp\"}]");
        } else if (path.matches(users + "/[^/]+") && method.equals("GET")) {
            JsonNode u = this.users.values().stream().filter(v -> path.endsWith("/" + v.path("id").asString())).findFirst()
                    .orElse(null);
            reply(x, u == null ? 404 : 200, u == null ? "{}" : u.toString());
        } else {
            reply(x, 204, "");                     // PUT user, DELETE credential, POST logout, PUT reset-password
        }
    }

    private volatile JsonNode lastCreated;

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            x.getResponseBody().write(bytes);
        }
        x.close();
    }

    @Test
    void aMemberIsCreatedWithAOneTimePasswordAndTheFirstSignInActionsOnce() {
        KeycloakAdmin admin = admin();
        assertThat(admin.configured()).isTrue();
        KeycloakAdmin.Delivery d = admin.create("asha", "Asha", "asha@example.com");     // no email server: a password
        assertThat(d.delivery()).isEqualTo("TEMP_PASSWORD");
        assertThat(d.keycloakUserId()).isEqualTo("kc-asha");
        assertThat(d.oneTimePassword()).hasSize(16).matches("[A-Za-z2-9]+");
        assertThat(lastCreated.path("requiredActions").toString()).contains("UPDATE_PASSWORD", "CONFIGURE_TOTP");
        assertThat(lastCreated.path("credentials").get(0).path("temporary").asBoolean()).isTrue();
        assertThat(lastCreated.path("credentials").get(0).path("value").asString()).isEqualTo(d.oneTimePassword());
        assertThatThrownBy(() -> admin.create("asha", "Asha", null)).isInstanceOf(KeycloakAdmin.Failure.class)
                .hasMessageContaining("already exists");
        assertThat(tokens).as("one service-account token, reused").isEqualTo(1);
        assertThat(admin.oneTimePassword()).isNotEqualTo(admin.oneTimePassword());
    }

    @Test
    void aLostAuthenticatorIsRemovedAndADisabledLoginSignedOut() {
        KeycloakAdmin admin = admin();
        admin.create("bina", "Bina", null);
        calls.clear();
        assertThat(admin.resetAuthenticator("kc-bina")).isEqualTo(1);
        assertThat(calls).contains("DELETE /users/kc-bina/credentials/c2", "POST /users/kc-bina/logout")
                .doesNotContain("DELETE /users/kc-bina/credentials/c1");
        calls.clear();
        admin.enabled("kc-bina", false);
        assertThat(calls).contains("PUT /users/kc-bina", "POST /users/kc-bina/logout");
        calls.clear();
        admin.enabled("kc-bina", true);
        assertThat(calls).contains("PUT /users/kc-bina").doesNotContain("POST /users/kc-bina/logout");
        KeycloakAdmin.Delivery again = admin.resend("kc-bina");
        assertThat(again.oneTimePassword()).hasSize(16);
        assertThat(calls).contains("PUT /users/kc-bina/reset-password");
    }

    @Test
    void refusalsAreFailuresWithoutSecretsAndAnUnconfiguredAdminCallsNothing() {
        refuseServiceAccount = true;
        assertThatThrownBy(() -> admin().userId("x")).isInstanceOf(KeycloakAdmin.Failure.class)
                .hasMessageContaining("refused the service account").hasMessageNotContaining("the-client-secret");
        KeycloakAdmin none = new KeycloakAdmin(new KeycloakAdminProperties(null, null, null, null));
        assertThat(none.configured()).isFalse();
        calls.clear();
        assertThatThrownBy(() -> none.userId("x")).isInstanceOf(KeycloakAdmin.Failure.class)
                .hasMessageContaining("not configured");
        assertThat(calls).isEmpty();
    }
}
