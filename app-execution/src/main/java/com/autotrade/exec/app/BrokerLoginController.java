package com.autotrade.exec.app;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;

/**
 * The daily logins of the members' other brokers (trader workspaces, 7 Nov 2026; Zerodha's is {@link KiteLoginController}):
 * <ul>
 *   <li>{@code GET /api/execution/accounts/{id}/upstox/login} and {@code GET /api/execution/upstox/callback}: Upstox's
 *       OAuth redirect (HTML pages, as for Kite);</li>
 *   <li>{@code POST /api/execution/accounts/{id}/kotak/login} {@code {"totp", "mpin"}}: Kotak Neo, passed through and
 *       never stored;</li>
 *   <li>{@code POST /api/execution/accounts/{id}/groww/login}: Groww, once the API key is approved on Groww today;</li>
 *   <li>{@code POST /api/execution/accounts/{id}/logout}: ends the account's session here (any broker); a LIVE day on it
 *       goes back to PAPER.</li>
 * </ul>
 */
@RestController
class BrokerLoginController {

    private final Access access;
    private final AccountStore accounts;
    private final UpstoxLogin upstox;
    private final KotakNeoLogin kotak;
    private final GrowwLogin groww;
    private final SessionActivation sessions;

    BrokerLoginController(Access access, DataSource dataSource, UpstoxLogin upstox, KotakNeoLogin kotak, GrowwLogin groww,
                          SessionActivation sessions) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.upstox = upstox;
        this.kotak = kotak;
        this.groww = groww;
        this.sessions = sessions;
    }

    @GetMapping("/api/execution/accounts/{id}/upstox/login")
    ResponseEntity<String> upstoxLogin(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                       @PathVariable long id) {
        try {
            URI url = upstox.start(access.user(subject), id);
            String href = HtmlUtils.htmlEscape(url.toString());
            return html("Upstox", "<meta http-equiv=\"refresh\" content=\"0; url=" + href + "\"><p>Opening the Upstox "
                    + "login... <a href=\"" + href + "\">continue</a></p>");
        } catch (RuntimeException e) {
            return html("Upstox", "<h3>Upstox login not started</h3><p>" + HtmlUtils.htmlEscape(String.valueOf(e.getMessage()))
                    + "</p>");
        }
    }

    @GetMapping("/api/execution/upstox/callback")
    ResponseEntity<String> upstoxCallback(@RequestParam(value = "code", required = false) String code,
                                          @RequestParam(value = "state", required = false) String state) {
        KiteLogin.Result r = upstox.callback(code, state);
        return html("Upstox", "<h3>" + (r.ok() ? "Upstox session active" : "Upstox login failed")
                + (r.account() == null ? "" : ": " + HtmlUtils.htmlEscape(r.account())) + "</h3><p>"
                + HtmlUtils.htmlEscape(r.message()) + "</p>");
    }

    record KotakCodes(String totp, String mpin) {
    }

    @PostMapping("/api/execution/accounts/{id}/kotak/login")
    Map<String, Object> kotakLogin(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @PathVariable long id, @RequestBody KotakCodes body) {
        return result(() -> kotak.login(access.user(subject), id, body.totp(), body.mpin()));
    }

    @PostMapping("/api/execution/accounts/{id}/groww/login")
    Map<String, Object> growwLogin(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @PathVariable long id) {
        return result(() -> groww.login(access.user(subject), id));
    }

    @PostMapping("/api/execution/accounts/{id}/logout")
    Map<String, Object> logout(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable long id) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        Access.require(user.emergency(a.tenantId()), "no right to act on this account");
        sessions.logout(a, user.subject());
        return Map.of("account", a.displayName(), "connection", "DISCONNECTED");
    }

    private static Map<String, Object> result(java.util.function.Supplier<KiteLogin.Result> login) {
        KiteLogin.Result r;
        try {
            r = login.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("account", r.account());
        out.put("message", r.message());
        return out;
    }

    private static ResponseEntity<String> html(String broker, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .body("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                        + "content=\"width=device-width\"><title>auto-trade: " + broker + " login</title></head>"
                        + "<body style=\"font-family:sans-serif;max-width:40em;margin:2em auto;padding:0 1em\">" + body
                        + "</body></html>");
    }
}
