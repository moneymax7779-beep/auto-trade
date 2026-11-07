package com.autotrade.exec.app;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.KillSwitches;
import com.autotrade.execution.TradingDay;

/**
 * What every broker's daily login ends with (trader workspaces, 7 Nov 2026; the Kite login keeps its own, older copy of
 * the same steps): the account's broker app and pinned client id, the session's token file (owner-only, written first),
 * the ACTIVE acct.broker_session row, the account's connection, the broker app's last check, and the audit; and a
 * logout. The token is never logged or audited.
 */
@Component
class SessionActivation {

    private static final Logger log = LoggerFactory.getLogger(SessionActivation.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** An account's broker app and what its login needs (references, never values). */
    record App(long profileId, BrokerType broker, String keyRef, String secretRef, String redirectUri,
               Map<String, String> settings, String clientId) {
    }

    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KillSwitches audit;
    private final TradingDay tradingDay;
    final BrokerSecrets secrets;

    SessionActivation(DataSource dataSource, TransactionTemplate tx, KiteLoginProperties kite, BrokerLoginProperties brokers) {
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.audit = new KillSwitches(dataSource);
        this.tradingDay = new TradingDay(dataSource);
        this.secrets = new BrokerSecrets(Path.of(kite.secretsDir()), Path.of(kite.sessionsDir()),
                Path.of(brokers.storeDir()));
    }

    /** An account the caller may log in (their own workspace, or SUPPORT with a grant), of this broker. */
    BrokerAccount account(Access.User user, long accountId, BrokerType broker) {
        BrokerAccount a = accounts.byId(accountId).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new IllegalArgumentException("no such account"));
        Access.require(user.mayAct(a.tenantId()), user.support(a.tenantId()) ? "SUPPORT needs the member's grant to log in"
                : "no right to act on this account");
        if (a.brokerType() != broker) {
            throw new IllegalArgumentException(a.displayName() + " is not a " + broker + " account");
        }
        return a;
    }

    /** The account's active broker app of its broker, with its client id; refused with what is missing. */
    App app(BrokerAccount account) {
        List<App> rows = jdbc.query("select p.id, p.broker_type, p.api_key_ref, p.api_secret_ref, p.redirect_uri, "
                        + "p.settings::text settings, a.external_broker_user_id from acct.broker_account a "
                        + "join acct.broker_credential_profile p on p.id = a.credential_profile_id where a.id = ? "
                        + "and p.status = 'ACTIVE' and p.broker_type = a.broker_type", (rs, i) -> {
                    Map<String, String> settings = new LinkedHashMap<>();
                    BrokerHttp.JSON.readTree(rs.getString("settings")).properties()
                            .forEach(e -> settings.put(e.getKey(), e.getValue().asString("")));
                    return new App(rs.getLong("id"), BrokerType.valueOf(rs.getString("broker_type")),
                            rs.getString("api_key_ref"), rs.getString("api_secret_ref"), rs.getString("redirect_uri"),
                            settings, rs.getString("external_broker_user_id"));
                }, account.id());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(account.displayName() + " has no active broker app: add one on the Brokers "
                    + "page and link it");
        }
        App app = rows.getFirst();
        if (app.clientId() == null) {
            throw new IllegalArgumentException(account.displayName() + " has no client id: set it first (the login must "
                    + "come from that client)");
        }
        return app;
    }

    /**
     * The login worked: the token file first (the newest token is the valid one), then in one transaction the old ACTIVE
     * session expired, the new one ACTIVE, the account's connection ACTIVE and the broker app checked; then the audit.
     */
    void activate(BrokerAccount account, App app, String tokenFile, String actor, Map<String, Object> file,
                  Instant acquired, Instant expires, Map<String, Object> detail) {
        Map<String, Object> content = new LinkedHashMap<>(file);
        content.put("accountId", account.id());
        content.put("expiresAt", expires.toString());
        secrets.writeToken(tokenFile, account.id(), BrokerHttp.JSON.writeValueAsString(content) + "\n");
        tx.executeWithoutResult(t -> {
            jdbc.update("update acct.broker_session set status = 'EXPIRED' where broker_account_id = ? and status = 'ACTIVE'",
                    account.id());
            jdbc.update("insert into acct.broker_session (broker_account_id, token_ref, acquired_at, expires_at, "
                    + "last_validated_at, status) values (?, ?, ?, ?, now(), 'ACTIVE')", account.id(),
                    BrokerSecrets.tokenRef(tokenFile, account.id()), Timestamp.from(acquired), Timestamp.from(expires));
            accounts.setConnectionStatus(account.id(), "ACTIVE");
            jdbc.update("update acct.broker_credential_profile set verified_at = now(), last_check_error = null, "
                    + "updated_at = now() where id = ?", app.profileId());
        });
        Map<String, Object> audited = new LinkedHashMap<>(detail);
        audited.put("expiresAt", expires.toString());
        safeAudit(account, actor, "SESSION_LOGIN", audited);
        log.info("account {} ({}): {} session ACTIVE until {}", account.id(), account.displayName(), app.broker(), expires);
    }

    /** The login failed: audited, and the broker app's last check says why (never a secret). */
    void failed(BrokerAccount account, App app, String actor, String reason) {
        if (app != null) {
            jdbc.update("update acct.broker_credential_profile set last_check_error = ?, updated_at = now() where id = ?",
                    reason.length() > 300 ? reason.substring(0, 300) : reason, app.profileId());
        }
        safeAudit(account, actor, "SESSION_LOGIN_FAILED", Map.of("reason", reason));
    }

    /**
     * Logs the account out of its broker here: sessions REVOKED, the token file deleted, the connection DISCONNECTED; a
     * LIVE day on it goes back to PAPER. The broker's own session is not touched.
     */
    void logout(BrokerAccount account, String actor) {
        tx.executeWithoutResult(t -> {
            jdbc.update("update acct.broker_session set status = 'REVOKED' where broker_account_id = ? and status = 'ACTIVE'",
                    account.id());
            accounts.setConnectionStatus(account.id(), "DISCONNECTED");
        });
        secrets.deleteToken(account.id());
        tradingDay.sessionLost(account, actor);
        safeAudit(account, actor, "SESSION_LOGOUT", Map.of());
    }

    /** The next {@code at} IST after {@code now} (Kite's 06:00 flush, Upstox's 03:30). */
    static Instant next(Instant now, LocalTime at) {
        ZonedDateTime t = ZonedDateTime.ofInstant(now, IST);
        ZonedDateTime cut = t.toLocalDate().atTime(at).atZone(IST);
        return (cut.isAfter(t) ? cut : cut.plusDays(1)).toInstant();
    }

    private void safeAudit(BrokerAccount account, String actor, String action, Map<String, ?> detail) {
        try {
            audit.audit(account, actor, action, detail);
        } catch (RuntimeException e) {
            log.warn("account {}: audit {} not written: {}", account.id(), action, e.getMessage());
        }
    }
}
