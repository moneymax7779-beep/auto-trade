package com.autotrade.exec.app;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.KillSwitches;
import com.autotrade.zerodha.KiteAuth;
import com.autotrade.zerodha.KiteConfig;
import com.autotrade.zerodha.KiteHttp;
import com.autotrade.zerodha.KiteSessionCheck;

import tools.jackson.databind.json.JsonMapper;

/**
 * The daily Kite login of a ZERODHA broker account (live plan sections 6 and 12, L3; user go-ahead 24 Oct 2026, "C1"):
 * <ol>
 *   <li>{@link #start}: a user allowed to act on the account asks for its login page; a one-time {@code state}
 *       (10 minutes) binds Kite's redirect to that account and user.</li>
 *   <li>The user logs in at Zerodha themselves (password and TOTP stay with Zerodha); Kite redirects the browser to
 *       the app's redirect URL with a {@code request_token}.</li>
 *   <li>{@link #callback}: the account must have its Zerodha client id pinned; the request token is exchanged for the
 *       day's access token (with the app secret), the login must come from that client id, the equity margin is read
 *       once (a read call), the token is stored in the session folder, acct.broker_session gets the new ACTIVE row
 *       (token reference, expiry 06:00 IST next morning) and the account's connection becomes ACTIVE; audited as
 *       SESSION_LOGIN.</li>
 * </ol>
 * One login or expiry at a time per account (an in-process lock; one worker serves an account). Expired sessions are
 * swept every minute, account by account (TOKEN_EXPIRED and the token file deleted only when no ACTIVE session is
 * left). At 08:30 IST on weekdays every enabled ZERODHA DRY_RUN / LIVE account without an ACTIVE session gets its login
 * link by Telegram. Nothing here places an order, and LIVE trading stays refused by the broker registry.
 */
@Component
class KiteLogin {

