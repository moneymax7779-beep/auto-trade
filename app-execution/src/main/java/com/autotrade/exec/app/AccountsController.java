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
 * Stopping trouble (engage a kill switch, flatten, exit one position) is open to SUPPORT in a member's workspace without
 * a grant ({@link Access.User#emergency}); releasing a switch, booking a hand exit and setting a session are not.
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

    /**
     * One account. {@code paperOf}: the real account this one is the paper twin of (null otherwise), so the page can
     * show a twin under its account. {@code today}: the account's own book for today (IST), see {@link DayBook}
     * (Accounts page layout, user 9 Nov 2026).
     */
    record AccountView(long id, long tenantId, String name, String broker, String mode, String role, boolean enabled,
                       double allocatedCapital, String riskFile, String connection, ExecutionLoop.AccountStatus lastTurn,
                       Long paperOf, DayBook today) {
    }

    /**
     * Positions closed today (net after charges, count) and open now; the {@code live*} figures are the part entered
     * with a LIVE intent (real money). Split by the intent, not by the account's mode now: an account switched back
     * to PAPER after a LIVE morning still shows that morning as LIVE (code review 9 Nov 2026).
     */
    record DayBook(double net, int trades, int open, double liveNet, int liveTrades, int liveOpen) {
        static final DayBook NONE = new DayBook(0, 0, 0, 0, 0, 0);
    }
    @GetMapping("/api/execution/accounts")
    List<AccountView> accounts(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Map<Long, ExecutionLoop.AccountStatus> turns = new LinkedHashMap<>();
        service.status().forEach(s -> turns.put(s.accountId(), s));
        List<AccountView> out = new ArrayList<>();
        LocalDate today = LocalDate.now(IST);
        for (long tenant : user.tenants()) {
            Map<Long, String> connections = accounts.connectionStatuses(tenant);
            Map<Long, DayBook> books = dayBooks(tenant, today);
            for (BrokerAccount a : accounts.ofTenant(tenant)) {
                out.add(new AccountView(a.id(), a.tenantId(), a.displayName(), a.brokerType().name(), a.tradeMode().name(),
                        a.role().name(), a.enabled(), a.allocatedCapital(), a.riskFile(), connections.get(a.id()),
                        turns.get(a.id()), a.paperOf(), books.getOrDefault(a.id(), DayBook.NONE)));
            }
        }
        return out;
    }

    /** Today's book of every account of {@code tenant} with a position open now or closed today, in one query. */
    private Map<Long, DayBook> dayBooks(long tenant, LocalDate day) {
        Map<Long, DayBook> books = new LinkedHashMap<>();
        java.sql.Timestamp from = java.sql.Timestamp.from(day.atStartOfDay(IST).toInstant());
        jdbc.query("select p.broker_account_id id, i.execution_mode = 'LIVE' live, "
                + "coalesce(sum(p.realised_gross - coalesce(p.charges, 0)) filter (where p.status = 'CLOSED'), 0)::float8 net, "
                + "count(*) filter (where p.status = 'CLOSED') trades, count(*) filter (where p.status <> 'CLOSED') open "
                + "from exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "where p.tenant_id = ? and (p.status <> 'CLOSED' or p.closed_at >= ?) group by 1, 2", rs -> {
                    long id = rs.getLong("id");
                    boolean live = rs.getBoolean("live");
                    double net = rs.getDouble("net");
                    int trades = rs.getInt("trades"), open = rs.getInt("open");
                    DayBook b = books.getOrDefault(id, DayBook.NONE);
                    books.put(id, new DayBook(cents(b.net() + net), b.trades() + trades, b.open() + open,
                            live ? cents(b.liveNet() + net) : b.liveNet(), b.liveTrades() + (live ? trades : 0),
                            b.liveOpen() + (live ? open : 0)));
                }, tenant, from);
        return books;
    }

    private static double cents(double v) {
        return Math.round(v * 100) / 100.0;
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
        BrokerAccount a = own(user, id, false);
        String status = body.status() == null ? "" : body.status();
        Access.require(status.equals("ACTIVE") || status.equals("DISCONNECTED"), "status: ACTIVE or DISCONNECTED");
        Integer linked = jdbc.queryForObject("select count(*) from acct.broker_account where id = ? and credential_profile_id "
                + "is not null", Integer.class, a.id());
        Access.require(!"ACTIVE".equals(status) || linked == null || linked == 0,
                a.displayName() + " is linked to a Kite app: log in with Kite (its session is set by the login, not by hand)");
        accounts.setConnectionStatus(a.id(), status);
        kills.audit(a, user.subject(), "SESSION_" + status, Map.of());
        if (status.equals("DISCONNECTED")) {
            new com.autotrade.execution.TradingDay(jdbc.getDataSource()).sessionLost(a, user.subject());
        }
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
            List<Long> tenants = user.tenants().stream().filter(user::emergency).toList();
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
            case "TENANT" -> kills.engage(actingTenant(user, e.tenantId()), scope, null, null, null, e.reason(),
                    user.subject());           // a member's own switch: every account of theirs (V29)
            case "ACCOUNT" -> {
                BrokerAccount a = own(user, e.accountId(), true);
                yield kills.engage(a.tenantId(), scope, a.id(), null, null, e.reason(), user.subject());
            }
            case "STRATEGY" -> kills.engage(actingTenant(user, e.tenantId()), scope, null, e.strategyId(), null, e.reason(),
                    user.subject());
            case "INSTRUMENT" -> kills.engage(actingTenant(user, e.tenantId()), scope, null, null, e.instrumentKey(), e.reason(),
                    user.subject());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope: GLOBAL_LIVE, TENANT, ACCOUNT, "
                    + "STRATEGY or INSTRUMENT");
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
        BrokerAccount a = own(user, id, true);
        return Map.of("account", a.displayName(), "result", service.flatten(a.id(), user.subject(),
                f.reason() == null ? "manual" : f.reason(), LocalDate.now(IST)));
    }

    record PositionExit(String confirm) {
    }

    /** The trader's per-position Exit (7 Nov 2026): sells that one position now, the account keeps trading. */
    @PostMapping("/api/execution/accounts/{id}/positions/{positionId}/exit")
    Map<String, Object> exitPosition(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                     @PathVariable long id, @PathVariable long positionId, @RequestBody PositionExit e)
            throws Exception {
        Access.User user = access.user(subject);
        Access.require("EXIT".equals(e.confirm()), "confirm with \"EXIT\"");
        BrokerAccount a = own(user, id, true);
        try {
            return Map.of("account", a.displayName(), "result", service.exitPosition(a.id(), positionId, user.subject(),
                    LocalDate.now(IST)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /** {@code at}: ISO local date-time in IST (e.g. 2026-11-06T09:57:30); {@code unchecked}: book without a broker read. */
    record HandExit(Double price, String at, Boolean unchecked) {
    }

    /**
     * Book a hand exit (7 Nov 2026): a position the trader sold outside the app closes in our record as MANUAL at the
     * price and time they enter ({@link com.autotrade.execution.ManualExit} says when it is refused).
     */
    @PostMapping("/api/execution/accounts/{id}/positions/{positionId}/hand-exit")
    Map<String, Object> handExit(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                 @PathVariable long id, @PathVariable long positionId, @RequestBody HandExit h) throws Exception {
        Access.User user = access.user(subject);
        BrokerAccount a = own(user, id, false);
        if (h.price() == null || h.at() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price and at are required");
        }
        java.time.Instant at;
        try {
            at = java.time.LocalDateTime.parse(h.at()).atZone(IST).toInstant();
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at: a date-time like 2026-11-06T09:57:30");
        }
        try {
            return Map.of("account", a.displayName(), "result", service.bookManualExit(a.id(), positionId, h.price(), at,
                    user.subject(), Boolean.TRUE.equals(h.unchecked())));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @PostMapping("/api/execution/flatten-all")
    Map<String, Object> flattenAll(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                   @RequestBody Flatten f) throws Exception {
        Access.User user = access.user(subject);
        Access.require("FLATTEN ALL".equals(f.confirm()), "confirm with \"FLATTEN ALL\"");
        Map<String, Object> out = new LinkedHashMap<>();
        for (long tenant : user.tenants()) {
            if (!user.emergency(tenant)) {
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

    /**
     * What a VIEWER's history shows (11 Nov 2026): what happened to their own accounts, the settings they trade with and
     * the stops and exits on them. Not the workspace's members, logins, broker apps, keys or support access, and not the
     * engine's internals (late fills, retries, reconciliation): those are for the people who run the workspace.
     */
    static final List<String> VIEWER_ACTIONS = List.of("ACCOUNT_CREATED", "ACCOUNT_SETTINGS", "LIVE_ON", "LIVE_OFF",
            "LIVE_ELIGIBLE_ON", "LIVE_ELIGIBLE_OFF", "LIVE_STRATEGIES", "TODAYS_BROKER", "KILL_ACCOUNT", "KILL_RELEASED",
            "FLATTEN", "POSITION_EXIT", "MANUAL_EXIT", "EOD_SQUARE_OFF", "MODE_PAPER_SESSION_LOST", "MODE_PAPER_NEW_SESSION",
            "BROKER_SESSION_LOST");

    /**
     * The audit log of the caller's tenants, newest first. Every role but VIEWER (an AUDITOR included) reads it whole; a
     * VIEWER reads {@link #VIEWER_ACTIONS} on accounts only, and who did it as "you", "admin" (a person) or "system" (the
     * engine), never a username (user, 11 Nov 2026: "is audit log required for Follower/Demo accounts?").
     */
    @GetMapping("/api/execution/audit")
    List<Map<String, Object>> audit(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                    @RequestParam(value = "account", required = false) Long accountId) {
        Access.User user = access.user(subject);
        if (accountId != null) {
            accounts.byId(accountId).filter(x -> user.sees(x.tenantId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + accountId));
        }
        Long[] full = user.roles().entrySet().stream().filter(e -> !"VIEWER".equals(e.getValue())).map(Map.Entry::getKey)
                .toArray(Long[]::new);
        Long[] viewing = user.roles().entrySet().stream().filter(e -> "VIEWER".equals(e.getValue())).map(Map.Entry::getKey)
                .toArray(Long[]::new);
        return jdbc.queryForList("select l.id, l.tenant_id, l.broker_account_id, case when l.tenant_id = any(?) then l.actor "
                        + "when l.actor = ? then 'you' when exists (select 1 from acct.app_user u where u.external_subject = "
                        + "l.actor) then 'admin' else 'system' end actor, l.action, l.detail::text detail, l.at "
                        + "from exec.audit_log l where (l.tenant_id = any(?) or (l.tenant_id = any(?) and l.broker_account_id "
                        + "is not null and l.action = any(?))) and (?::bigint is null or l.broker_account_id = ?) "
                        + "order by l.id desc limit 200", full, user.subject(), full, viewing,
                VIEWER_ACTIONS.toArray(new String[0]), accountId, accountId);
    }

    /**
     * An account the caller may act on ({@code emergency}: or stop, as SUPPORT may without a grant); 404 when it is another
     * tenant's (its existence is not revealed).
     */
    private BrokerAccount own(Access.User user, Long accountId, boolean emergency) {
        if (accountId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
        BrokerAccount a = accounts.byId(accountId).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + accountId));
        Access.require(emergency ? user.emergency(a.tenantId()) : user.mayAct(a.tenantId()), user.support(a.tenantId())
                ? "SUPPORT needs the member's grant for this" : "read-only role for this account");
        return a;
    }

    /**
     * The tenant a STRATEGY or INSTRUMENT switch is for: the one asked for (the caller must be able to act for it), else
     * the caller's only such tenant (29 Oct 2026: an owner who is ADMIN of family members' tenants must say whose).
     */
    private static long actingTenant(Access.User user, Long requested) {
        if (requested != null) {
            Access.require(user.sees(requested) && user.emergency(requested), "no right to act for tenant " + requested);
            return requested;
        }
        List<Long> acting = user.tenants().stream().filter(user::emergency).toList();
        Access.require(!acting.isEmpty(), "no tenant you may act for");
        if (acting.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "you act for several tenants: give tenantId");
        }
        return acting.getFirst();
    }
}
