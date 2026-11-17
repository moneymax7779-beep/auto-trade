package com.autotrade.exec.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Web push to the installed app (mobile phase 4, 17 Nov 2026): the same people and texts as the Telegram alerts
 * ({@link TelegramNotifier} calls {@link #toAdmins} and {@link #toUser}; {@link PositionAlerts} {@link #toMembers}), to every
 * browser each person
 * allowed. Sent off the caller's thread (an alert loop never waits on a push service); a 404 / 410 drops the browser.
 * Nothing is sent, and no key is made, while {@code autotrade.execution.push.enabled} is off.
 */
@Component
class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A notification: its title, text, and the app page a tap opens; LIVE ones go out with high urgency. */
    record Message(String title, String body, String url, boolean urgent) {
        static Message of(String text) {
            boolean live = text != null && text.contains("LIVE");
            return new Message(live ? "auto-trade · LIVE" : "auto-trade", text == null ? "" : text, "/home", live);
        }
    }

    record Subscription(long id, long appUserId, String endpoint, String p256dh, String auth) {
    }

    private final JdbcTemplate jdbc;
    private final PushProperties props;
    private final String subject;
    private final HttpClient http;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-push");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    WebPushSender(DataSource dataSource, PushProperties props,
                  @Value("${AUTOTRADE_PUBLIC_BASE_URL:http://localhost:8095}") String publicBase) {
        this(dataSource, props, publicBase, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    WebPushSender(DataSource dataSource, PushProperties props, String publicBase, HttpClient http) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.props = props == null ? PushProperties.off() : props;
        this.subject = subject(this.props.subject(), publicBase);
        this.http = http;
    }

    /**
     * RFC 8292 "sub": the configured mailto:/https: address, else the site's https address; push services (Apple's
     * strictly) refuse anything else, so an http base falls back to a mailto: that names this host (review 17 Nov).
     */
    static String subject(String configured, String publicBase) {
        for (String s : new String[] {configured, publicBase}) {
            if (s != null && (s.startsWith("mailto:") || s.startsWith("https://"))) {
                return s.trim();
            }
        }
        log.warn("web push: no mailto: or https: subject (autotrade.execution.push.subject); using mailto:push@localhost");
        return "mailto:push@localhost";
    }

    boolean enabled() {
        return props.enabled();
    }

    /** The server's public VAPID key (made on first use); null while push is off. */
    synchronized String publicKey() {
        return enabled() ? keys()[0] : null;
    }

    void toUser(long appUserId, Message m) {
        send(subscriptions("where s.app_user_id = ?", appUserId), m);
    }

    /** A workspace's OWNER, TRADER, VIEWER and AUDITOR (the position alerts' members). */
    void toMembers(long tenantId, Message m) {
        send(subscriptions("where s.app_user_id in (select app_user_id from acct.tenant_membership where tenant_id = ? "
                + "and role in ('OWNER', 'TRADER', 'VIEWER', 'AUDITOR'))", tenantId), m);
    }

    /** As Telegram's admin chat: everyone who is ADMIN of a tenant. */
    void toAdmins(Message m) {
        send(subscriptions("where s.app_user_id in (select app_user_id from acct.tenant_membership where role = 'ADMIN')"), m);
    }

    private List<Subscription> subscriptions(String where, Object... args) {
        if (!enabled()) {
            return List.of();
        }
        return jdbc.query("select distinct s.id, s.app_user_id, s.endpoint, s.p256dh, s.auth from acct.push_subscription s "
                + "join acct.app_user u on u.id = s.app_user_id and u.status = 'ACTIVE' " + where, (rs, n) ->
                new Subscription(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)), args);
    }

    /**
     * Review 17 Nov: one alert can reach a person by two routes (the admin chat and their workspace's members), which
     * would ring the phone twice; the same text to the same browser within {@link #REPEAT} goes once.
     */
    static final Duration REPEAT = Duration.ofMinutes(2);
    private final java.util.Map<String, Instant> recent = new java.util.concurrent.ConcurrentHashMap<>();

    private void send(List<Subscription> targets, Message m) {
        Instant now = Instant.now();
        recent.values().removeIf(t -> t.isBefore(now.minus(REPEAT)));
        List<Subscription> fresh = targets.stream()
                .filter(s -> recent.putIfAbsent(s.id() + "|" + m.title() + "|" + m.body(), now) == null).toList();
        if (fresh.isEmpty()) {
            return;
        }
        sender.execute(() -> fresh.forEach(s -> deliver(s, m)));
    }

    /** One message to one browser now (also the Settings page's test); true when the push service accepted it. */
    boolean deliver(Subscription s, Message m) {
        try {
            String[] keys = keys();
            // no tag: a tag would make each alert replace the last on the lock screen (three LIVE entries would show as one)
            byte[] payload = JSON.writeValueAsString(Map.of("title", m.title(), "body", m.body(), "url", m.url()))
                    .getBytes(StandardCharsets.UTF_8);
            byte[] body = WebPushCrypto.encrypt(payload, WebPushCrypto.unb64(s.p256dh()), WebPushCrypto.unb64(s.auth()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(s.endpoint())).timeout(Duration.ofSeconds(15))
                    .header("Content-Encoding", "aes128gcm").header("Content-Type", "application/octet-stream")
                    .header("TTL", m.urgent() ? "600" : "3600").header("Urgency", m.urgent() ? "high" : "normal")
                    .header("Authorization", WebPushCrypto.vapid(s.endpoint(), subject, keys[0], keys[1],
                            Instant.now().getEpochSecond()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code == 404 || code == 410) {
                jdbc.update("delete from acct.push_subscription where id = ?", s.id());
                log.info("push subscription {} gone ({}): removed", s.id(), code);
                return false;
            }
            if (code / 100 != 2) {
                jdbc.update("update acct.push_subscription set failures = failures + 1, last_error = ? where id = ?",
                        "HTTP " + code, s.id());
                log.warn("push to subscription {} refused: HTTP {}", s.id(), code);
                return false;
            }
            jdbc.update("update acct.push_subscription set last_ok_at = now(), failures = 0, last_error = null where id = ?",
                    s.id());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            jdbc.update("update acct.push_subscription set failures = failures + 1, last_error = ? where id = ?",
                    e.getClass().getSimpleName(), s.id());
            log.warn("push to subscription {} failed: {}", s.id(), e.getClass().getSimpleName());
            return false;
        }
    }

    /** {public, private}: read, or made once (the first caller wins; a second server racing reads the winner's). */
    private synchronized String[] keys() {
        List<String[]> rows = jdbc.query("select public_key, private_key from acct.push_vapid where id = 1",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)});
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        try {
            String[] made = WebPushCrypto.newKeys();
            jdbc.update("insert into acct.push_vapid (id, public_key, private_key) values (1, ?, ?) on conflict (id) do nothing",
                    made[0], made[1]);
            log.info("web push: VAPID key pair made");
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("no P-256 key pair", e);
        }
        return keys();
    }
}
