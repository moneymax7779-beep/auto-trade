package com.autotrade.exec.app;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.BrokerAccount;

/**
 * What an exit by the platform admin on someone else's account carries (admin exit desk, user 20 Nov 2026, decisions 3
 * and 5):
 * <ul>
 *   <li>a reason, required ({@link #requireReason});</li>
 *   <li>the audit detail {@code by_admin: true}, the reason, the actor's role and the member's standing consent to admin
 *       exits (V51, given on their Settings page; it never blocks an exit, which reduces risk) ({@link #audit});</li>
 *   <li>the members told: Telegram to their linked chats and web push to their phones, through the same per-member path
 *       as every other alert ({@link TelegramNotifier#sendToMembers}, which pushes too); the in-app banner reads the
 *       audit rows ({@code GET /api/execution/notices}).</li>
 * </ul>
 */
@Component
class AdminExits {

    private static final Logger log = LoggerFactory.getLogger(AdminExits.class);
    /** The longest reason kept (it goes into the audit, a Telegram message and the member's banner). */
    static final int MAX_REASON = 300;

    private final JdbcTemplate jdbc;
    private final TelegramNotifier telegram;

    /** {@code telegram} null (tests): nobody is told, the exit is still audited. */
    AdminExits(DataSource dataSource, TelegramNotifier telegram) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.telegram = telegram;
    }

    /** The reason, trimmed; 400 when there is none (an admin exit always says why). */
    static String requireReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a reason is required: you are not this account's "
                    + "owner, and the member is told why their position was exited");
        }
        return r.length() > MAX_REASON ? r.substring(0, MAX_REASON) : r;
    }

    /** The workspace's standing consent to admin exits: given (true/false), when and by whom. */
    Map<String, Object> consent(long tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> row = jdbc.queryForList("select t.admin_exit_consent_at at, u.external_subject by from acct.tenant t "
                + "left join acct.app_user u on u.id = t.admin_exit_consent_by where t.id = ?", tenantId).stream().findFirst()
                .orElse(Map.of());
        Timestamp at = (Timestamp) row.get("at");
        out.put("given", at != null);
        out.put("at", at == null ? null : at.toInstant().toString());
        out.put("by", at == null ? null : row.get("by"));
        return out;
    }

    /** The consent as one audit line ("given 2026-11-20T04:30:00Z by kc-x" or "not given"). */
    String consentText(long tenantId) {
        Map<String, Object> c = consent(tenantId);
        return Boolean.TRUE.equals(c.get("given")) ? "given " + c.get("at") + " by " + c.get("by") : "not given";
    }

    /** The audit detail of an exit by {@code user} on {@code account}: by_admin, the reason, role and consent. */
    Map<String, Object> audit(Access.User user, BrokerAccount account, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean byAdmin = user.byAdmin(account.tenantId());
        out.put("by_admin", byAdmin);
        if (reason != null && !reason.isBlank()) {
            out.put("admin_reason", reason);
        }
        if (byAdmin) {
            String role = user.roles().get(account.tenantId());
            out.put("actor_role", role == null ? "PLATFORM_ADMIN" : role);
            try {
                out.put("consent", consentText(account.tenantId()));
            } catch (RuntimeException e) {
                // second review 20 Nov 2026: the consent never blocks an exit, not even when it cannot be read (the
                // per-position Exit, a flatten and flatten-all called this unguarded; only the batch caught it)
                log.warn("admin exit on {}: consent not read: {}", account.displayName(), e.getMessage());
                out.put("consent", "unknown (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /** What an admin message adds when the action also stopped the account's new entries. */
    static final String STOPPED = "New entries are stopped on this account (kill switch): release it on the Accounts page "
            + "once it is understood.";

    /**
     * The lines of an admin's exit or flatten worth telling the members (second review 20 Nov 2026): what was sold, part
     * sold or may have been (an order whose outcome is not known, a sale still queued); never "position 7 is not open",
     * a refused paper exit or a route refusal, which sold nothing ("the admin exited" would be false). {@code stopped}:
     * the action engaged the account's kill switch, which is said too (the kill's own alert goes to the admin only).
     * Empty: nothing to tell.
     */
    static List<String> news(List<String> lines, boolean stopped) {
        List<String> out = new java.util.ArrayList<>(lines.stream().filter(AdminExits::acted).toList());
        if (stopped) {
            out.add(STOPPED);
        }
        return out;
    }

    /** A line of a sale, part sale, or a sell whose outcome is not known (FollowerSimulator / ExecutionLoop wording). */
    static boolean acted(String line) {
        String l = line.toLowerCase(java.util.Locale.ROOT);
        return l.matches(".*: [1-9]\\d* (of \\d+ )?sold.*") || l.contains("unknown outcome")
                || com.autotrade.execution.ExitOutcome.uncleanShortSale(line) || l.contains("queued");
    }

    /**
     * Tells the account's members that the admin exited (Telegram to each linked chat, web push to their phones). A failed
     * send is logged by the notifier, never thrown: the exit is done whatever the message does.
     */
    void exited(BrokerAccount account, String actor, String reason, List<String> lines) {
        String text = message(account, reason, lines);
        try {
            int sent = telegram == null ? 0 : telegram.sendToMembers(account.tenantId(), text);
            log.info("admin exit on {} by {}: members told ({} Telegram chats): {}", account.displayName(), actor, sent,
                    text.replace('\n', ' '));
        } catch (RuntimeException e) {
            log.warn("admin exit on {}: members not told: {}", account.displayName(), e.getMessage());
        }
    }

    /**
     * Tells the account's members that the admin set or removed a first target on their positions (user 25 Nov 2026, the
     * exit desk); as {@link #exited}, a failed send is logged, never thrown.
     */
    void targetSet(BrokerAccount account, String actor, String reason, List<String> lines) {
        String text = "Admin target on " + account.displayName() + " (" + account.tradeMode() + "):\n" + String.join("\n", lines)
                + "\nReason: " + reason;
        try {
            int sent = telegram == null ? 0 : telegram.sendToMembers(account.tenantId(), text);
            log.info("admin target on {} by {}: members told ({} Telegram chats): {}", account.displayName(), actor, sent,
                    text.replace('\n', ' '));
        } catch (RuntimeException e) {
            log.warn("admin target on {}: members not told: {}", account.displayName(), e.getMessage());
        }
    }

    /** "Admin exited positions on ZERODHA-1 (LIVE): NIFTY 25450 CE: 130 sold at 131.20. Reason: ..." */
    static String message(BrokerAccount account, String reason, List<String> lines) {
        return "Admin exited positions on " + account.displayName() + " (" + account.tradeMode() + "):\n"
                + String.join("\n", lines) + "\nReason: " + reason;
    }
}
