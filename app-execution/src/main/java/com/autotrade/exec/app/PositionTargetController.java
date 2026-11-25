package com.autotrade.exec.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.execution.PositionTargets;

/**
 * The trader's manual first target on an open position (user 24 Nov 2026; {@link PositionTargets}): a % above the average
 * entry; once the bid has reached that price, a bid back at or below it sells the whole position. POST only (the UI's
 * proxy forwards GET and POST):
 * <ul>
 *   <li>{@code POST /api/execution/accounts/{id}/positions/{positionId}/target} with {@code {"pct": 20}}: sets or replaces
 *       it (it starts over, waiting for the bid to reach the new price); answers the target with its price;</li>
 *   <li>{@code POST .../target/clear}: removes it.</li>
 * </ul>
 * Who: the workspace's own people who may act on the account (OWNER, ADMIN, TRADER); and (user 25 Nov 2026, the exit desk:
 * "Members too with reason") SUPPORT or the platform admin from outside, as an exit: with a reason ({@code reason}, required
 * then), audited by_admin with the role and the member's consent, the members told (Telegram, push), the target marked as the
 * admin's (V55); never on a mirror (it holds no positions of its own). Only a position of the account's own book (PAPER for a paper account, LIVE for a
 * broker account). A bad % is a 400, a refusal a 409 with the reason; the live book shows each open position's target.
 */
@RestController
class PositionTargetController {

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final PositionTargets targets;

    PositionTargetController(Access access, DataSource dataSource) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.targets = new PositionTargets(dataSource);
    }

    /** {@code reason}: required when the caller is not of the workspace (SUPPORT, the platform admin). */
    record TargetBody(Double pct, String reason) {

        TargetBody(Double pct) {
            this(pct, null);
        }
    }

    record ClearBody(String reason) {
    }

    /** The members' message and the admin's audit detail (null in tests that never act as the admin). */
    private AdminExits adminExits;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void adminExits(AdminExits adminExits) {
        this.adminExits = adminExits;
    }

    /** Tests: the admin exits' helper (a recording one). */
    PositionTargetController withAdminExits(AdminExits adminExits) {
        this.adminExits = adminExits;
        return this;
    }

    @PostMapping("/api/execution/accounts/{id}/positions/{positionId}/target")
    Map<String, Object> set(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                            @PathVariable long positionId, @RequestBody TargetBody body) {
        Access.User user = access.user(subject);
        BrokerAccount a = actable(user, id);
        Map<String, Object> admin = admin(user, a, body == null ? null : body.reason());
        if (body == null || body.pct() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pct is required");
        }
        if (PositionTargets.pct(body.pct()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pct: more than 0 and at most "
                    + (int) PositionTargets.MAX_PCT + " (two decimals)");
        }
        try {
            java.util.Optional<PositionTargets.View> before = targets.view(positionId);
            PositionTargets.View v = targets.set(a, positionId, body.pct(), user.subject(), admin);
            // review 25 Nov 2026: the members hear of a change only (the same % again by the admin tells nobody twice)
            if (admin != null && adminExits != null && PositionTargets.changed(before, java.util.Optional.of(v))) {
                adminExits.targetSet(a, user.subject(), (String) admin.get("admin_reason"), List.of(line(v)));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("account", a.displayName());
            out.put("positionId", positionId);
            out.put("target", v.toMap());
            return out;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/api/execution/accounts/{id}/positions/{positionId}/target/clear")
    Map<String, Object> clear(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                              @PathVariable long positionId, @RequestBody(required = false) ClearBody body) {
        Access.User user = access.user(subject);
        BrokerAccount a = actable(user, id);
        Map<String, Object> admin = admin(user, a, body == null ? null : body.reason());
        try {
            boolean cleared = targets.clear(a, positionId, user.subject(), admin);
            if (cleared && admin != null && adminExits != null) {
                adminExits.targetSet(a, user.subject(), (String) admin.get("admin_reason"), List.of(symbol(positionId)
                        + ": first target removed"));
            }
            return Map.of("account", a.displayName(), "positionId", positionId, "cleared", cleared);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private BrokerAccount actable(Access.User user, long id) {
        // the platform admin reaches every tenant's accounts (as the exit desk does); anyone else only their own tenants'
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()) || user.admin())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + id));
        // review 24 Nov 2026: a target is a sale later, so it follows the admin exit desk's rule; user 25 Nov 2026: someone
        // other than the workspace's own people (SUPPORT, the platform admin) may set it as they may exit, with a reason
        Access.require(user.byAdmin(a.tenantId()) ? user.mayExit(a.tenantId()) : user.mayAct(a.tenantId()),
                "read-only role for this account");
        String mirror = Mirrors.of(jdbc, a.id());
        if (mirror != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, a.displayName() + " is a mirror of " + mirror
                    + ": it holds no positions of its own; set the target at the source (" + mirror + ")");
        }
        return a;
    }

    /**
     * The admin's audit detail when the caller is not of the account's workspace (a reason is then required: 400), null
     * for the workspace's own people.
     */
    private Map<String, Object> admin(Access.User user, BrokerAccount a, String reason) {
        if (!user.byAdmin(a.tenantId())) {
            return null;
        }
        String r = AdminExits.requireReason(reason);
        Map<String, Object> out = adminExits != null ? new LinkedHashMap<>(adminExits.audit(user, a, r))
                : new LinkedHashMap<>(Map.of("by_admin", true, "admin_reason", r));
        out.put("by_admin", true);
        out.put("admin_reason", r);
        return out;
    }

    /** "NIFTY 25450 CE: first target +20 % = 145.20" (the members' message). */
    String line(PositionTargets.View v) {
        return String.format(java.util.Locale.ROOT, "%s: first target +%s %% = %.2f (if the bid reaches it and falls back "
                + "to it, the whole position is sold)", symbol(v.positionId()), PositionTargets.pctText(v.pct()), v.price());
    }

    private String symbol(long positionId) {
        return jdbc.queryForList("select symbol from exec.position where id = ?", String.class, positionId).stream()
                .findFirst().orElse("position " + positionId);
    }
}
