package com.autotrade.exec.app;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

/**
 * The page a broker's login sends the browser back to (Kite and Upstox callbacks). Mobile phase 2 (17 Nov 2026): it used
 * to be a dead end on a phone; it now has a thumb-sized "Back to auto-trade" button and, after a successful login, returns
 * to the app's Home by itself after 3 seconds, so the morning login is tap, broker page, back.
 */
final class BrokerLoginPage {

    static final String HOME = "/home";

    private BrokerLoginPage() {
    }

    /** {@code title} and {@code message} are escaped here; {@code ok} adds the return to Home. */
    static ResponseEntity<String> result(String broker, boolean ok, String title, String account, String message) {
        String heading = HtmlUtils.htmlEscape(title) + (account == null ? "" : ": " + HtmlUtils.htmlEscape(account));
        String back = "<p><a href=\"" + HOME + "\" style=\"display:inline-block;padding:12px 20px;border-radius:8px;"
                + "background:#0369a1;color:#fff;text-decoration:none;font-weight:600\">Back to auto-trade</a></p>";
        String body = "<h3>" + heading + "</h3><p>" + HtmlUtils.htmlEscape(String.valueOf(message)) + "</p>" + back
                + (ok ? "<p style=\"color:#59636e;font-size:14px\">Returning to auto-trade in 3 seconds.</p>" : "");
        return page(broker, ok ? "<meta http-equiv=\"refresh\" content=\"3; url=" + HOME + "\">" : "", body);
    }

    static ResponseEntity<String> page(String broker, String head, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .body("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                        + "content=\"width=device-width, initial-scale=1\"><title>auto-trade: " + broker + " login</title>"
                        + head + "</head><body style=\"font-family:sans-serif;max-width:40em;margin:2em auto;padding:0 1em\">"
                        + body + "</body></html>");
    }
}
