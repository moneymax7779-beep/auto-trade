package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.autotrade.account.AccountStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A member's broker apps and the daily logins of Upstox, Kotak Neo and Groww against stand-ins for their APIs: the keys
 * are write-only (owner-only files, never in an answer), each login must be the pinned client, a code or state is used
 * once, a TOTP/MPIN only passes through, a base URL that is not Kotak's is never trusted, Groww's checksum, and a logout
 * sends a LIVE day back to PAPER.
 */
@Testcontainers(disabledWithoutDocker = true)
class BrokerLoginIT {

    private static final DockerImageName TIMESCALE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg17").asCompatibleSubstituteFor("postgres");

    @TempDir
    static Path dir;

    private static PostgreSQLContainer postgres;
    private static HikariDataSourceHolder db;
    private static JdbcTemplate jdbc;
    private static HttpServer broker;
    private static final Map<String, String> seen = new ConcurrentHashMap<>();
    private static volatile String kotakBase;                  // what tradeApiValidate returns as baseUrl
    private static volatile String upstoxUser = "7AB12C", kotakUcc = "KT1234", growwUcc = "924189";
    private static MembersController members;
    private static AccountAdminController admin;
    private static BrokerAppController apps;
    private static BrokerLoginController logins;
    private static UpstoxLogin upstox;
    private static WorkspaceController workspaces;

    /** Keeps the pool for the class. */
    private record HikariDataSourceHolder(com.zaxxer.hikari.HikariDataSource ds) {
    }

