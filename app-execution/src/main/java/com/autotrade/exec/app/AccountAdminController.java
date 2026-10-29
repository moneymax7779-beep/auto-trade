package com.autotrade.exec.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
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
import com.autotrade.account.BrokerType;
import com.autotrade.account.EquityMode;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.FanOut;
import com.autotrade.execution.KillSwitches;

/**
 * Account management on the Accounts page (user, 24 Oct 2026: "start with account management on the Accounts page"),
 * every call scoped to the caller's tenants and audited:
 * <ul>
 *   <li>{@code GET /api/execution/accounts/{id}/details}: settings, Zerodha client id, credential profile, the daily
 *       loss limit, strategy subscriptions, the broker session and today's P&amp;L;</li>
 *   <li>{@code GET /api/execution/accounts/{id}/positions?day=}, {@code .../pnl?days=} and {@code .../ledger?days=}:
 *       positions of a day, the daily realised net, and the account's ledger (opening and closing equity per day);</li>
 *   <li>{@code GET /api/execution/credential-profiles}, {@code GET /api/execution/strategy-ids}: what a form offers;</li>
 *   <li>{@code POST /api/execution/accounts}: a new follower account (OWNER / ADMIN);</li>
 *   <li>{@code POST /api/execution/accounts/{id}/settings}: allocation, equity mode, daily loss limit, mode, enabled,
 *       client id, strategies (OWNER / ADMIN).</li>
 * </ul>
 * Nothing here makes an account LIVE: the mode is PAPER or DRY_RUN only, and {@code live_trading_enabled} is never
 * touched (LIVE stays a separate, explicit step, and is refused by the broker registry in this build).
 */
