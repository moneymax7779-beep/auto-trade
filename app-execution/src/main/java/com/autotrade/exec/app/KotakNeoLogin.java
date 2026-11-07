package com.autotrade.exec.app;

import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;

import tools.jackson.databind.JsonNode;

/**
 * The daily Kotak Neo login (Trade API v2, trader workspaces, 7 Nov 2026): the member types the day's TOTP and their MPIN
 * on our page; both pass straight through to Kotak and are never stored, logged or audited.
 * <ol>
 *   <li>{@code POST /login/1.0/tradeApiLogin} with the consumer key (the token from the NEO app's Trade API card),
 *       the registered mobile number, the UCC and the TOTP: a view token and sid;</li>
 *   <li>{@code POST /login/1.0/tradeApiValidate} with the MPIN: the trade token, sid and the account's base URL;</li>
 *   <li>the UCC must be the pinned client id; the limits are read once ({@code POST {baseUrl}/quick/user/limits},
 *       "Net"); the session is kept until the end of the IST day (Kotak does not publish its lifetime).</li>
 * </ol>
 * Kotak's rule: the session must be created from the IP that sends orders. Kotak has no LIVE order adapter in this build,
 * so the account trades PAPER and the rule only matters once one exists. The base URL Kotak returns must be HTTPS on
 * kotaksecurities.com (or the configured login host) before any token is sent there.
 */
@Component
class KotakNeoLogin {

    private static final Pattern TOTP = Pattern.compile("[0-9]{6}");
    private static final Pattern MPIN = Pattern.compile("[0-9]{4,6}");
    private static final String NEO_FIN_KEY = "neotradeapi";

    private final SessionActivation sessions;
    private final BrokerLoginProperties props;
    private final BrokerHttp http = new BrokerHttp("Kotak Neo");

    KotakNeoLogin(SessionActivation sessions, BrokerLoginProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    KiteLogin.Result login(Access.User user, long accountId, String totp, String mpin) {
        BrokerAccount account = sessions.account(user, accountId, BrokerType.KOTAK_NEO);
        if (totp == null || !TOTP.matcher(totp.trim()).matches()) {
            throw new IllegalArgumentException("TOTP: the 6 digits your authenticator shows now");
        }
        if (mpin == null || !MPIN.matcher(mpin.trim()).matches()) {
            throw new IllegalArgumentException("MPIN: your 4-6 digit Kotak MPIN");
        }
        SessionActivation.App app = sessions.app(account);
        String mobile = app.settings().get("mobileNumber");
        if (mobile == null || mobile.isBlank()) {
            throw new IllegalArgumentException("the Kotak app has no registered mobile number: set it on the Brokers page");
        }
        try {
            String consumerKey = sessions.secrets.read(app.keyRef());
            // Kotak can answer 2xx with an error body and no data: the reason is read from the whole answer
            JsonNode first = http.postJson(props.kotakApi() + "/login/1.0/tradeApiLogin", Map.of("Authorization",
                    consumerKey, "neo-fin-key", NEO_FIN_KEY), Map.of("mobileNumber", mobile, "ucc", app.clientId(), "totp",
                    totp.trim()));
            JsonNode view = first.path("data");
            if (view.path("token").asString("").isEmpty() || view.path("sid").asString("").isEmpty()) {
                throw new BrokerHttp.Failure(0, "Kotak Neo refused the TOTP login" + BrokerHttp.reason(first));
            }
            JsonNode second = http.postJson(props.kotakApi() + "/login/1.0/tradeApiValidate", Map.of("Authorization",
                    consumerKey, "neo-fin-key", NEO_FIN_KEY, "sid", view.path("sid").asString(""), "Auth",
                    view.path("token").asString("")), Map.of("mpin", mpin.trim()));
            JsonNode trade = second.path("data");
            String token = trade.path("token").asString(""), sid = trade.path("sid").asString("");
            String base = trade.path("baseUrl").asString("").replaceAll("/+$", "");
            String ucc = trade.path("ucc").asString(view.path("ucc").asString(""));
            if (token.isEmpty() || sid.isEmpty()) {
                throw new BrokerHttp.Failure(0, "Kotak Neo refused the MPIN" + BrokerHttp.reason(second));
            }
            if (!app.clientId().equalsIgnoreCase(ucc)) {
                sessions.failed(account, app, user.subject(), "logged in as " + ucc + ", not " + app.clientId());
                return new KiteLogin.Result(false, account.displayName(), "Kotak logged in client " + ucc + ", but "
                        + account.displayName() + " is client " + app.clientId() + ". Nothing was stored.");
            }
            if (!trustedBase(base)) {
                throw new BrokerHttp.Failure(0, "Kotak Neo returned an unexpected base URL; nothing was stored");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("brokerUser", ucc);
            String funds;
            try {
                JsonNode limits = http.postForm(base + "/quick/user/limits", Map.of("Sid", sid, "Auth", token),
                        Map.of("jData", "{\"seg\":\"ALL\",\"exch\":\"ALL\",\"prod\":\"ALL\"}"));
                double net = Double.parseDouble(limits.path("Net").asString("NaN"));
                detail.put("net", net);
                funds = Double.isNaN(net) ? "limits not reported" : String.format("net limit Rs %,.0f", net);
            } catch (BrokerHttp.Failure | NumberFormatException e) {
                detail.put("limitsRead", e.getMessage());
                funds = "limits not read now";
            }
            Instant now = Instant.now();
            Instant expires = SessionActivation.next(now, LocalTime.of(23, 59, 59));
            sessions.activate(account, app, "kotak", user.subject(), Map.of("ucc", ucc, "token", token, "sid", sid,
                    "baseUrl", base), now, expires, detail);
            return new KiteLogin.Result(true, account.displayName(), "Logged in as Kotak client " + ucc + ", " + funds
                    + ". Session kept until the end of today.");
        } catch (RuntimeException e) {         // never a 500: the page says why (code review 7 Nov 2026)
            sessions.failed(account, app, user.subject(), e.getMessage());
            return new KiteLogin.Result(false, account.displayName(), "The login could not be completed: " + e.getMessage());
        }
    }

    private boolean trustedBase(String base) {
        try {
            URI uri = URI.create(base);
            URI login = URI.create(props.kotakApi());
            String host = uri.getHost() == null ? "" : uri.getHost();
            // https on Kotak's domain, or the configured login host with its own scheme (https in production; a local
            // stand-in in tests): never plain http to anywhere else (code review 7 Nov 2026)
            return ("https".equals(uri.getScheme()) && host.endsWith(".kotaksecurities.com"))
                    || (host.equals(login.getHost()) && java.util.Objects.equals(uri.getScheme(), login.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
