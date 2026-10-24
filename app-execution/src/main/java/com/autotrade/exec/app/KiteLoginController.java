package com.autotrade.exec.app;

import java.net.URI;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The browser side of the daily Kite login (see {@link KiteLogin}); both pages are plain HTML because they are opened
 * from a Telegram link, behind the same password gate as the UI:
 * <ul>
 *   <li>{@code GET /api/execution/accounts/{id}/kite/login}: a page that forwards to Zerodha's login (HTML, not a
 *       redirect, so it passes the UI's pass-through unchanged);</li>
 *   <li>{@code GET /api/execution/kite/callback}: the Kite app's redirect URL; Kite adds {@code request_token},
 *       {@code status} and the {@code state} we sent.</li>
 * </ul>
 */
@RestController
class KiteLoginController {

    private final Access access;
    private final KiteLogin login;

    KiteLoginController(Access access, KiteLogin login) {
        this.access = access;
        this.login = login;
    }

    @GetMapping("/api/execution/accounts/{id}/kite/login")
    ResponseEntity<String> login(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                 @PathVariable long id) {
        try {
            URI url = login.start(access.user(subject), id);
            String href = HtmlUtils.htmlEscape(url.toString());
            return html("<meta http-equiv=\"refresh\" content=\"0; url=" + href + "\"><p>Opening the Zerodha login... "
                    + "<a href=\"" + href + "\">continue</a></p>");
        } catch (RuntimeException e) {
            return html("<h3>Kite login not started</h3><p>" + HtmlUtils.htmlEscape(String.valueOf(e.getMessage())) + "</p>");
        }
    }

    @GetMapping("/api/execution/kite/callback")
    ResponseEntity<String> callback(@RequestParam(value = "request_token", required = false) String requestToken,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "state", required = false) String state) {
        KiteLogin.Result r = login.callback(requestToken, status, state);
        String title = r.ok() ? "Kite session active" : "Kite login failed";
        return html("<h3>" + title + (r.account() == null ? "" : ": " + HtmlUtils.htmlEscape(r.account())) + "</h3><p>"
                + HtmlUtils.htmlEscape(r.message()) + "</p>");
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .body("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                        + "content=\"width=device-width\"><title>auto-trade: Kite login</title></head>"
                        + "<body style=\"font-family:sans-serif;max-width:40em;margin:2em auto;padding:0 1em\">" + body
                        + "</body></html>");
    }
}
