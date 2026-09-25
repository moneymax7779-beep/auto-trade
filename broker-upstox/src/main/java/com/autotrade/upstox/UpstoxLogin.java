package com.autotrade.upstox;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upstox OAuth login. The account holder opens {@link #authorizationUrl()} in their own browser and
 * signs in on Upstox's page; Upstox redirects to the local callback with a one-time code, which is
 * exchanged for the day's access token. This class never sees the password or TOTP.
 */
public final class UpstoxLogin {

    static final String AUTHORIZE = "https://api.upstox.com/v2/login/authorization/dialog";
    static final String TOKEN = "https://api.upstox.com/v2/login/authorization/token";
    static final String PROFILE = "https://api.upstox.com/v2/user/profile";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UpstoxCredentials credentials;
    private final HttpClient http;
    private final String state;

    public UpstoxLogin(UpstoxCredentials credentials) {
        this(credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), newState());
    }

    UpstoxLogin(UpstoxCredentials credentials, HttpClient http, String state) {
        this.credentials = credentials;
        this.http = http;
        this.state = state;
    }

    /** The page to open in the browser. Contains the app key and a one-time state value, no secret. */
    public String authorizationUrl() {
        return AUTHORIZE + "?response_type=code&client_id=" + encode(credentials.apiKey()) + "&redirect_uri="
                + encode(credentials.redirectUri()) + "&state=" + encode(state);
    }

    /**
     * Listens on the redirect URL's port for the callback, exchanges the code and returns the token.
     * Waits at most {@code timeout} for the account holder to finish signing in.
     */
    public UpstoxToken awaitCallback(Duration timeout) throws IOException, InterruptedException {
        URI redirect = URI.create(credentials.redirectUri());
        CompletableFuture<String> code = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", redirect.getPort()), 0);
        server.createContext(redirect.getPath(), exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String message;
            if (!state.equals(query.get("state"))) {
                message = "Login rejected: the state value did not match. Start the login again.";
                code.completeExceptionally(new IOException("callback state mismatch"));
            } else if (query.get("code") == null) {
                message = "Login failed: Upstox returned no code (" + query.getOrDefault("error", "unknown") + ").";
                code.completeExceptionally(new IOException("no code in callback"));
            } else {
                message = "auto-trade received the Upstox login. You can close this tab.";
                code.complete(query.get("code"));
            }
            byte[] body = ("<html><body style='font-family:sans-serif'><p>" + message + "</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            return exchange(code.get(timeout.toSeconds(), TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("no login within " + timeout.toMinutes() + " minutes");
        } finally {
            server.stop(0);
        }
    }

    /** Exchanges a one-time authorization code for an access token. */
    UpstoxToken exchange(String code) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", credentials.apiKey());
        form.put("client_secret", credentials.apiSecret());
        form.put("redirect_uri", credentials.redirectUri());
        form.put("grant_type", "authorization_code");
        StringBuilder body = new StringBuilder();
        form.forEach((key, value) -> body.append(body.isEmpty() ? "" : "&").append(key).append('=').append(encode(value)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("token exchange failed: HTTP " + response.statusCode() + " " + errorOf(response.body()));
        }
        JsonNode node = JSON.readTree(response.body());
        JsonNode token = node.get("access_token");
        if (token == null || token.asString().isBlank()) {
            throw new IOException("token exchange returned no access_token");
        }
        return UpstoxToken.issued(token.asString(), node.has("user_id") ? node.get("user_id").asString() : "?",
                Instant.now());
    }

    /** Calls the profile API with the token; returns the user id if the token works. */
    public static String verify(UpstoxToken token, HttpClient http) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILE))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token.accessToken())
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("profile check failed: HTTP " + response.statusCode() + " " + errorOf(response.body()));
        }
        JsonNode data = JSON.readTree(response.body()).get("data");
        return data != null && data.has("user_id") ? data.get("user_id").asString() : "?";
    }

    /** Upstox error bodies carry a message; the access token or secret is never echoed. */
    private static String errorOf(String body) {
        try {
            JsonNode errors = JSON.readTree(body).get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty() && errors.get(0).has("message")) {
                return errors.get(0).get("message").asString();
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "";
    }

    static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String newState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
