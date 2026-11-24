package com.autotrade.exec.app;

import java.util.LinkedHashMap;
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
 * Who: the workspace's own people who may act on the account (OWNER, ADMIN, TRADER); never SUPPORT or the platform admin
 * from outside (a target is a later sale: they use Exit, which takes a reason and tells the members); never on a mirror
 * (it holds no positions of its own). Only a position of the account's own book (PAPER for a paper account, LIVE for a
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

    record TargetBody(Double pct) {
    }

    @PostMapping("/api/execution/accounts/{id}/positions/{positionId}/target")
    Map<String, Object> set(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                            @PathVariable long positionId, @RequestBody TargetBody body) {
        Access.User user = access.user(subject);
        BrokerAccount a = actable(user, id);
        if (body == null || body.pct() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pct is required");
        }
        if (PositionTargets.pct(body.pct()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pct: more than 0 and at most "
                    + (int) PositionTargets.MAX_PCT + " (two decimals)");
        }
        try {
            PositionTargets.View v = targets.set(a, positionId, body.pct(), user.subject());
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
                              @PathVariable long positionId) {
        Access.User user = access.user(subject);
        BrokerAccount a = actable(user, id);
        try {
            return Map.of("account", a.displayName(), "positionId", positionId, "cleared",
                    targets.clear(a, positionId, user.subject()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private BrokerAccount actable(Access.User user, long id) {
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + id));
        Access.require(user.mayAct(a.tenantId()), user.support(a.tenantId())
                ? "SUPPORT needs the member's grant for this" : "read-only role for this account");
        // review 24 Nov 2026: a target is a sale later, so it follows the admin exit desk's rule: someone other than the
        // workspace's own people (SUPPORT, the platform admin) exits with a reason the members are told, now, by Exit
        Access.require(!user.byAdmin(a.tenantId()), "a target is set by the workspace's own people; "
                + "SUPPORT and the platform admin use Exit (with a reason)");
        String mirror = Mirrors.of(jdbc, a.id());
        if (mirror != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, a.displayName() + " is a mirror of " + mirror
                    + ": it holds no positions of its own; set the target at the source (" + mirror + ")");
        }
        return a;
    }
}
