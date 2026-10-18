package com.autotrade.zerodha;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Kite's daily login (live plan section 12, "Login"): the user opens {@link #loginUrl} (sent to them by Telegram each
 * morning), logs in at Zerodha, and Kite redirects to the app's redirect URL with a {@code request_token}; that token
 * plus the checksum SHA-256(api_key + request_token + api_secret) is exchanged for the day's access token. Nothing here
 * handles a password or TOTP, and nothing is automated past the user's own login.
 */
public final class KiteAuth {

    /** The day's session: the access token is valid until the next morning's flush (about 06:00 IST). */
    public record Session(String userId, String accessToken, Instant loginTime) {
        @Override
        public String toString() {
            return "Session[userId=" + userId + ", loginTime=" + loginTime + ", accessToken=***]";
        }
    }

    private final KiteConfig config;
    private final KiteHttp http;

    public KiteAuth(KiteConfig config, KiteHttp http) {
        this.config = config;
        this.http = http;
    }

    /** The page the user logs in on; {@code state} comes back on the redirect (to bind it to the account). */
    public URI loginUrl(String state) {
        String url = config.loginBase() + "/connect/login?v=3&api_key=" + enc(config.apiKey());
        return URI.create(state == null ? url : url + "&redirect_params=" + enc("state=" + state));
    }

    public static String checksum(String apiKey, String requestToken, String apiSecret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((apiKey + requestToken + apiSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Exchanges the redirect's request token for the day's access token. */
    public Session exchange(String requestToken, String apiSecret) {
        JsonNode data = http.post("/session/token", Map.of("api_key", config.apiKey(), "request_token", requestToken,
                "checksum", checksum(config.apiKey(), requestToken, apiSecret)));
        String loginTime = data.path("login_time").asString("");
        return new Session(data.path("user_id").asString(""), data.path("access_token").asString(""),
                loginTime.isEmpty() ? Instant.now() : KiteHttp.time(loginTime));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
