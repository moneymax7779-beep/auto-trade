package com.autotrade.exec.app;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.execution.KillSwitches;

/**
 * Support access to a member's workspace (trader workspaces, 7 Nov 2026, plan 2.3; V29 acct.support_grant). The platform
 * admin is SUPPORT in every family workspace: they see it and may always stop trouble (kill, flatten, exit); to run it
 * (capital, strategies, broker apps, the LIVE switch) they need the member's grant:
 * <ul>
 *   <li>{@code GET /api/execution/workspaces/{tenant}/support}: active and recent grants;</li>
 *   <li>{@code POST .../support} {@code {"hours": h}}: the member (OWNER) lets SUPPORT manage for 1-168 hours;</li>
 *   <li>{@code POST .../support/{id}/revoke}: the member, or SUPPORT itself, ends it early;</li>
 *   <li>{@code POST .../support/break-glass} {@code {"reason": ...}}: SUPPORT opens one hour without the member, with a
 *       reason; the member is told on Telegram at once.</li>
 * </ul>
 * Every grant, revoke and break-glass is audited in the member's workspace; nothing is ever deleted.
 */
@RestController
class SupportController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm");
    static final int MAX_HOURS = 168;

    private final Access access;
    private final JdbcTemplate jdbc;
    private final KillSwitches audit;
    private final TelegramNotifier telegram;

    SupportController(Access access, DataSource dataSource, TelegramNotifier telegram) {
        this.access = access;
        this.jdbc = new JdbcTemplate(dataSource);
        this.audit = new KillSwitches(dataSource);
        this.telegram = telegram;
    }

    @GetMapping("/api/execution/workspaces/{tenant}/support")
    List<Map<String, Object>> grants(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                     @PathVariable long tenant) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        return jdbc.queryForList("select g.id, u.external_subject granted_to, b.external_subject granted_by, g.reason, "
                + "g.break_glass, " + Iso.of("g.granted_at") + " granted_at, " + Iso.of("g.expires_at") + " expires_at, "
                + Iso.of("g.revoked_at") + " revoked_at, "
                + "g.revoked_at is null and g.expires_at > now() active from acct.support_grant g join acct.app_user u "
                + "on u.id = g.granted_to join acct.app_user b on b.id = g.granted_by where g.tenant_id = ? "
                + "order by g.id desc limit 20", tenant);
    }

    record Grant(Integer hours) {
    }

    @PostMapping("/api/execution/workspaces/{tenant}/support")
    Map<String, Object> grant(@RequestHeader(value = Access.HEADER, required = false) String subject,
                              @PathVariable long tenant, @RequestBody Grant body) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        Access.require("OWNER".equals(user.roles().get(tenant)), "only the workspace's OWNER grants support access");
        require(body.hours() != null && body.hours() >= 1 && body.hours() <= MAX_HOURS, "hours: 1 to " + MAX_HOURS);
        List<Long> supporters = jdbc.queryForList("select m.app_user_id from acct.tenant_membership m join acct.app_user u "
                + "on u.id = m.app_user_id where m.tenant_id = ? and m.role = 'SUPPORT' and u.status = 'ACTIVE'", Long.class,
                tenant);
        require(!supporters.isEmpty(), "this workspace has no SUPPORT member to grant access to");
        Instant until = Instant.now().plusSeconds(body.hours() * 3600L);
        for (long to : supporters) {
            jdbc.update("insert into acct.support_grant (tenant_id, granted_to, granted_by, expires_at) values (?, ?, ?, ?)",
                    tenant, to, user.id(), java.sql.Timestamp.from(until));
        }
        audit.audit(tenant, null, user.subject(), "SUPPORT_GRANTED", Map.of("hours", body.hours(), "until", until.toString()));
        telegram.send(tenantName(tenant) + ": " + user.subject() + " gave you support access until "
                + WHEN.format(until.atZone(IST)) + " IST.");
        return Map.of("granted", supporters.size(), "until", until.toString());
    }

    @PostMapping("/api/execution/workspaces/{tenant}/support/{id}/revoke")
    Map<String, Object> revoke(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable long tenant, @PathVariable long id) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        Map<String, Object> g = jdbc.queryForList("select granted_to from acct.support_grant where id = ? and tenant_id = ? "
                + "and revoked_at is null", id, tenant).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no open grant " + id));
        long to = ((Number) g.get("granted_to")).longValue();
        Access.require("OWNER".equals(user.roles().get(tenant)) || to == user.id(),
                "only the workspace's OWNER, or the one it was granted to, ends a grant");
        jdbc.update("update acct.support_grant set revoked_at = now(), revoked_by = ? where id = ? and revoked_at is null",
                user.id(), id);
        audit.audit(tenant, null, user.subject(), "SUPPORT_REVOKED", Map.of("grant", id));
        return Map.of("revoked", id);
    }

    record BreakGlass(String reason) {
    }

    @PostMapping("/api/execution/workspaces/{tenant}/support/break-glass")
    Map<String, Object> breakGlass(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @PathVariable long tenant, @RequestBody BreakGlass body) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        Access.require(user.support(tenant), "break-glass is SUPPORT's (the member has every right already)");
        String reason = body.reason() == null ? "" : body.reason().trim();
        require(reason.length() >= 5 && reason.length() <= 300, "a reason of 5-300 characters (the member reads it)");
        Instant until = Instant.now().plusSeconds(3600);
        long id = jdbc.queryForObject("insert into acct.support_grant (tenant_id, granted_to, granted_by, reason, break_glass, "
                + "expires_at) values (?, ?, ?, ?, true, ?) returning id", Long.class, tenant, user.id(), user.id(), reason,
                java.sql.Timestamp.from(until));
        audit.audit(tenant, null, user.subject(), "SUPPORT_BREAK_GLASS", Map.of("grant", id, "reason", reason,
                "until", until.toString()));
        int told = telegram.sendToMembers(tenant, "auto-trade: " + user.subject() + " opened emergency access to your "
                + "workspace until " + WHEN.format(until.atZone(IST)) + " IST. Reason: " + reason
                + ". You can end it on your Settings page.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grant", id);
        out.put("until", until.toString());
        out.put("memberTold", told > 0);
        return out;
    }

    private static void seen(Access.User user, long tenant) {
        if (!user.sees(tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + tenant);
        }
    }

    private String tenantName(long tenant) {
        return jdbc.queryForObject("select name from acct.tenant where id = ?", String.class, tenant);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
