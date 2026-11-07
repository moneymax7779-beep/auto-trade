package com.autotrade.exec.app;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;

import tools.jackson.databind.JsonNode;

/**
 * The daily Upstox login of a member's own Upstox app (trader workspaces, 7 Nov 2026; Upstox API v2 OAuth: authorization
 * dialog, then the code exchanged at /v2/login/authorization/token):
 * <ol>
 *   <li>{@link #start}: a one-time {@code state} (10 minutes) binds Upstox's redirect to the account and the user;</li>
 *   <li>the member logs in at Upstox (password and 2FA stay there); Upstox redirects with {@code code} and {@code state};</li>
 *   <li>{@link #callback}: the code (single use) is exchanged with the app's secret; the login must be the pinned client
 *       (UCC = {@code user_id}); the funds are read once (Upstox's funds service is down 00:00-05:30 IST: then only
 *       noted); the session ends at the next 03:30 IST, Upstox's own expiry.</li>
 * </ol>
 * Never zt-tiger-v2's Upstox app (a new login of that app would end zt's token): a member's account uses its own app.
 * Upstox has no LIVE order adapter in this build: the session proves the connection and the account trades PAPER.
 */
@Component
class UpstoxLogin {

    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    static final LocalTime EXPIRY = LocalTime.of(3, 30);

    private record Pending(long accountId, String subject, Instant created) {
    }

    private final SessionActivation sessions;
    private final AccountStore accounts;
    private final BrokerLoginProperties props;
    private final BrokerHttp http = new BrokerHttp("Upstox");
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    UpstoxLogin(SessionActivation sessions, DataSource dataSource, BrokerLoginProperties props) {
        this.sessions = sessions;
        this.accounts = new AccountStore(dataSource);
        this.props = props;
    }

    /** Upstox's login page for this account (the caller must be allowed to act on it). */
    URI start(Access.User user, long accountId) {
        BrokerAccount account = sessions.account(user, accountId, BrokerType.UPSTOX);
        SessionActivation.App app = sessions.app(account);
        if (app.redirectUri() == null) {
            throw new IllegalArgumentException("the Upstox app has no redirect URL");
        }
        Instant cutoff = Instant.now().minus(STATE_LIFETIME);
        pending.values().removeIf(p -> p.created().isBefore(cutoff));
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        pending.put(state, new Pending(accountId, user.subject(), Instant.now()));
        return URI.create(props.upstoxApi() + "/v2/login/authorization/dialog?response_type=code&client_id="
                + enc(sessions.secrets.read(app.keyRef())) + "&redirect_uri=" + enc(app.redirectUri()) + "&state=" + state);
    }

    /** Upstox's redirect: exchange, check, store. Never throws to the browser. */
    KiteLogin.Result callback(String code, String state) {
        Pending p = state == null ? null : pending.remove(state);
        if (p == null || p.created().plus(STATE_LIFETIME).isBefore(Instant.now())) {
            return new KiteLogin.Result(false, null, "This login link has expired or was already used. Start the login again.");
        }
        BrokerAccount account = accounts.byId(p.accountId()).orElse(null);
        if (account == null) {
            return new KiteLogin.Result(false, null, "The account no longer exists.");
        }
        if (code == null || code.isBlank()) {
            sessions.failed(account, null, p.subject(), "Upstox returned no code");
            return new KiteLogin.Result(false, account.displayName(), "Upstox did not complete the login.");
        }
        SessionActivation.App app = null;
        try {
            app = sessions.app(account);
            JsonNode token = http.postForm(props.upstoxApi() + "/v2/login/authorization/token", Map.of(), Map.of(
                    "code", code, "client_id", sessions.secrets.read(app.keyRef()),
                    "client_secret", sessions.secrets.read(app.secretRef()), "redirect_uri", app.redirectUri(),
                    "grant_type", "authorization_code"));
            String user = token.path("user_id").asString("");
            String access = token.path("access_token").asString("");
            if (access.isEmpty()) {
                throw new BrokerHttp.Failure(0, "Upstox returned no access token");
            }
            if (!app.clientId().equalsIgnoreCase(user)) {
                sessions.failed(account, app, p.subject(), "logged in as " + user + ", not " + app.clientId());
                return new KiteLogin.Result(false, account.displayName(), "You logged in as Upstox client " + user + ", but "
                        + account.displayName() + " is client " + app.clientId() + ". Nothing was stored.");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("brokerUser", user);
            String funds;
            try {
                JsonNode f = http.getJson(props.upstoxApi() + "/v2/user/get-funds-and-margin",
                        Map.of("Authorization", "Bearer " + access));
                double available = f.path("data").path("equity").path("available_margin").asDouble(Double.NaN);
                detail.put("equityAvailable", available);
                funds = Double.isNaN(available) ? "funds not reported" : String.format("equity available Rs %,.0f", available);
            } catch (BrokerHttp.Failure e) {
                detail.put("fundsRead", e.getMessage());
                funds = "funds not read now (" + e.getMessage() + ")";
            }
            Instant now = Instant.now();
            Instant expires = SessionActivation.next(now, EXPIRY);
            sessions.activate(account, app, "upstox", p.subject(), Map.of("userId", user, "accessToken", access), now,
                    expires, detail);
            return new KiteLogin.Result(true, account.displayName(), "Logged in as Upstox client " + user + ", " + funds
                    + ". Session valid until 03:30 IST.");
        } catch (RuntimeException e) {         // never a 500: the page says why (code review 7 Nov 2026)
            sessions.failed(account, app, p.subject(), e.getMessage());
            return new KiteLogin.Result(false, account.displayName(), "The login could not be completed: " + e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
