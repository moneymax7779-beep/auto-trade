package com.autotrade.exec.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web push for the signed-in person (mobile phase 4, 17 Nov 2026): the server's public key, their browsers
 * (subscribe, list, remove) and a test message. Each call acts on the caller's own browsers only.
 */
@RestController
class PushController {

    record Keys(String p256dh, String auth) {
    }

    record Subscribe(String endpoint, Keys keys, String userAgent) {
    }

    /** One of the caller's browsers: by its push endpoint (this browser) or by its row id (the device list). */
    record Unsubscribe(String endpoint, Long id) {
    }

    private final WebPushSender push;
    private final Access access;
    private final JdbcTemplate jdbc;

    PushController(WebPushSender push, Access access, DataSource dataSource) {
        this.push = push;
        this.access = access;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @GetMapping("/api/execution/push")
    Map<String, Object> state(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", push.enabled());
        out.put("publicKey", push.publicKey());
        out.put("devices", jdbc.queryForList("select id, user_agent \"userAgent\", created_at::text \"createdAt\", "
                + "last_ok_at::text \"lastOk\", last_error \"lastError\", endpoint from acct.push_subscription "
                + "where app_user_id = ? order by id", user.id()).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.put("endpoint", host(String.valueOf(r.get("endpoint"))));   // which push service, not the address
                    return m;
                }).toList());
        return out;
    }

    @PostMapping("/api/execution/push/subscribe")
    Map<String, Object> subscribe(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                  @RequestBody Subscribe s) {
        Access.User user = access.user(subject);
        if (!push.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "notifications are not switched on for this server yet");
        }
        if (s.endpoint() == null || !s.endpoint().startsWith("https://") || s.endpoint().length() > 1000 || s.keys() == null
                || s.keys().p256dh() == null || s.keys().auth() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a push subscription");
        }
        try {
            WebPushCrypto.publicKey(WebPushCrypto.unb64(s.keys().p256dh()));       // a real P-256 point, or refuse now
            if (WebPushCrypto.unb64(s.keys().auth()).length != 16) {
                throw new IllegalArgumentException("auth");
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a push subscription");
        }
        String agent = s.userAgent() == null ? null : s.userAgent().substring(0, Math.min(200, s.userAgent().length()));
        // the same browser again (or one that moved to another person on a shared phone): it is now this person's
        jdbc.update("insert into acct.push_subscription (app_user_id, endpoint, p256dh, auth, user_agent) values (?, ?, ?, ?, ?) "
                + "on conflict (endpoint) do update set app_user_id = excluded.app_user_id, p256dh = excluded.p256dh, "
                + "auth = excluded.auth, user_agent = excluded.user_agent, failures = 0, last_error = null",
                user.id(), s.endpoint(), s.keys().p256dh(), s.keys().auth(), agent);
        return Map.of("subscribed", true);
    }

    @PostMapping("/api/execution/push/unsubscribe")
    Map<String, Object> unsubscribe(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                    @RequestBody Unsubscribe e) {
        Access.User user = access.user(subject);
        int n = jdbc.update("delete from acct.push_subscription where app_user_id = ? and (endpoint = ? or id = ?)",
                user.id(), e.endpoint() == null ? "" : e.endpoint(), e.id() == null ? -1 : e.id());
        return Map.of("removed", n);
    }

    @PostMapping("/api/execution/push/test")
    Map<String, Object> test(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        if (!push.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "notifications are not switched on for this server yet");
        }
        List<WebPushSender.Subscription> mine = jdbc.query("select id, app_user_id, endpoint, p256dh, auth from "
                + "acct.push_subscription where app_user_id = ?", (rs, n) -> new WebPushSender.Subscription(rs.getLong(1),
                rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)), user.id());
        long ok = mine.stream().filter(s -> push.deliver(s, new WebPushSender.Message("auto-trade",
                "Test: notifications reach this device.", "/home", false))).count();
        return Map.of("devices", mine.size(), "delivered", ok);
    }

    private static String host(String endpoint) {
        try {
            return java.net.URI.create(endpoint).getHost();
        } catch (Exception e) {
            return "?";
        }
    }
}
