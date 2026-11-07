package com.autotrade.exec.app;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The family members' Keycloak logins, made by the app instead of the admin console behind an SSH tunnel (trader
 * workspaces, 7 Nov 2026, plan 2.7): a confidential client with a service account ({@code autotrade-admin}, realm-management
 * roles manage-users, view-users, query-users, view-realm; {@code bin/keycloak/bootstrap.sh --admin-secret-file}) on the
 * realm's internal address. What it does, and nothing else:
 * <ul>
 *   <li>create a member's user with a one-time password they must change and an authenticator app they must enrol at
 *       first sign-in ({@code UPDATE_PASSWORD}, {@code CONFIGURE_TOTP}), or, when the realm has an email server and the
 *       member an email, let Keycloak email them the link instead;</li>
 *   <li>a new one-time password (resend), remove the authenticator so they enrol again, enable or disable the login
 *       (disabling also signs them out everywhere).</li>
 * </ul>
 * The one-time password is returned to the caller once and never logged, stored or audited. Not configured
 * ({@code autotrade.execution.keycloak.url} blank): {@link #configured()} is false and members' logins stay a console job.
 */
@Component
class KeycloakAdmin {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdmin.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> FIRST_SIGN_IN = List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    /** How a new or re-invited member gets in: the email Keycloak sent, or the one-time password shown once. */
    record Delivery(String keycloakUserId, String delivery, String oneTimePassword) {
    }

    /** A Keycloak call that failed, with Keycloak's own reason where it gave one (never a secret). */
    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final KeycloakAdminProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();
    private String token;
    private Instant tokenUntil = Instant.EPOCH;

    KeycloakAdmin(KeycloakAdminProperties props) {
        this.props = props;
    }

    boolean configured() {
        return props.url() != null && props.clientSecret() != null;
    }

    /** The user's Keycloak id, or null when no user has this username. */
    String userId(String username) {
        JsonNode users = call("GET", "/users?exact=true&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8),
                null);
        for (JsonNode u : users) {
            if (username.equalsIgnoreCase(u.path("username").asString(""))) {
                return u.path("id").asString();
            }
        }
        return null;
    }

    /**
     * Creates the member's login; an existing user of that username is refused (the caller decides whether to link an
     * existing login instead). Email delivery when {@code email} is given and the realm can send mail.
     */
    Delivery create(String username, String displayName, String email) {
        if (userId(username) != null) {
            throw new Failure(409, "a Keycloak user " + username + " already exists");
        }
        String password = oneTimePassword();
        boolean byEmail = email != null && canEmail();
        Map<String, Object> user = new java.util.LinkedHashMap<>();
        user.put("username", username);
        user.put("enabled", true);
        user.put("firstName", displayName);
        if (email != null) {
            user.put("email", email);
            user.put("emailVerified", false);
        }
        user.put("requiredActions", FIRST_SIGN_IN);
        if (!byEmail) {
            user.put("credentials", List.of(Map.of("type", "password", "value", password, "temporary", true)));
        }
        call("POST", "/users", user);
        String id = userId(username);
        if (id == null) {
            throw new Failure(500, "Keycloak created no user " + username);
        }
        if (byEmail) {
            call("PUT", "/users/" + id + "/execute-actions-email?lifespan=86400", FIRST_SIGN_IN);
            return new Delivery(id, "EMAIL", null);
        }
        return new Delivery(id, "TEMP_PASSWORD", password);
    }

    /** A new one-time password (the old password stops working); the member changes it and enrols again if needed. */
    Delivery resend(String keycloakUserId) {
        String password = oneTimePassword();
        call("PUT", "/users/" + keycloakUserId + "/reset-password", Map.of("type", "password", "value", password,
                "temporary", true));
        requireActions(keycloakUserId, FIRST_SIGN_IN);
        return new Delivery(keycloakUserId, "TEMP_PASSWORD", password);
    }

    /** Removes the user's authenticator app(s); they must enrol one at the next sign-in. Returns how many were removed. */
    int resetAuthenticator(String keycloakUserId) {
        int removed = 0;
        for (JsonNode c : call("GET", "/users/" + keycloakUserId + "/credentials", null)) {
            if ("otp".equals(c.path("type").asString(""))) {
                call("DELETE", "/users/" + keycloakUserId + "/credentials/" + c.path("id").asString(), null);
                removed++;
            }
        }
        requireActions(keycloakUserId, List.of("CONFIGURE_TOTP"));
        call("POST", "/users/" + keycloakUserId + "/logout", null);
        return removed;
    }

    /** Enables or disables the login; disabling also ends their sessions. */
    void enabled(String keycloakUserId, boolean on) {
        // the whole representation, changed (code review 7 Nov 2026: a partial PUT can clear the profile's other fields)
        tools.jackson.databind.node.ObjectNode user = (tools.jackson.databind.node.ObjectNode) call("GET",
                "/users/" + keycloakUserId, null);
        user.put("enabled", on);
        call("PUT", "/users/" + keycloakUserId, user);
        if (!on) {
            call("POST", "/users/" + keycloakUserId + "/logout", null);
        }
    }

    private void requireActions(String keycloakUserId, List<String> actions) {
        tools.jackson.databind.node.ObjectNode user = (tools.jackson.databind.node.ObjectNode) call("GET",
                "/users/" + keycloakUserId, null);
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        user.path("requiredActions").forEach(a -> all.add(a.asString()));
        all.addAll(actions);
        tools.jackson.databind.node.ArrayNode list = user.putArray("requiredActions");
        all.forEach(list::add);
        call("PUT", "/users/" + keycloakUserId, user);
    }

    /** True when the realm has an email server (view-realm). */
    private boolean canEmail() {
        try {
            JsonNode realm = callAt("GET", props.url() + "/admin/realms/" + props.realm(), null);
            return realm.path("smtpServer").path("host").asString("").length() > 0;
        } catch (Failure e) {
            return false;
        }
    }

    String oneTimePassword() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }

    private JsonNode call(String method, String path, Object body) {
        return callAt(method, props.url() + "/admin/realms/" + props.realm() + path, body);
    }

    private JsonNode callAt(String method, String url, Object body) {
        if (!configured()) {
            throw new Failure(503, "Keycloak administration is not configured on the execution service");
        }
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token()).header("Accept", "application/json");
            if (body != null) {
                r.header("Content-Type", "application/json").method(method,
                        HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            } else {
                r.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                String reason = "";
                try {
                    JsonNode e = JSON.readTree(response.body());
                    reason = e.path("errorMessage").asString(e.path("error").asString(""));
                } catch (RuntimeException ignored) {
                    // not JSON: the status says enough
                }
                throw new Failure(response.statusCode(), "Keycloak refused (" + response.statusCode() + ")"
                        + (reason.isEmpty() ? "" : ": " + reason));
            }
            String text = response.body();
            return text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (Failure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "interrupted");
        } catch (Exception e) {
            log.warn("Keycloak {} failed: {}", method, e.getClass().getSimpleName());
            throw new Failure(503, "Keycloak not reachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The service account's token (client credentials), reused until 30 s before it expires. */
    private synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenUntil)) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(props.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(props.clientSecret(), StandardCharsets.UTF_8);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(props.url() + "/realms/" + props.realm()
                        + "/protocol/openid-connect/token")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new Failure(r.statusCode(), "Keycloak refused the service account (" + r.statusCode() + ")");
        }
        JsonNode t = JSON.readTree(r.body());
        token = t.path("access_token").asString();
        tokenUntil = Instant.now().plusSeconds(Math.max(5, t.path("expires_in").asLong(60) - 30));
        return token;
    }
}