    private static final Logger log = LoggerFactory.getLogger(KiteLogin.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final LocalTime TOKEN_FLUSH = LocalTime.of(6, 0);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What a login attempt ended with, for the page the browser lands on. */
    record Result(boolean ok, String account, String message) {
    }

    private record Pending(long accountId, String subject, Instant created) {
    }

    private record Profile(String apiKeyRef, String apiSecretRef, String externalUserId) {
    }

    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KillSwitches audit;
    private final BrokerSecrets secrets;
    private final KiteLoginProperties props;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Long, Object> accountLocks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient telegram = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    KiteLogin(DataSource dataSource, TransactionTemplate tx, KiteLoginProperties props) {
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.audit = new KillSwitches(dataSource);
        this.secrets = new BrokerSecrets(Path.of(props.secretsDir()), Path.of(props.sessionsDir()));
        this.props = props;
    }

    /** The Zerodha login page for this account (the caller must be allowed to act on it). */
    URI start(Access.User user, long accountId) {
        BrokerAccount account = accounts.byId(accountId).filter(a -> user.sees(a.tenantId()))
                .orElseThrow(() -> new IllegalArgumentException("no such account"));       // other tenants: not revealed
        Access.require(user.mayAct(account.tenantId()), "no right to act on this account");
        if (account.brokerType() != BrokerType.ZERODHA) {
            throw new IllegalArgumentException(account.displayName() + " is not a Zerodha account");
        }
        Profile profile = profile(accountId);
        if (profile.externalUserId() == null) {
            throw new IllegalArgumentException(account.displayName() + " has no Zerodha client id: set it first "
                    + "(bin/autotrade kite-profile ... --account " + account.displayName() + " --client-id XX0000)");
        }
        sweepPending();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        pending.put(state, new Pending(accountId, user.subject(), Instant.now()));
        return new KiteAuth(config(secrets.read(profile.apiKeyRef())), null).loginUrl(state);
    }

    /** Kite's redirect: exchange, check, store. Never throws to the browser; the result says what happened. */
    Result callback(String requestToken, String status, String state) {
        Pending p = state == null ? null : pending.remove(state);
        if (p == null || p.created().plus(STATE_LIFETIME).isBefore(Instant.now())) {
            return new Result(false, null, "This login link has expired or was already used. Start the login again.");
        }
        BrokerAccount account = accounts.byId(p.accountId()).orElse(null);
        if (account == null) {
            return new Result(false, null, "The account no longer exists.");
        }
        if (!"success".equals(status) || requestToken == null || requestToken.isBlank()) {
            safeAudit(account, p.subject(), "SESSION_LOGIN_FAILED", Map.of("status", String.valueOf(status)));
            return new Result(false, account.displayName(), "Zerodha did not complete the login (status " + status + ").");
        }
        synchronized (lock(account.id())) {
            Profile profile;
            KiteAuth.Session session;
            double funds;
            Instant expires;
            try {
                profile = profile(account.id());
                if (profile.externalUserId() == null) {
                    return new Result(false, account.displayName(), "No Zerodha client id is pinned for this account; "
                            + "nothing was stored.");
                }
                KiteConfig config = config(secrets.read(profile.apiKeyRef()));
                session = new KiteAuth(config, new KiteHttp(config)).exchange(requestToken, secrets.read(profile.apiSecretRef()));
                if (!profile.externalUserId().equalsIgnoreCase(session.userId())) {
                    safeAudit(account, p.subject(), "SESSION_LOGIN_WRONG_USER",
                            Map.of("expected", profile.externalUserId(), "got", session.userId()));
                    return new Result(false, account.displayName(), "You logged in as " + session.userId() + ", but "
                            + account.displayName() + " is Zerodha client " + profile.externalUserId()
                            + ". Nothing was stored. Log out at Zerodha and log in with the right client id.");
                }
                funds = new KiteSessionCheck(config, session.accessToken()).equityAvailable();
                expires = nextFlush(Instant.now());
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("accountId", account.id());
                file.put("userId", session.userId());
                file.put("accessToken", session.accessToken());
                file.put("loginTime", session.loginTime().toString());
                file.put("expiresAt", expires.toString());
                // the file first: Kite's newest token is the valid one, so it is never left pointing at an older one
                secrets.writeToken(account.id(), JSON.writeValueAsString(file) + "\n");
                tx.executeWithoutResult(t -> {
                    jdbc.update("update acct.broker_session set status = 'EXPIRED' where broker_account_id = ? "
                            + "and status = 'ACTIVE'", account.id());
                    jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, "
                            + "last_validated_at, status) values (?, ?, ?, ?, now(), 'ACTIVE')", account.id(),
                            BrokerSecrets.tokenRef(account.id()), Timestamp.from(session.loginTime()), Timestamp.from(expires));
                    accounts.setConnectionStatus(account.id(), "ACTIVE");
                });
            } catch (RuntimeException e) {
                safeAudit(account, p.subject(), "SESSION_LOGIN_FAILED", Map.of("error", e.getClass().getSimpleName()));
                log.warn("account {}: Kite login failed: {}", account.id(), e.getMessage());   // never holds a token
                return new Result(false, account.displayName(), "The login could not be completed: " + e.getMessage());
            }
            Map<String, Object> detail = new HashMap<>();
            detail.put("brokerUser", session.userId());
            detail.put("equityAvailable", funds);
            detail.put("expiresAt", expires.toString());
            safeAudit(account, p.subject(), "SESSION_LOGIN", detail);
            log.info("account {} ({}): Kite session ACTIVE for {} until {}, equity available {}", account.id(),
                    account.displayName(), session.userId(), expires, Math.round(funds));
            return new Result(true, account.displayName(), "Logged in as Zerodha client " + session.userId()
                    + ". Session valid until " + ZonedDateTime.ofInstant(expires, IST).toLocalDateTime()
                    + " IST. Equity available: Rs " + String.format("%,.0f", funds) + ".");
        }
    }

