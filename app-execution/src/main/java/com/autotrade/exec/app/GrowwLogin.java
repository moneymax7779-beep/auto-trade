package com.autotrade.exec.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;

import tools.jackson.databind.JsonNode;

/**
 * The daily Groww login (Groww Trade API, approval flow; trader workspaces, 7 Nov 2026): the member approves their API
 * key on Groww's API keys page each day; then one click here asks Groww for the day's token:
 * <ol>
 *   <li>{@code POST /v1/token/api/access} with the API key as bearer and {@code {"key_type":"approval", "checksum":
 *       sha256(secret + timestamp), "timestamp"}} (valid 10 minutes; Groww allows 150 token requests a day);</li>
 *   <li>{@code GET /v1/user/detail}: the UCC must be the pinned client id; {@code GET /v1/margins/detail/user}: the options
 *       buying balance, read once;</li>
 *   <li>the session ends at the token's own expiry (else the next 06:00 IST).</li>
 * </ol>
 * No TOTP or password passes here. Groww has a DRY_RUN order adapter only in this build (no LIVE one): the session is
 * read by READ_LIVE ({@link GrowwSessionSource}); orders go to a simulator.
 */
@Component
class GrowwLogin {

    private final SessionActivation sessions;
    private final BrokerLoginProperties props;
    private final BrokerHttp http = new BrokerHttp("Groww");

    GrowwLogin(SessionActivation sessions, BrokerLoginProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    KiteLogin.Result login(Access.User user, long accountId) {
        BrokerAccount account = sessions.account(user, accountId, BrokerType.GROWW);
        SessionActivation.App app = sessions.app(account);
        try {
            String key = sessions.secrets.read(app.keyRef());
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            JsonNode t = http.postJson(props.growwApi() + "/v1/token/api/access", Map.of("Authorization", "Bearer " + key,
                    "X-API-VERSION", "1.0"), Map.of("key_type", "approval", "checksum",
                    checksum(sessions.secrets.read(app.secretRef()), timestamp), "timestamp", timestamp));
            String token = t.path("token").asString("");
            if (token.isEmpty() || !t.path("isActive").asBoolean(true)) {
                throw new BrokerHttp.Failure(0, "Groww returned no active token: approve the API key on Groww's API keys "
                        + "page today first");
            }
            Map<String, String> auth = Map.of("Authorization", "Bearer " + token, "X-API-VERSION", "1.0");
            String ucc = http.getJson(props.growwApi() + "/v1/user/detail", auth).path("payload").path("ucc").asString("");
            if (!app.clientId().equalsIgnoreCase(ucc)) {
                sessions.failed(account, app, user.subject(), "logged in as " + ucc + ", not " + app.clientId());
                return new KiteLogin.Result(false, account.displayName(), "This Groww key belongs to client " + ucc + ", but "
                        + account.displayName() + " is client " + app.clientId() + ". Nothing was stored.");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("brokerUser", ucc);
            String funds;
            try {
                double balance = http.getJson(props.growwApi() + "/v1/margins/detail/user", auth).path("payload")
                        .path("fno_margin_details").path("option_buy_balance_available").asDouble(Double.NaN);
                detail.put("optionBuyBalance", balance);
                funds = Double.isNaN(balance) ? "balance not reported" : String.format("options buying balance Rs %,.0f",
                        balance);
            } catch (BrokerHttp.Failure e) {
                detail.put("fundsRead", e.getMessage());
                funds = "balance not read now";
            }
            Instant now = Instant.now();
            Instant expires = expiry(t.path("expiry").asString(""), now);
            sessions.activate(account, app, "groww", user.subject(), Map.of("ucc", ucc, "accessToken", token), now, expires,
                    detail);
            return new KiteLogin.Result(true, account.displayName(), "Logged in as Groww client " + ucc + ", " + funds + ".");
        } catch (RuntimeException e) {         // never a 500: the page says why (code review 7 Nov 2026)
            sessions.failed(account, app, user.subject(), e.getMessage());
            return new KiteLogin.Result(false, account.displayName(), "The login could not be completed: " + e.getMessage());
        }
    }

    /** Groww's checksum: hex SHA-256 of the API secret followed by the timestamp. */
    static String checksum(String secret, String timestamp) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((secret + timestamp).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The token's expiry (Groww's local IST time without a zone); not usable: the next 06:00 IST. */
    static Instant expiry(String value, Instant now) {
        try {
            Instant at = LocalDateTime.parse(value).atZone(SessionActivation.IST).toInstant();
            if (at.isAfter(now)) {
                return at;
            }
        } catch (DateTimeParseException e) {
            // fall through
        }
        return SessionActivation.next(now, LocalTime.of(6, 0));
    }
}