    @BeforeAll
    static void start() throws IOException {
        postgres = new PostgreSQLContainer(TIMESCALE);
        postgres.start();
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        db = new HikariDataSourceHolder(new com.zaxxer.hikari.HikariDataSource(config));
        Flyway.configure().dataSource(db.ds()).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(db.ds());
        broker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broker.createContext("/", BrokerLoginIT::handle);
        broker.start();
        String base = "http://127.0.0.1:" + broker.getAddress().getPort();
        kotakBase = base;

        AccountStore accounts = new AccountStore(db.ds());
        long owner = accounts.ensureTenant("owner");
        long satya = accounts.ensureUser("satya", "satya");
        accounts.ensureMembership(owner, satya, "ADMIN");
        long signal = accounts.ensureSignalSourceAccount(owner, satya, "SIGNAL-1", 500_000, LocalDate.of(2026, 11, 1),
                "../config/risk/signal-source.v2.yaml");
        accounts.ensureCopyGroup(owner, "LIVE-SET", signal);

        Access access = new Access(db.ds());
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.ds()));
        KiteLoginProperties kite = new KiteLoginProperties(dir.resolve("secrets").toString(),
                dir.resolve("sessions").toString(), "https://app.example", null, null, "/nonexistent/bot.env");
        BrokerLoginProperties props = new BrokerLoginProperties(dir.resolve("store").toString(), base, base, base,
                "34.1.2.3");
        TelegramNotifier telegram = new TelegramNotifier(kite, db.ds());
        members = new MembersController(access, db.ds(), tx, new KeycloakAdmin(new KeycloakAdminProperties(null, null, null,
                null)), telegram);
        admin = new AccountAdminController(access, db.ds(), tx, new ExecutionProperties(null, "*", 1000, null, 0, "it", null));
        apps = new BrokerAppController(access, db.ds(), tx, kite, props);
        SessionActivation sessions = new SessionActivation(db.ds(), tx, kite, props);
        upstox = new UpstoxLogin(sessions, db.ds(), props);
        logins = new BrokerLoginController(access, db.ds(), upstox, new KotakNeoLogin(sessions, props),
                new GrowwLogin(sessions, props), sessions);
        workspaces = new WorkspaceController(access, db.ds(), tx, telegram);
    }

    @AfterAll
    static void stop() {
        broker.stop(0);
        db.ds().close();
        postgres.stop();
    }

    private static void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.put(path, body + " | " + x.getRequestHeaders().entrySet());
        switch (path) {
            case "/v2/login/authorization/token" -> reply(x, body.contains("code=good") && body.contains("client_secret=up-secret")
                    ? 200 : 400, body.contains("code=good") ? "{\"user_id\":\"" + upstoxUser + "\",\"access_token\":\"up-token\"}"
                    : "{\"status\":\"error\",\"errors\":[{\"error_code\":\"UDAPI100057\",\"message\":\"Invalid Auth code\"}]}");
            case "/v2/user/get-funds-and-margin" -> reply(x, 200, "{\"data\":{\"equity\":{\"available_margin\":125000.5}}}");
            case "/login/1.0/tradeApiLogin" -> reply(x, 200, body.contains("\"totp\":\"123456\"")
                    ? "{\"data\":{\"token\":\"view\",\"sid\":\"s1\",\"ucc\":\"" + kotakUcc + "\"}}"
                    : "{\"error\":[{\"code\":\"10\",\"message\":\"Invalid TOTP\"}]}");
            case "/login/1.0/tradeApiValidate" -> reply(x, 200, body.contains("\"mpin\":\"4321\"")
                    ? "{\"data\":{\"token\":\"trade\",\"sid\":\"s2\",\"ucc\":\"" + kotakUcc + "\",\"baseUrl\":\"" + kotakBase + "\"}}"
                    : "{\"error\":[{\"code\":\"11\",\"message\":\"Invalid MPIN\"}]}");
            case "/quick/user/limits" -> reply(x, 200, "{\"Net\":\"98000.25\",\"stat\":\"Ok\"}");
            case "/v1/token/api/access" -> {
                String ts = body.replaceAll(".*\"timestamp\":\"([0-9]+)\".*", "$1");
                boolean ok = body.contains("\"checksum\":\"" + GrowwLogin.checksum("gr-secret-xyz", ts) + "\"");
                reply(x, ok ? 200 : 400, ok ? "{\"token\":\"gr-token\",\"expiry\":\"2099-01-01T06:00:00\",\"isActive\":true}"
                        : "{\"status\":\"FAILURE\",\"error\":{\"displayMessage\":\"Key not approved\"}}");
            }
            case "/v1/user/detail" -> reply(x, 200, "{\"status\":\"SUCCESS\",\"payload\":{\"ucc\":\"" + growwUcc + "\"}}");
            case "/v1/margins/detail/user" -> reply(x, 200,
                    "{\"payload\":{\"fno_margin_details\":{\"option_buy_balance_available\":51000}}}");
            default -> reply(x, 404, "{}");
        }
    }

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private static int code(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private static long member(String username) {
        return (Long) members.add("satya", new MembersController.NewMember(username, null, "OWNER", null, "CHILD", null, null))
                .get("tenantId");
    }

    private static long account(String who, long tenant, String name, String brokerType, String clientId) {
        return ((Number) admin.create(who, new AccountAdminController.NewAccount(name, brokerType, null, clientId, null,
                200_000.0, "FIXED", null, null, List.of("ma-cross"), true, tenant)).get("id")).longValue();
    }

    private static long app(String who, long tenant, String brokerType, String name) {
        return ((Number) apps.create(who, tenant, new BrokerAppController.NewApp(brokerType, name)).get("id")).longValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void keysAreWriteOnlyAndAnAppIsTheWorkspacesOwn() throws Exception {
        long t = member("kavi");
        long id = app("kavi", t, "UPSTOX", "My Upstox");
        assertThat(code(() -> app("kavi", t, "UPSTOX", "My Upstox"))).as("names are unique").isEqualTo(400);
        assertThat(code(() -> app("kavi", t, "PAPER", "x1"))).isEqualTo(400);
        assertThat(code(() -> apps.keys("kavi", id, new BrokerAppController.Keys("short", "secret-12345", null))))
                .isEqualTo(400);
        assertThat(code(() -> apps.keys("kavi", id, new BrokerAppController.Keys("has space key", "secret-12345", null))))
                .isEqualTo(400);
        assertThat(code(() -> apps.keys("kavi", id, new BrokerAppController.Keys("same-value-1", "same-value-1", null))))
                .isEqualTo(400);
        apps.keys("kavi", id, new BrokerAppController.Keys("up-key-abcd", "up-secret", null));
        Map<String, Object> view = apps.apps("kavi", t);
        Map<String, Object> a = ((List<Map<String, Object>>) view.get("apps")).getFirst();
        assertThat(a).containsEntry("keysSet", true).containsEntry("key_hint", "...abcd").containsEntry("secret_set_by", "kavi")
                .containsEntry("redirect_uri", "https://app.example/api/execution/upstox/callback");
        assertThat(a.toString()).doesNotContain("up-key-abcd").doesNotContain("up-secret");
        assertThat(((Map<String, Object>) view.get("register")).get("ZERODHA").toString()).contains("34.1.2.3");
        Path file = dir.resolve("store").resolve("t" + t + "-p" + id + "-secret");
        assertThat(Files.readString(file)).isEqualTo("up-secret");
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where detail::text like '%up-secret%' "
                + "or detail::text like '%up-key-abcd%'", Integer.class)).isZero();
        // another workspace: not found; SUPPORT without a grant: refused
        long other = member("lata");
        assertThat(code(() -> apps.keys("lata", id, new BrokerAppController.Keys("x-key-1234", "x-secret-1", null))))
                .isEqualTo(404);
        assertThat(code(() -> apps.keys("satya", id, new BrokerAppController.Keys("x-key-1234", "x-secret-1", null))))
                .isEqualTo(403);
        // linking: the same workspace and broker, an active app, not while logged in
        long acc = account("kavi", t, "Upstox", "UPSTOX", "7AB12C");
        long kite = app("kavi", t, "ZERODHA", "My Kite");
        assertThat(code(() -> apps.link("kavi", acc, new BrokerAppController.Link(kite)))).isEqualTo(400);
        long lataApp = app("lata", other, "UPSTOX", "Lata Upstox");
        assertThat(code(() -> apps.link("kavi", acc, new BrokerAppController.Link(lataApp)))).isEqualTo(404);
        apps.link("kavi", acc, new BrokerAppController.Link(id));
        apps.status("kavi", id, new BrokerAppController.Status(false));
        assertThat(code(() -> apps.link("kavi", acc, new BrokerAppController.Link(id)))).isEqualTo(400);
        apps.status("kavi", id, new BrokerAppController.Status(true));
    }

    @Test
    void upstoxLogsInThePinnedClientOnceAndLogoutEndsALiveDay() {
        long t = member("mala");
        long id = app("mala", t, "UPSTOX", "Upstox app");
        apps.keys("mala", id, new BrokerAppController.Keys("up-key-mala", "up-secret", null));
        long acc = account("mala", t, "Upstox", "UPSTOX", "7AB12C");
        apps.link("mala", acc, new BrokerAppController.Link(id));
        Access.User mala = new Access(db.ds()).user("mala");
        URI url = upstox.start(mala, acc);
        assertThat(url.toString()).startsWith(kotakBase + "/v2/login/authorization/dialog?response_type=code&client_id="
                + "up-key-mala&redirect_uri=https%3A%2F%2Fapp.example%2Fapi%2Fexecution%2Fupstox%2Fcallback&state=");
        String state = url.getQuery().replaceAll(".*state=", "");
        assertThat(upstox.callback("bad", state).ok()).isFalse();
        assertThat(upstox.callback("good", state).message()).as("a state is used once").contains("expired or was already used");
        state = upstox.start(mala, acc).getQuery().replaceAll(".*state=", "");
        KiteLogin.Result r = upstox.callback("good", state);
        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("Upstox client 7AB12C").contains("125,001");
        assertThat(jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class, acc))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select token_ref from acct.broker_session where broker_account_id = ? and status = "
                + "'ACTIVE'", String.class, acc)).isEqualTo("session-file:upstox-" + acc + ".token");
        assertThat(dir.resolve("sessions").resolve("upstox-" + acc + ".token")).exists();
        assertThat(jdbc.queryForObject("select verified_at is not null from acct.broker_credential_profile where id = ?",
                Boolean.class, id)).isTrue();
        // another client's login is refused and stores nothing
        upstoxUser = "ZZ9999";
        state = upstox.start(mala, acc).getQuery().replaceAll(".*state=", "");
        assertThat(upstox.callback("good", state).message()).contains("ZZ9999").contains("Nothing was stored");
        upstoxUser = "7AB12C";
        // logout: the session ends here, the token file goes, and a LIVE day there would go back to PAPER
        jdbc.update("insert into acct.trading_day_event (tenant_id, broker_account_id, mode, reason) values (?, ?, 'LIVE', "
                + "'IT')", t, acc);
        logins.logout("mala", acc);
        assertThat(dir.resolve("sessions").resolve("upstox-" + acc + ".token")).doesNotExist();
        assertThat(jdbc.queryForObject("select mode from acct.trading_state(?, now())", String.class, t)).isEqualTo("PAPER");
        member("pia");
        assertThat(code(() -> logins.logout("pia", acc))).as("another workspace's account").isEqualTo(404);
    }

    @Test
    void kotakPassesTheCodesThroughAndTrustsOnlyItsOwnBaseUrl() {
        long t = member("neha");
        long id = app("neha", t, "KOTAK_NEO", "Neo app");
        assertThat(code(() -> apps.keys("neha", id, new BrokerAppController.Keys("consumer-key-1", null, "12345"))))
                .as("a mobile number").isEqualTo(400);
        apps.keys("neha", id, new BrokerAppController.Keys("consumer-key-1", null, "98765 43210"));
        long acc = account("neha", t, "Kotak", "KOTAK_NEO", "KT1234");
        apps.link("neha", acc, new BrokerAppController.Link(id));
        assertThat(code(() -> logins.kotakLogin("neha", acc, new BrokerLoginController.KotakCodes("12345", "4321"))))
                .isEqualTo(400);
        assertThat(code(() -> logins.kotakLogin("neha", acc, new BrokerLoginController.KotakCodes("123456", "12")))).isEqualTo(400);
        Map<String, Object> wrong = logins.kotakLogin("neha", acc, new BrokerLoginController.KotakCodes("654321", "4321"));
        assertThat(wrong).containsEntry("ok", false);
        assertThat(String.valueOf(wrong.get("message"))).contains("Invalid TOTP").doesNotContain("654321");
        Map<String, Object> ok = logins.kotakLogin("neha", acc, new BrokerLoginController.KotakCodes("123456", "4321"));
        assertThat(ok).containsEntry("ok", true);
        assertThat(String.valueOf(ok.get("message"))).contains("98,000");
        assertThat(seen.get("/login/1.0/tradeApiLogin")).contains("\"mobileNumber\":\"+919876543210\"")
                .contains("\"ucc\":\"KT1234\"").contains("Authorization=[consumer-key-1]").containsIgnoringCase("neo-fin-key=[neotradeapi]");
        assertThat(jdbc.queryForObject("select count(*) from exec.audit_log where detail::text like '%4321%' "
                + "or detail::text like '%123456%'", Integer.class)).as("never audited").isZero();
        // a base URL that is not Kotak's: nothing stored, nothing sent there
        kotakBase = "https://evil.example.com";
        Map<String, Object> evil = logins.kotakLogin("neha", acc, new BrokerLoginController.KotakCodes("123456", "4321"));
        assertThat(evil).containsEntry("ok", false);
        assertThat(String.valueOf(evil.get("message"))).contains("unexpected base URL");
        kotakBase = "http://127.0.0.1:" + broker.getAddress().getPort();
    }

    @Test
    void growwNeedsTheDaysApprovalAndThePinnedClient() {
        long t = member("ojas");
        long id = app("ojas", t, "GROWW", "Groww app");
        apps.keys("ojas", id, new BrokerAppController.Keys("gr-key-abcdef", "gr-secret-wrong", null));
        long acc = account("ojas", t, "Groww", "GROWW", "924189");
        apps.link("ojas", acc, new BrokerAppController.Link(id));
        Map<String, Object> refused = logins.growwLogin("ojas", acc);
        assertThat(refused).containsEntry("ok", false);
        assertThat(String.valueOf(refused.get("message"))).contains("Key not approved");
        apps.keys("ojas", id, new BrokerAppController.Keys("gr-key-abcdef", "gr-secret-xyz", null));
        Map<String, Object> ok = logins.growwLogin("ojas", acc);
        assertThat(ok).containsEntry("ok", true);
        assertThat(String.valueOf(ok.get("message"))).contains("Groww client 924189").contains("51,000");
        assertThat(jdbc.queryForObject("select expires_at::text from acct.broker_session where broker_account_id = ? "
                + "and status = 'ACTIVE'", String.class, acc)).startsWith("2099-01-01");
        // the pinned client: a key of another client stores nothing
        growwUcc = "111111";
        assertThat(String.valueOf(logins.growwLogin("ojas", acc).get("message"))).contains("client 111111");
        growwUcc = "924189";
        // no LIVE for brokers without an adapter: the switch says so
        workspaces.broker("ojas", t, new WorkspaceController.Broker(acc));
        assertThat(code(() -> workspaces.mode("ojas", t, new WorkspaceController.Mode("LIVE", "LIVE")))).isEqualTo(409);
    }
}