@RestController
class AccountAdminController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{1,39}");
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Z]{2}[0-9]{4,6}");
    private static final Pattern STRATEGY = Pattern.compile("[a-z0-9][a-z0-9-]{1,59}");
    private static final double MAX_CAPITAL = 100_000_000;               // Rs 10 Cr: a typo guard, not a policy
    private static final String DEFAULT_RISK = "config/risk/follower-risk.v3.yaml";

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KillSwitches audit;
    private final FanOut fanOut;
    private final ExecutionProperties properties;

    AccountAdminController(Access access, DataSource dataSource, TransactionTemplate tx, ExecutionProperties properties) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.audit = new KillSwitches(dataSource);
        this.fanOut = new FanOut(dataSource);
        this.properties = properties;
    }

    // ------------------------------------------------------------------------------------------------- reads

    @GetMapping("/api/execution/accounts/{id}/details")
    Map<String, Object> details(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id) {
        BrokerAccount a = visible(access.user(subject), id);
        Map<String, Object> row = jdbc.queryForMap("select a.external_broker_user_id, a.daily_loss_limit, a.connection_status, "
                + "p.name profile from acct.broker_account a left join acct.broker_credential_profile p on p.id = "
                + "a.credential_profile_id where a.id = ?", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id());
        out.put("name", a.displayName());
        out.put("broker", a.brokerType().name());
        out.put("mode", a.tradeMode().name());
        out.put("enabled", a.enabled());
        out.put("allocatedCapital", a.allocatedCapital());
        out.put("equityMode", a.equityMode().name());
        out.put("equityFrom", a.equityFrom().toString());
        out.put("riskFile", a.riskFile());
        out.put("sizeStepPct", a.sizeStepPct());
        out.put("clientId", row.get("external_broker_user_id"));
        out.put("credentialProfile", row.get("profile"));
        out.put("dailyLossLimit", row.get("daily_loss_limit") == null ? null : ((BigDecimal) row.get("daily_loss_limit")).doubleValue());
        out.put("connection", row.get("connection_status"));
        out.put("session", jdbc.queryForList("select status, to_char(expires_at at time zone 'Asia/Kolkata', 'DD Mon HH24:MI') "
                + "expires from acct.broker_session where broker_account_id = ? order by id desc limit 1", id).stream()
                .findFirst().orElse(null));
        out.put("strategies", jdbc.queryForList("select s.strategy_id, s.enabled, s.size_multiplier, g.name copy_group "
                + "from acct.strategy_subscription s join acct.copy_group g on g.id = s.copy_group_id "
                + "where s.broker_account_id = ? order by s.strategy_id", id));
        LocalDate today = LocalDate.now(IST);
        out.put("today", jdbc.queryForMap("select count(*) filter (where status = 'OPEN') open, "
                + "count(*) filter (where status = 'CLOSED') closed, coalesce(sum(realised_gross - coalesce(charges, 0)) "
                + "filter (where status = 'CLOSED'), 0) net from exec.position where broker_account_id = ? "
                + "and (opened_at at time zone 'Asia/Kolkata')::date = ?", id, today));
        return out;
    }

    @GetMapping("/api/execution/accounts/{id}/positions")
    List<Map<String, Object>> positions(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                        @PathVariable long id, @RequestParam(required = false) String day) {
        visible(access.user(subject), id);
        LocalDate date = day == null || day.isBlank() ? LocalDate.now(IST) : LocalDate.parse(day);
        return jdbc.queryForList("select id, strategy_id, underlying, option_side, symbol, quantity_open, average_entry, "
                + "to_char(opened_at at time zone 'Asia/Kolkata', 'HH24:MI:SS') opened, "
                + "to_char(closed_at at time zone 'Asia/Kolkata', 'HH24:MI:SS') closed, exit_reason, realised_gross, charges, "
                + "status from exec.position where broker_account_id = ? and (opened_at at time zone 'Asia/Kolkata')::date = ? "
                + "order by opened_at", id, date);
    }

    @GetMapping("/api/execution/accounts/{id}/pnl")
    List<Map<String, Object>> pnl(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                  @PathVariable long id, @RequestParam(defaultValue = "30") int days) {
        visible(access.user(subject), id);
        int n = Math.max(1, Math.min(days, 366));
        return jdbc.queryForList("select (closed_at at time zone 'Asia/Kolkata')::date::text as day, count(*) as trades, "
                + "count(*) filter (where realised_gross - coalesce(charges, 0) > 0) wins, "
                + "sum(realised_gross - coalesce(charges, 0)) net from exec.position where broker_account_id = ? "
                + "and status = 'CLOSED' and closed_at > now() - make_interval(days => ?) group by 1 order by 1 desc", id, n);
    }

    /**
     * The account's own ledger (29 Oct 2026, user: "its own ledger"), newest day first: per day with closed trades (and
     * today while it has open ones) the opening equity as the follower sizes it (allocation + capital movements, plus the
     * realised net since the equity date when COMPOUNDING), trades, wins, gross, charges, net, closing equity (opening +
     * net, as acct.ledger_day) and, once the broker was reconciled, its net and the difference.
     */
    @GetMapping("/api/execution/accounts/{id}/ledger")
    List<Map<String, Object>> ledger(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                     @PathVariable long id, @RequestParam(defaultValue = "60") int days) {
        BrokerAccount a = visible(access.user(subject), id);
        List<Map<String, Object>> rows = jdbc.queryForList("select (coalesce(closed_at, opened_at) at time zone "
                + "'Asia/Kolkata')::date as day, count(*) filter (where status = 'CLOSED') trades, "
                + "count(*) filter (where status = 'CLOSED' and realised_gross - coalesce(charges, 0) > 0) wins, "
                + "count(*) filter (where status <> 'CLOSED') open, "
                + "coalesce(sum(realised_gross) filter (where status = 'CLOSED'), 0) gross, "
                + "coalesce(sum(charges) filter (where status = 'CLOSED'), 0) charges "
                + "from exec.position where broker_account_id = ? group by 1 order by 1", id);
        Map<LocalDate, Map<String, Object>> broker = new LinkedHashMap<>();
        jdbc.queryForList("select session_date, broker_net, difference from acct.ledger_day where broker_account_id = ?", id)
                .forEach(r -> broker.put(((java.sql.Date) r.get("session_date")).toLocalDate(), r));
        List<Map<String, Object>> out = new ArrayList<>();
        double realisedBefore = 0;                    // since the equity date, as the follower's sizing counts it
        for (Map<String, Object> r : rows) {
            LocalDate day = ((java.sql.Date) r.get("day")).toLocalDate();
            double gross = ((Number) r.get("gross")).doubleValue(), charges = ((Number) r.get("charges")).doubleValue();
            double net = gross - charges;
            double opening = com.autotrade.account.CapitalPolicy.equity(a, accounts.capitalMovements(id, day),
                    day.isBefore(a.equityFrom()) ? 0 : realisedBefore);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day.toString());
            row.put("openingEquity", round(opening));
            row.put("trades", ((Number) r.get("trades")).intValue());
            row.put("wins", ((Number) r.get("wins")).intValue());
            row.put("open", ((Number) r.get("open")).intValue());
            row.put("gross", round(gross));
            row.put("charges", round(charges));
            row.put("net", round(net));
            row.put("closingEquity", round(opening + net));
            Map<String, Object> b = broker.get(day);
            row.put("brokerNet", b == null ? null : b.get("broker_net"));
            row.put("difference", b == null ? null : b.get("difference"));
            out.add(row);
            if (!day.isBefore(a.equityFrom())) {
                realisedBefore += net;
            }
        }
        java.util.Collections.reverse(out);
        return out.subList(0, Math.min(out.size(), Math.max(1, Math.min(days, 366))));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    @GetMapping("/api/execution/credential-profiles")
    List<Map<String, Object>> profiles(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        List<Map<String, Object>> out = new ArrayList<>();
        for (long tenant : user.tenants()) {                      // names and settings only, never a secret value
            out.addAll(jdbc.queryForList("select id, tenant_id, name, broker_type, redirect_uri, array_to_string(registered_ips, "
                    + "',') ips, status from acct.broker_credential_profile where tenant_id = ? order by name", tenant));
        }
        return out;
    }

    /**
     * Strategy ids a follower can take: those the copy groups its tenant may follow (its own and those shared with it)
     * have published, and those its own accounts are subscribed to (never another tenant's subscriptions).
     */
    @GetMapping("/api/execution/strategy-ids")
    List<String> strategyIds(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Set<String> ids = new LinkedHashSet<>();
        for (long tenant : user.tenants()) {
            Long[] groups = accounts.followableCopyGroups(tenant).toArray(new Long[0]);
            ids.addAll(jdbc.queryForList("select distinct strategy_id from exec.signal_decision where copy_group_id = any(?) "
                    + "and session_date > current_date - 60", String.class, (Object) groups));
            ids.addAll(jdbc.queryForList("select distinct s.strategy_id from acct.strategy_subscription s join "
                    + "acct.broker_account a on a.id = s.broker_account_id where a.tenant_id = ?", String.class, tenant));
        }
        return ids.stream().sorted().toList();
    }

    // ------------------------------------------------------------------------------------------------- writes

    record NewAccount(String name, String broker, String mode, String clientId, String credentialProfile,
                      Double allocation, String equityMode, String equityFrom, Double dailyLossLimit, List<String> strategies,
                      Boolean enabled, Long tenantId) {
    }

    @PostMapping("/api/execution/accounts")
    Map<String, Object> create(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @RequestBody NewAccount body) {
        Access.User user = access.user(subject);
        long tenant = body.tenantId() != null ? body.tenantId() : user.tenants().size() == 1 ? user.tenants().getFirst() : -1;
        Access.require(tenant > 0 && user.manages(tenant), "only an OWNER or ADMIN of the tenant may create accounts");
        String name = trimmed(body.name());
        require(name != null && NAME.matcher(name).matches(), "name: 2-40 letters, digits, space, _ or -");
        BrokerType broker = parse(BrokerType.class, body.broker(), "broker");
        require(broker == BrokerType.PAPER || broker == BrokerType.ZERODHA,
                "broker: PAPER or ZERODHA (no order adapter for " + broker + " yet)");
        TradeMode mode = parse(TradeMode.class, body.mode() == null ? (broker == BrokerType.PAPER ? "PAPER" : "DRY_RUN")
                : body.mode(), "mode");
        require(mode != TradeMode.LIVE, "an account is never created LIVE");
        require(broker != BrokerType.PAPER || mode == TradeMode.PAPER, "a PAPER broker account trades PAPER only");
        double allocation = capital(body.allocation(), "allocation");
        EquityMode equityMode = parse(EquityMode.class, body.equityMode() == null ? "FIXED" : body.equityMode(), "equity mode");
        LocalDate from = body.equityFrom() == null || body.equityFrom().isBlank() ? LocalDate.now(IST)
                : LocalDate.parse(body.equityFrom());
        Double loss = lossLimit(body.dailyLossLimit(), allocation);
        String clientId = clientId(body.clientId());
        Long profile = null;
        if (broker == BrokerType.ZERODHA) {
            require(clientId != null, "a Zerodha account needs its client id (e.g. AB1234)");
            profile = profileId(tenant, body.credentialProfile());
        }
        long group = copyGroup(tenant);
        List<String> strategies = strategyList(body.strategies());
        Long profileId = profile;
        long id = tx.execute(t -> {
            Integer taken = jdbc.queryForObject("select count(*) from acct.broker_account where tenant_id = ? and display_name = ?",
                    Integer.class, tenant, name);
            require(taken != null && taken == 0, "an account named " + name + " already exists");
            long owner = owner(user, tenant);
            long created = accounts.ensureFollowerAccount(tenant, owner, name, broker, mode, allocation, equityMode, from,
                    DEFAULT_RISK, 100);
            jdbc.update("update acct.broker_account set credential_profile_id = ?, external_broker_user_id = ?, "
                    + "daily_loss_limit = ?, enabled = ?, updated_at = now() where id = ?", profileId, clientId, loss,
                    !Boolean.FALSE.equals(body.enabled()), created);
            if (!strategies.isEmpty()) {
                fanOut.subscribe(group, created, strategies, 1.0);
            }
            return created;
        });
        BrokerAccount account = accounts.byId(id).orElseThrow();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("broker", broker.name());
        detail.put("mode", mode.name());
        detail.put("allocation", allocation);
        detail.put("equityMode", equityMode.name());
        detail.put("dailyLossLimit", loss);
        detail.put("clientId", clientId);
        detail.put("strategies", strategies);
        audit.audit(account, user.subject(), "ACCOUNT_CREATED", detail);
        return Map.of("id", id, "name", name);
    }

    record Settings(Double allocation, String equityMode, Double dailyLossLimit, Boolean clearDailyLossLimit, String mode,
                    Boolean enabled, String clientId, List<String> strategies) {
    }

    @PostMapping("/api/execution/accounts/{id}/settings")
    Map<String, Object> settings(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                 @PathVariable long id, @RequestBody Settings body) {
        Access.User user = access.user(subject);
        BrokerAccount a = visible(user, id);
        Access.require(user.manages(a.tenantId()), "only an OWNER or ADMIN may change an account's settings");
        require(a.role().name().equals("FOLLOWER_TRADE"), a.displayName() + " is not a follower account");
        Map<String, Object> before = details(subject, id);
        Map<String, Object> changed = new LinkedHashMap<>();
        tx.executeWithoutResult(t -> {
            if (body.allocation() != null) {
                double allocation = capital(body.allocation(), "allocation");
                jdbc.update("update acct.broker_account set allocated_capital = ?, updated_at = now() where id = ?", allocation, id);
                changed.put("allocation", allocation);
            }
            if (body.equityMode() != null) {
                EquityMode m = parse(EquityMode.class, body.equityMode(), "equity mode");
                jdbc.update("update acct.broker_account set equity_mode = ?, updated_at = now() where id = ?", m.name(), id);
                changed.put("equityMode", m.name());
            }
            if (Boolean.TRUE.equals(body.clearDailyLossLimit())) {
                jdbc.update("update acct.broker_account set daily_loss_limit = null, updated_at = now() where id = ?", id);
                changed.put("dailyLossLimit", "risk file %");
            } else if (body.dailyLossLimit() != null) {
                double allocation = body.allocation() != null ? body.allocation() : a.allocatedCapital();
                Double loss = lossLimit(body.dailyLossLimit(), allocation);
                jdbc.update("update acct.broker_account set daily_loss_limit = ?, updated_at = now() where id = ?", loss, id);
                changed.put("dailyLossLimit", loss);
            }
            if (body.mode() != null) {
                TradeMode m = parse(TradeMode.class, body.mode(), "mode");
                require(m != TradeMode.LIVE, "LIVE is not switched here (a separate, explicit step)");
                require(a.brokerType() != BrokerType.PAPER || m == TradeMode.PAPER, "a PAPER broker account trades PAPER only");
                jdbc.update("update acct.broker_account set trade_mode = ?, updated_at = now() where id = ? "
                        + "and not live_trading_enabled", m.name(), id);
                changed.put("mode", m.name());
            }
            if (body.enabled() != null) {
                jdbc.update("update acct.broker_account set enabled = ?, updated_at = now() where id = ?", body.enabled(), id);
                if (body.enabled() && !a.enabled()) {
                    // switched back on: it follows from now, not the entries it missed while off (code review 29 Oct)
                    jdbc.update("update acct.strategy_subscription set enabled_at = now() where broker_account_id = ? "
                            + "and enabled", id);
                }
                changed.put("enabled", body.enabled());
            }
            if (body.clientId() != null) {
                String clientId = clientId(body.clientId());
                require(clientId != null, "client id: two letters and 4-6 digits (e.g. AB1234)");
                Integer sessions = jdbc.queryForObject("select count(*) from acct.broker_session where broker_account_id = ? "
                        + "and status = 'ACTIVE'", Integer.class, id);
                require(sessions == null || sessions == 0 || clientId.equals(before.get("clientId")),
                        "log out first: the account has an ACTIVE session for client " + before.get("clientId"));
                jdbc.update("update acct.broker_account set external_broker_user_id = ?, updated_at = now() where id = ?",
                        clientId, id);
                changed.put("clientId", clientId);
            }
            if (body.strategies() != null) {
                List<String> wanted = strategyList(body.strategies());
                long group = copyGroup(a.tenantId());
                jdbc.update("update acct.strategy_subscription set enabled = false where broker_account_id = ? "
                        + "and copy_group_id = ? and not (strategy_id = any(?))", id, group, wanted.toArray(new String[0]));
                if (!wanted.isEmpty()) {
                    fanOut.subscribe(group, id, wanted, 1.0);
                }
                changed.put("strategies", wanted);
            }
        });
        require(!changed.isEmpty(), "nothing to change");
        Map<String, Object> detail = new LinkedHashMap<>(changed);
        detail.put("before", Map.of("allocation", before.get("allocatedCapital"), "equityMode", before.get("equityMode"),
                "dailyLossLimit", String.valueOf(before.get("dailyLossLimit")), "mode", before.get("mode"),
                "enabled", before.get("enabled")));
        audit.audit(accounts.byId(id).orElseThrow(), user.subject(), "ACCOUNT_SETTINGS", detail);
        return details(subject, id);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /**
     * Whose account a new one is: the caller's, unless they are the ADMIN of a family member's tenant (one the owner's
     * signals are shared with), then that member's whatever their role (OWNER, TRADER, VIEWER, AUDITOR); the caller
     * when the tenant has no such member. In the owner's own tenant it is always the caller.
     */
    private long owner(Access.User user, long tenant) {
        long caller = jdbc.queryForObject("select id from acct.app_user where external_subject = ?", Long.class, user.subject());
        Integer shared = jdbc.queryForObject("select count(*) from acct.copy_group_share where tenant_id = ?", Integer.class,
                tenant);
        if (!"ADMIN".equals(user.roles().get(tenant)) || shared == null || shared == 0) {
            return caller;
        }
        return jdbc.queryForList("select app_user_id from acct.tenant_membership where tenant_id = ? and role <> 'ADMIN' "
                + "order by case role when 'OWNER' then 0 when 'TRADER' then 1 when 'VIEWER' then 2 else 3 end, app_user_id "
                + "limit 1", Long.class, tenant).stream().findFirst().orElse(caller);
    }

    private BrokerAccount visible(Access.User user, long id) {
        return accounts.byId(id).filter(a -> user.sees(a.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
    }

    /**
     * The copy group subscriptions go to, among those the tenant may follow (its own and those shared with it): the
     * worker's configured group, else the only one.
     */
    private long copyGroup(long tenant) {
        String name = properties.copyGroup();
        Long[] followable = accounts.followableCopyGroups(tenant).toArray(new Long[0]);
        List<Long> ids = "*".equals(name) || name.contains(",") ? List.of(followable)
                : jdbc.queryForList("select id from acct.copy_group where id = any(?) and name = ?", Long.class,
                        followable, name);
        if (ids.size() > 1) {                    // several (drills): the live set's group, LIVE-SET, when it is one of them
            List<Long> live = jdbc.queryForList("select id from acct.copy_group where id = any(?) and name = 'LIVE-SET'",
                    Long.class, (Object) ids.toArray(new Long[0]));
            if (live.size() == 1) {
                return live.getFirst();
            }
        }
        require(ids.size() == 1, ids.isEmpty() ? "the tenant has no copy group to follow yet (the owner's is created by the "
                + "first signal-source session and shared with a family member when they are added)"
                : "the tenant may follow several copy groups and none is LIVE-SET");
        return ids.getFirst();
    }

    private long profileId(long tenant, String name) {
        List<Long> ids = name == null || name.isBlank()
                ? jdbc.queryForList("select id from acct.broker_credential_profile where tenant_id = ? and broker_type = 'ZERODHA' "
                        + "and status = 'ACTIVE'", Long.class, tenant)
                : jdbc.queryForList("select id from acct.broker_credential_profile where tenant_id = ? and broker_type = 'ZERODHA' "
                        + "and status = 'ACTIVE' and name = ?", Long.class, tenant, name);
        require(ids.size() == 1, ids.isEmpty() ? "no active Zerodha app (credential profile) for this tenant"
                : "several Zerodha apps: choose one");
        return ids.getFirst();
    }

    private static double capital(Double value, String what) {
        require(value != null && value > 0 && value <= MAX_CAPITAL, what + ": a positive amount up to Rs 10 Cr");
        return Math.round(value * 100) / 100.0;
    }

    private static Double lossLimit(Double value, double allocation) {
        if (value == null) {
            return null;
        }
        require(value > 0 && value <= allocation, "daily loss limit: a positive amount, at most the allocation");
        return Math.round(value * 100) / 100.0;
    }

    private static String clientId(String value) {
        String v = trimmed(value);
        if (v == null) {
            return null;
        }
        v = v.toUpperCase(Locale.ROOT);
        require(CLIENT_ID.matcher(v).matches(), "client id: two letters and 4-6 digits (e.g. AB1234)");
        return v;
    }

    private static List<String> strategyList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> out = values.stream().map(AccountAdminController::trimmed).filter(java.util.Objects::nonNull).distinct().toList();
        out.forEach(s -> require(STRATEGY.matcher(s).matches(), "strategy id: " + s));
        return out;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + ": unknown " + value);
        }
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
