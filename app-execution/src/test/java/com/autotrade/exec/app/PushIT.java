package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import com.autotrade.account.AccountStore;

/**
 * Mobile phase 4: web push end to end against a stand-in push service, which checks what a real one checks (the
 * aes128gcm coding, a VAPID token signed by the key the browser subscribed with) and decrypts every body as the browser
 * would. The Telegram alerts reach the installed apps of the same people; a browser the push service dropped (410) goes.
 */
@Testcontainers(disabledWithoutDocker = true)
class PushIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    /** A browser: its key pair and auth secret, and what reached it. */
    record Browser(KeyPair keys, byte[] auth, List<String> got) {
        String p256dh() {
            return WebPushCrypto.b64(WebPushCrypto.point((ECPublicKey) keys.getPublic()));
        }
    }

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static HttpServer service;
    private static String base;
    private static final Map<String, Browser> browsers = new ConcurrentHashMap<>();
    private static final List<String> problems = new CopyOnWriteArrayList<>();
    private static long alice, admin, viewer, tenant;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        AccountStore accounts = new AccountStore(dataSource);
        tenant = accounts.ensureTenant("t-alice");
        long adminTenant = accounts.ensureTenant("t-platform");
        alice = accounts.ensureUser("kc-alice", "alice");
        viewer = accounts.ensureUser("kc-viewer", "viewer");
        admin = accounts.ensureUser("kc-admin", "admin");
        accounts.ensureMembership(tenant, alice, "OWNER");
        accounts.ensureMembership(tenant, viewer, "VIEWER");
        accounts.ensureMembership(adminTenant, admin, "ADMIN");
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/push/", x -> {
            String id = x.getRequestURI().getPath().substring("/push/".length());
            byte[] body = x.getRequestBody().readAllBytes();
            Browser b = browsers.get(id);
            int code = 201;
            try {
                if (id.startsWith("gone")) {
                    code = 410;
                } else {
                    check(x.getRequestHeaders().getFirst("Authorization"), x.getRequestHeaders().getFirst("Content-Encoding"));
                    b.got().add(decrypt(b, body));
                }
            } catch (Exception e) {
                problems.add(id + ": " + e);
                code = 400;
            }
            x.sendResponseHeaders(code, -1);
            x.close();
        });
        service.start();
        base = "http://127.0.0.1:" + service.getAddress().getPort() + "/push/";
    }

    @AfterAll
    static void stop() {
        service.stop(0);
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from acct.push_subscription");
        browsers.clear();
        problems.clear();
    }

    private static WebPushSender sender(boolean enabled) {
        return new WebPushSender(dataSource, new PushProperties(enabled, "mailto:ops@example.invalid"), "https://app.example",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    private static Browser browser(long user, String id) throws Exception {
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        Browser b = new Browser(WebPushCrypto.generate(), auth, new CopyOnWriteArrayList<>());
        browsers.put(id, b);
        // inserted directly: the controller only takes https endpoints, this stand-in is plain http
        jdbc.update("insert into acct.push_subscription (app_user_id, endpoint, p256dh, auth) values (?, ?, ?, ?)",
                user, base + id, b.p256dh(), WebPushCrypto.b64(auth));
        return b;
    }

    /** What a push service checks: the content coding and a valid ES256 VAPID token for its origin, signed by k. */
    private static void check(String authorization, String encoding) throws Exception {
        assertThat(encoding).isEqualTo("aes128gcm");
        assertThat(authorization).startsWith("vapid t=");
        String jwt = authorization.substring(8, authorization.indexOf(", k="));
        String k = authorization.substring(authorization.indexOf(", k=") + 4);
        String[] parts = jwt.split("\\.");
        assertThat(new String(WebPushCrypto.unb64(parts[1]), StandardCharsets.UTF_8))
                .contains("\"aud\":\"" + base.substring(0, base.indexOf("/push/")) + "\"").contains("mailto:ops@example.invalid");
        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(WebPushCrypto.publicKey(WebPushCrypto.unb64(k)));
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(WebPushCrypto.unb64(parts[2]))).as("VAPID signature").isTrue();
    }

    private static String decrypt(Browser b, byte[] body) throws Exception {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idlen = body[20];
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idlen);
        byte[] uaPublic = WebPushCrypto.unb64(b.p256dh());
        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(b.keys().getPrivate());
        ecdh.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] ikm = WebPushCrypto.hkdf(b.auth(), ecdh.generateSecret(),
                WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic), 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = gcm.doFinal(Arrays.copyOfRange(body, 21 + idlen, body.length));
        return new String(plain, 0, plain.length - 1, StandardCharsets.UTF_8);
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void theTelegramAlertsReachTheSamePeoplesPhonesEvenWithoutTelegram(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Browser alicePhone = browser(alice, "alice-phone"), viewerPhone = browser(viewer, "viewer-phone");
        Browser adminPhone = browser(admin, "admin-phone");
        TelegramNotifier telegram = new TelegramNotifier(new KiteLoginProperties(null, null, null, null, null,
                dir.resolve("no-telegram.env").toString()), dataSource);
        telegram.webPush(sender(true));
        telegram.sendToMembers(tenant, "ZERODHA-1 LIVE: obc-v31 bought NIFTY 24650 CE, 6 lots at 147.25");
        telegram.send("Login board 09:10: ZERODHA-1 not logged in");
        waitFor(() -> alicePhone.got().size() == 1 && viewerPhone.got().size() == 1 && adminPhone.got().size() == 1);
        assertThat(problems).isEmpty();
        assertThat(alicePhone.got()).singleElement().asString().contains("\"title\":\"auto-trade · LIVE\"")
                .contains("bought NIFTY 24650 CE").contains("\"url\":\"/home\"").doesNotContain("\"tag\"");
        assertThat(viewerPhone.got()).hasSize(1);
        assertThat(adminPhone.got()).singleElement().asString().contains("Login board 09:10");
        assertThat(jdbc.queryForObject("select count(*) from acct.push_subscription where last_ok_at is not null", Long.class))
                .isEqualTo(3);
    }

    @Test
    void anAdminWhoIsAlsoAMemberGetsAnAlertOnce(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        long both = new AccountStore(dataSource).ensureUser("kc-both", "both");
        new AccountStore(dataSource).ensureMembership(tenant, both, "OWNER");
        new AccountStore(dataSource).ensureMembership(new AccountStore(dataSource).ensureTenant("t-platform"), both, "ADMIN");
        Browser phone = browser(both, "both-phone");
        TelegramNotifier telegram = new TelegramNotifier(new KiteLoginProperties(null, null, null, null, null,
                dir.resolve("no-telegram.env").toString()), dataSource);
        telegram.webPush(sender(true));
        telegram.send("ZERODHA-1 LIVE: order rejected");                 // as ExecutionAlerts: the admin chat ...
        telegram.sendToMembers(tenant, "ZERODHA-1 LIVE: order rejected"); // ... and the workspace's members
        waitFor(() -> phone.got().size() >= 1);
        Thread.sleep(500);
        assertThat(phone.got()).hasSize(1);
        assertThat(problems).isEmpty();
    }

    @Test
    void aMessageAboutTheTelegramChatStaysOnTelegram(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Browser phone = browser(alice, "alice-phone");
        TelegramNotifier telegram = new TelegramNotifier(new KiteLoginProperties(null, null, null, null, null,
                dir.resolve("no-telegram.env").toString()), dataSource);
        telegram.webPush(sender(true));
        telegram.sendToUser(alice, "auto-trade: this chat now gets alice's login links and alerts.", false);
        Thread.sleep(500);
        assertThat(phone.got()).isEmpty();
    }

    @Test
    void theSubjectIsAMailtoOrHttpsAddress() {
        assertThat(WebPushSender.subject(null, "https://app.example")).isEqualTo("https://app.example");
        assertThat(WebPushSender.subject("mailto:ops@example.invalid", "https://app.example")).isEqualTo("mailto:ops@example.invalid");
        assertThat(WebPushSender.subject("", "http://localhost:8095")).isEqualTo("mailto:push@localhost");
    }

    @Test
    void aBrowserThePushServiceDroppedIsRemoved() throws Exception {
        browser(alice, "gone-1");
        Browser kept = browser(alice, "alice-phone");
        sender(true).toUser(alice, WebPushSender.Message.of("test"));
        waitFor(() -> kept.got().size() == 1 && jdbc.queryForObject("select count(*) from acct.push_subscription", Long.class) == 1);
        assertThat(jdbc.queryForList("select endpoint from acct.push_subscription", String.class)).containsExactly(base + "alice-phone");
    }

    @Test
    void offMeansNothingIsSentAndNoKeyIsMade() throws Exception {
        Browser phone = browser(alice, "alice-phone");
        WebPushSender off = sender(false);
        off.toUser(alice, WebPushSender.Message.of("x"));
        Thread.sleep(300);
        assertThat(phone.got()).isEmpty();
        assertThat(off.publicKey()).isNull();
        PushController api = new PushController(off, new Access(dataSource), dataSource);
        assertThatThrownBy(() -> api.subscribe("kc-alice", new PushController.Subscribe("https://push.example/x",
                new PushController.Keys(phone.p256dh(), WebPushCrypto.b64(new byte[16])), "ua")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not switched on");
    }

    @Test
    void subscribingChecksTheKeysAndListsOnlyTheCallersOwnBrowsers() throws Exception {
        PushController api = new PushController(sender(true), new Access(dataSource), dataSource);
        String key = WebPushCrypto.b64(WebPushCrypto.point((ECPublicKey) WebPushCrypto.generate().getPublic()));
        String auth = WebPushCrypto.b64(new byte[16]);
        assertThatThrownBy(() -> api.subscribe("kc-alice", new PushController.Subscribe("http://push.example/x",
                new PushController.Keys(key, auth), null))).hasMessageContaining("not a push subscription");
        assertThatThrownBy(() -> api.subscribe("kc-alice", new PushController.Subscribe("https://push.example/x",
                new PushController.Keys("AAAA", auth), null))).hasMessageContaining("not a push subscription");
        api.subscribe("kc-alice", new PushController.Subscribe("https://fcm.googleapis.com/fcm/send/a1",
                new PushController.Keys(key, auth), "iPhone Safari"));
        api.subscribe("kc-viewer", new PushController.Subscribe("https://web.push.apple.com/v1",
                new PushController.Keys(key, auth), "Android Chrome"));
        Map<String, Object> mine = api.state("kc-alice");
        assertThat(mine.get("enabled")).isEqualTo(true);
        assertThat((String) mine.get("publicKey")).hasSize(87);                     // 65 bytes, base64url
        assertThat((List<?>) mine.get("devices")).singleElement().asString().contains("iPhone Safari")
                .contains("fcm.googleapis.com").doesNotContain("/fcm/send/a1");
        assertThat(api.unsubscribe("kc-viewer", new PushController.Unsubscribe("https://fcm.googleapis.com/fcm/send/a1", null)))
                .containsEntry("removed", 0);                                       // not theirs
        assertThat(api.unsubscribe("kc-alice", new PushController.Unsubscribe("https://fcm.googleapis.com/fcm/send/a1", null)))
                .containsEntry("removed", 1);
        assertThat(Files.exists(Path.of("."))).isTrue();
    }
}