    /**
     * Sessions past their expiry, account by account: the session EXPIRED; when no ACTIVE session is left (a login may
     * have landed meanwhile) the account TOKEN_EXPIRED (if it was ACTIVE) and its token file deleted. One account's
     * failure is logged and retried next minute; it never stops the others.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    void sweepExpired() {
        List<Map<String, Object>> expired = jdbc.queryForList("select id, broker_account_id from acct.broker_session "
                + "where status = 'ACTIVE' and expires_at <= now() order by id");
        for (Map<String, Object> row : expired) {
            long sessionId = ((Number) row.get("id")).longValue();
            long accountId = ((Number) row.get("broker_account_id")).longValue();
            try {
                synchronized (lock(accountId)) {
                    Boolean noneLeft = tx.execute(t -> {
                        jdbc.update("update acct.broker_session set status = 'EXPIRED' where id = ? and status = 'ACTIVE'",
                                sessionId);
                        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from "
                                + "acct.broker_session where broker_account_id = ? and status = 'ACTIVE')", Boolean.class,
                                accountId));
                        if (!active) {
                            jdbc.update("update acct.broker_account set connection_status = 'TOKEN_EXPIRED', updated_at = now() "
                                    + "where id = ? and connection_status = 'ACTIVE'", accountId);
                        }
                        return !active;
                    });
                    if (Boolean.TRUE.equals(noneLeft)) {
                        secrets.deleteToken(accountId);
                        accounts.byId(accountId).ifPresent(a -> safeAudit(a, "kite-login", "SESSION_EXPIRED", Map.of()));
                        log.info("account {}: Kite session expired", accountId);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("account {}: expiring session {} failed (retried next minute): {}", accountId, sessionId,
                        e.getMessage());
            }
        }
    }

    /** 08:30 IST, weekdays: the login link of every enabled ZERODHA DRY_RUN / LIVE account without a session. */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata")
    void morningLinks() {
        for (Map<String, Object> row : jdbc.queryForList("select a.id, a.display_name from acct.broker_account a "
                + "where a.broker_type = 'ZERODHA' and a.enabled and a.trade_mode in ('DRY_RUN', 'LIVE') "
                + "and a.credential_profile_id is not null and not exists (select 1 from acct.broker_session s "
                + "where s.broker_account_id = a.id and s.status = 'ACTIVE') order by a.id")) {
            long id = ((Number) row.get("id")).longValue();
            String name = (String) row.get("display_name");
            if (props.publicBaseUrl() == null) {
                log.warn("account {} ({}) needs today's Kite login; no public-base-url set for a link", id, name);
                continue;
            }
            telegram("Kite login for " + name + " (today's session): " + props.publicBaseUrl()
                    + "/api/execution/accounts/" + id + "/kite/login");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private Object lock(long accountId) {
        return accountLocks.computeIfAbsent(accountId, id -> new Object());
    }

    /** An audit row; its failure never turns a completed login into a failed one (it is logged). */
    private void safeAudit(BrokerAccount account, String actor, String action, Map<String, ?> detail) {
        try {
            audit.audit(account, actor, action, detail);
        } catch (RuntimeException e) {
            log.warn("account {}: audit {} not written: {}", account.id(), action, e.getMessage());
        }
    }

    private Profile profile(long accountId) {
        List<Profile> rows = jdbc.query("select p.api_key_ref, p.api_secret_ref, a.external_broker_user_id "
                        + "from acct.broker_account a join acct.broker_credential_profile p on p.id = a.credential_profile_id "
                        + "where a.id = ? and p.status = 'ACTIVE' and p.broker_type = 'ZERODHA'",
                (rs, i) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3)), accountId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("account " + accountId + " has no active Zerodha credential profile");
        }
        return rows.getFirst();
    }

    private KiteConfig config(String apiKey) {
        return new KiteConfig(apiKey, URI.create(props.apiBase()), URI.create(props.loginBase()), "MIS",
                Duration.ofSeconds(10), 8, null);
    }

    /** Kite flushes access tokens around 06:00 IST: a session ends at the next 06:00 after the login. */
    static Instant nextFlush(Instant now) {
        ZonedDateTime t = ZonedDateTime.ofInstant(now, IST);
        ZonedDateTime flush = t.toLocalDate().atTime(TOKEN_FLUSH).atZone(IST);
        return (flush.isAfter(t) ? flush : flush.plusDays(1)).toInstant();
    }

    private void sweepPending() {
        Instant cutoff = Instant.now().minus(STATE_LIFETIME);
        pending.values().removeIf(p -> p.created().isBefore(cutoff));
    }

    private void telegram(String text) {
        Map<String, String> settings = new HashMap<>();
        try {
            Path file = Path.of(props.telegramFile());
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file)) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && !line.trim().startsWith("#")) {
                        settings.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
            String token = settings.get("TELEGRAM_BOT_TOKEN"), chat = settings.get("TELEGRAM_CHAT_ID");
            if (token == null || chat == null) {
                log.info("no Telegram settings in {}: {}", file, text);
                return;
            }
            String body = "chat_id=" + URLEncoder.encode(chat, StandardCharsets.UTF_8) + "&text="
                    + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&disable_web_page_preview=true";
            HttpResponse<String> r = telegram.send(HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token
                    + "/sendMessage")).timeout(Duration.ofSeconds(15)).header("Content-Type",
                    "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("Telegram login link not delivered: HTTP {}", r.statusCode());
            }
        } catch (Exception e) {
            log.warn("Telegram login link not delivered: {}", e.getClass().getSimpleName());
        }
    }
}
