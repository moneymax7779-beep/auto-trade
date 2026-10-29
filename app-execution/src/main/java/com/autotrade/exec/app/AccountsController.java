package com.autotrade.exec.app;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.execution.ExecutionLoop;
import com.autotrade.execution.KillSwitches;

/**
 * The multi-user execution API (live plan sections 2a and 4), every call scoped to the caller's tenants:
 * <ul>
 *   <li>{@code GET /api/execution/accounts}: the caller's accounts with this worker's last turn of each;</li>
 *   <li>{@code GET /api/execution/kill-switches}, {@code POST} (engage), {@code POST .../{id}/release};</li>
 *   <li>{@code POST /api/execution/accounts/{id}/flatten} with {@code {"confirm": "FLATTEN", "reason": ...}} and
 *       {@code POST /api/execution/flatten-all} with {@code {"confirm": "FLATTEN ALL"}}: kill switch, then every open
 *       position exited (broker stops off first);</li>
 *   <li>{@code GET /api/execution/audit?account=}: the audit log.</li>
 * </ul>
 */
@RestController
class AccountsController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Access access;
    private final AccountStore accounts;
    private final KillSwitches kills;
    private final ExecutionService service;
    private final JdbcTemplate jdbc;

    AccountsController(Access access, DataSource dataSource, ExecutionService service) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.kills = new KillSwitches(dataSource);
        this.service = service;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    record AccountView(long id, long tenantId, String name, String broker, String mode, String role, boolean enabled,
                       double allocatedCapital, String riskFile, String connection, ExecutionLoop.AccountStatus lastTurn) {
    }

    @GetMapping("/api/execution/accounts")
    List<AccountView> accounts(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Map<Long, ExecutionLoop.AccountStatus> turns = new LinkedHashMap<>();
        service.status().forEach(s -> turns.put(s.accountId(), s));
        List<AccountView> out = new ArrayList<>();
        for (long tenant : user.tenants()) {
            Map<Long, String> connections = accounts.connectionStatuses(tenant);
            for (BrokerAccount a : accounts.ofTenant(tenant)) {
                out.add(new AccountView(a.id(), a.tenantId(), a.displayName(), a.brokerType().name(), a.tradeMode().name(),
                        a.role().name(), a.enabled(), a.allocatedCapital(), a.riskFile(), connections.get(a.id()),
                        turns.get(a.id())));
            }
        }
        return out;
    }

    record Session(String status) {
    }

    /**
     * The account's broker session after the day's login (ACTIVE: entries resume, waiting exits go out, stops are
     * verified) or taken down (DISCONNECTED). The Kite request-token exchange itself is L3 (Secret Manager); until then
     * the owner logs in and marks the session here.
     */
    @PostMapping("/api/execution/accounts/{id}/session")
    Map<String, Object> session(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                @PathVariable long id, @RequestBody Session body) {
        Access.User user = access.user(subject);
        BrokerAccount a = own(user, id);
        String status = body.status() == null ? "" : body.status();
        Access.require(status.equals("ACTIVE") || status.equals("DISCONNECTED"), "status: ACTIVE or DISCONNECTED");
        Integer linked = jdbc.queryForObject("select count(*) from acct.broker_account where id = ? and credential_profile_id "
                + "is not null", Integer.class, a.id());
        Access.require(!"ACTIVE".equals(status) || linked == null || linked == 0,
                a.displayName() + " is linked to a Kite app: log in with Kite (its session is set by the login, not by hand)");
        accounts.setConnectionStatus(a.id(), status);
        kills.audit(a, user.subject(), "SESSION_" + status, Map.of());
        return Map.of("account", a.displayName(), "connection", status);
    }

    @GetMapping("/api/execution/kill-switches")
    List<KillSwitches.Switch> switches(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        if (user.admin()) {
            return kills.list(null);
        }
        return kills.list(null).stream().filter(s -> s.tenantId() == null ? s.scope().equals("GLOBAL_LIVE")
                || s.scope().equals("INSTRUMENT") : user.sees(s.tenantId())).toList();
    }

    /**
     * For STRATEGY and INSTRUMENT: {@code tenantId}, which tenant's accounts (needed when the caller may act for several),
     * or {@code allTenants}, one switch for each tenant they may act for (the owner stopping a strategy for the family).
     */
    record Engage(String scope, Long accountId, String strategyId, String instrumentKey, String reason, Long tenantId,
                  Boolean allTenants) {
    }

    @PostMapping("/api/execution/kill-switches")
    Map<String, Object> engage(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @RequestBody Engage e) {
        Access.User user = access.user(subject);
        if (e.reason() == null || e.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a reason is required");
        }
        String scope = e.scope() == null ? "" : e.scope();
        if (Boolean.TRUE.equals(e.allTenants()) && (scope.equals("STRATEGY") || scope.equals("INSTRUMENT"))) {
            List<Long> tenants = user.tenants().stream().filter(user::mayAct).toList();
            Access.require(!tenants.isEmpty(), "no tenant you may act for");
            List<Long> ids = new ArrayList<>();
            for (long tenant : tenants) {
                ids.add(kills.engage(tenant, scope, null, scope.equals("STRATEGY") ? e.strategyId() : null,
                        scope.equals("INSTRUMENT") ? e.instrumentKey() : null, e.reason(), user.subject()));
            }
            return Map.of("id", ids.getFirst(), "ids", ids);
        }
        long id = switch (scope) {
            case "GLOBAL_LIVE" -> {
                Access.require(user.admin(), "GLOBAL_LIVE needs ADMIN");
                yield kills.engage(null, scope, null, null, null, e.reason(), user.subject());
            }
            case "ACCOUNT" -> {
                BrokerAccount a = own(user, e.accountId());
                yield kills.engage(a.tenantId(), scope, a.id(), null, null, e.reason(), user.subject());
            }
            case "STRATEGY" -> kills.engage(actingTenant(user, e.tenantId()), scope, null, e.strategyId(), null, e.reason(),
                    user.subject());
            case "INSTRUMENT" -> kills.engage(actingTenant(user, e.tenantId()), scope, null, null, e.instrumentKey(), e.reason(),
                    user.subject());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope: GLOBAL_LIVE, ACCOUNT, STRATEGY "
                    + "or INSTRUMENT");
        };
        return Map.of("id", id);
    }

    @PostMapping("/api/execution/kill-switches/{id}/release")
    Map<String, Object> release(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                @PathVariable long id) {
        Access.User user = access.user(subject);
        KillSwitches.Switch s = kills.list(null).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no engaged switch " + id));
        Access.require(s.tenantId() == null ? user.admin() : user.mayAct(s.tenantId()), "not yours to release");
        return Map.of("released", kills.release(id, s.tenantId(), user.subject()));
    }

    record Flatten(String confirm, String reason) {
    }

    @PostMapping("/api/execution/accounts/{id}/flatten")
    Map<String, Object> flatten(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                @PathVariable long id, @RequestBody Flatten f) throws Exception {
        Access.User user = access.user(subject);
        Access.require("FLATTEN".equals(f.confirm()), "confirm with \"FLATTEN\"");
        BrokerAccount a = own(user, id);
        return Map.of("account", a.displayName(), "result", service.flatten(a.id(), user.subject(),
                f.reason() == null ? "manual" : f.reason(), LocalDate.now(IST)));
    }

    @PostMapping("/api/execution/flatten-all")
    Map<String, Object> flattenAll(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @RequestBody Flatten f) throws Exception {
        Access.User user = access.user(subject);
        Access.require("FLATTEN ALL".equals(f.confirm()), "confirm with \"FLATTEN ALL\"");
        Map<String, Object> out = new LinkedHashMap<>();
        for (long tenant : user.tenants()) {
            if (!user.mayAct(tenant)) {
                continue;
            }
            for (BrokerAccount a : accounts.ofTenant(tenant)) {
                if (a.enabled() && a.role().name().equals("FOLLOWER_TRADE")) {
                    out.put(a.displayName(), service.flatten(a.id(), user.subject(), f.reason() == null ? "flatten all"
                            : f.reason(), LocalDate.now(IST)));
                }
            }
        }
        return out;
    }

    @GetMapping("/api/execution/audit")
    List<Map<String, Object>> audit(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                    @RequestParam(value = "account", required = false) Long accountId) {
        Access.User user = access.user(subject);
        if (accountId != null) {
            own(user, accountId);
        }
        return jdbc.queryForList("select id, tenant_id, broker_account_id, actor, action, detail::text detail, at "
                        + "from exec.audit_log where tenant_id = any(?) and (?::bigint is null or broker_account_id = ?) "
                        + "order by id desc limit 200", user.tenants().toArray(new Long[0]), accountId, accountId);
    }

    /** An account the caller may act on (404 when it is another tenant's: its existence is not revealed). */
    private BrokerAccount own(Access.User user, Long accountId) {
        if (accountId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
        BrokerAccount a = accounts.byId(accountId).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + accountId));
        Access.require(user.mayAct(a.tenantId()), "read-only role for this account");
        return a;
    }

    /**
     * The tenant a STRATEGY or INSTRUMENT switch is for: the one asked for (the caller must be able to act for it), else
     * the caller's only such tenant (29 Oct 2026: an owner who is ADMIN of family members' tenants must say whose).
     */
    private static long actingTenant(Access.User user, Long requested) {
        if (requested != null) {
            Access.require(user.sees(requested) && user.mayAct(requested), "no right to act for tenant " + requested);
            return requested;
        }
        List<Long> acting = user.tenants().stream().filter(user::mayAct).toList();
        Access.require(!acting.isEmpty(), "no tenant you may act for");
        if (acting.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "you act for several tenants: give tenantId");
        }
        return acting.getFirst();
    }
}
