package com.autotrade.exec.app;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.FollowerRisk;
import com.autotrade.execution.KillSwitches;
import com.autotrade.execution.TradingDay;

/**
 * A trader's workspace day (trader workspaces, user 7 Nov 2026: "each member will choose which broker to use for trading
 * that day that has to be in his dashboard ... if member not logged in to broker account, it should be PAPER trading ...
 * change the flag to LIVE and then only after any signal should be traded ... not allowed for splitting"; plan section
 * 2.5 and 10):
 * <ul>
 *   <li>{@code GET /api/execution/workspaces/{tenant}/today}: the day's broker, PAPER or LIVE since when, every broker of
 *       the workspace (connection, session, LIVE eligibility, strategies, today's paper and live result), the member's
 *       loss limit and kill switch, and today's switches;</li>
 *   <li>{@code POST .../today/broker} {@code {"accountId": n | null}}: the day's broker (null = Off); it starts PAPER;</li>
 *   <li>{@code POST .../today/mode} {@code {"mode": "LIVE", "confirm": "LIVE"}} or {@code {"mode": "PAPER"}}: the switch.
 *       LIVE needs the chosen broker LIVE-eligible with an ACTIVE session today, no kill switch, the member's loss limit
 *       not reached, and no other broker of theirs holding a live position or a working order; only signals decided
 *       after it go to the broker. PAPER is always allowed (SUPPORT too, without a grant);</li>
 *   <li>{@code POST .../limits} {@code {"dailyLossLimit": r}} or {@code {"clear": true}}: the member's LIVE loss limit
 *       across their brokers;</li>
 *   <li>{@code POST /api/execution/accounts/{id}/live-eligible} {@code {"on": true, "confirm": name}}: the platform admin's
 *       one-time check that a real broker account may be switched LIVE at all;</li>
 *   <li>{@code GET /api/execution/household}: the platform admin's one card per family workspace.</li>
 * </ul>
 * Every change is audited and the member is told on Telegram (the admin too for LIVE).
 */
@RestController
class WorkspaceController {

    /** The brokers this build can trade LIVE (an order adapter with a LIVE route: BrokerConnections). */
    static final Set<BrokerType> LIVE_BROKERS = com.autotrade.execution.LiveBrokers.LIVE;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final double MAX_LIMIT = 100_000_000;                 // Rs 10 Cr: a typo guard, not a policy
    private static final String NO_EXPOSURE = " and not exists (select 1 from exec.position p where p.broker_account_id = "
            + "acct.broker_account.id and p.status <> 'CLOSED') and not exists (select 1 from exec.order_execution e "
            + "where e.broker_account_id = acct.broker_account.id and e.status in ('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', "
            + "'OPEN', 'PARTIALLY_FILLED', 'CANCEL_PENDING', 'UNKNOWN', 'RECONCILIATION_REQUIRED'))";

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TradingDay tradingDay;
    private final KillSwitches kills;
    private final TelegramNotifier telegram;

    WorkspaceController(Access access, DataSource dataSource, TransactionTemplate tx, TelegramNotifier telegram) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.tradingDay = new TradingDay(dataSource);
        this.kills = new KillSwitches(dataSource);
        this.telegram = telegram;
    }

    // ------------------------------------------------------------------------------------------------- the day

    @GetMapping("/api/execution/workspaces/{tenant}/today")
    Map<String, Object> today(@RequestHeader(value = Access.HEADER, required = false) String subject,
                              @PathVariable long tenant) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        return view(user, tenant);
    }

    record Broker(Long accountId) {
    }

    @PostMapping("/api/execution/workspaces/{tenant}/today/broker")
    Map<String, Object> broker(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable long tenant, @RequestBody Broker body) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        Access.require(user.mayAct(tenant), user.support(tenant) ? "SUPPORT needs the member's grant to choose the broker"
                : "read-only role in this workspace");
        dailySwitch(tenant);
        BrokerAccount chosen = body.accountId() == null ? null : choosable(tenant, body.accountId());
        TradingDay.State before = tx.execute(t -> {
            tradingDay.lock(tenant);
            TradingDay.State now = tradingDay.stateNow(tenant);
            Long id = chosen == null ? null : chosen.id();
            if (java.util.Objects.equals(now.brokerAccountId(), id)) {
                return now;                                         // already the day's broker: its mode is kept
            }
            tradingDay.append(tenant, id, TradingDay.Mode.PAPER, "BROKER", user.id());
            if (id != null) {
                jdbc.update("update acct.tenant set default_broker_account_id = ? where id = ?", id, tenant);
            }
            return now;
        });
        if (!java.util.Objects.equals(before.brokerAccountId(), chosen == null ? null : chosen.id())) {
            String name = chosen == null ? "Off (no trading)" : chosen.displayName() + ", PAPER";
            kills.audit(tenant, chosen == null ? null : chosen.id(), user.subject(), "TODAYS_BROKER",
                    detail("broker", name, "before", String.valueOf(before.brokerAccountId()), "beforeMode",
                            before.mode().name()));
            telegram.sendToMembers(tenant, "Today's broker: " + name + " (by " + user.subject() + ")."
                    + (before.mode() == TradingDay.Mode.LIVE ? " The earlier LIVE broker's open positions stay there and exit "
                    + "there." : ""));
        }
        return view(user, tenant);
    }

    record Mode(String mode, String confirm) {
    }

    @PostMapping("/api/execution/workspaces/{tenant}/today/mode")
    Map<String, Object> mode(@RequestHeader(value = Access.HEADER, required = false) String subject,
                             @PathVariable long tenant, @RequestBody Mode body) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        dailySwitch(tenant);
        TradingDay.Mode wanted;
        try {
            wanted = TradingDay.Mode.valueOf(String.valueOf(body.mode()).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("mode: PAPER or LIVE");
        }
        if (wanted == TradingDay.Mode.PAPER) {
            Access.require(user.emergency(tenant), "read-only role in this workspace");
        } else {
            // real money: the workspace's OWNER (or SUPPORT with their grant), as every other money setting (code review
            // 7 Nov 2026: not a TRADER)
            Access.require(user.manages(tenant), user.support(tenant)
                    ? "SUPPORT needs the member's grant to switch LIVE" : "only the workspace's OWNER switches LIVE");
            require("LIVE".equals(body.confirm()), "type LIVE to confirm: real orders go to the broker from the next signal");
        }
        String refused = tx.execute(t -> {
            tradingDay.lock(tenant);
            TradingDay.State now = tradingDay.stateNow(tenant);
            if (now.mode() == wanted) {
                return null;                                         // already so: nothing to record
            }
            if (wanted == TradingDay.Mode.LIVE) {
                String why = liveRefusal(tenant, now.brokerAccountId());
                if (why != null) {
                    return why;
                }
            }
            tradingDay.append(tenant, now.brokerAccountId(), wanted, "SWITCH", user.id());
            return "";
        });
        if (refused != null && !refused.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, refused);
        }
        if (refused != null) {
            TradingDay.State now = tradingDay.stateNow(tenant);
            BrokerAccount a = accounts.byId(now.brokerAccountId()).orElseThrow();
            kills.audit(a, user.subject(), "MODE_" + wanted, detail("since", String.valueOf(now.since())));
            String text = a.displayName() + " is " + wanted + " from " + HHMM.format(Instant.now().atZone(IST)) + " IST (by "
                    + user.subject() + ")" + (wanted == TradingDay.Mode.LIVE ? ": real orders go to the broker from the "
                    + "next signal." : ": new signals paper-trade; open LIVE positions keep their broker stop and exit at the "
                    + "broker.");
            telegram.sendToMembers(tenant, text);
            if (wanted == TradingDay.Mode.LIVE) {
                telegram.send(tenantName(tenant) + ": " + text);
            }
        }
        return view(user, tenant);
    }

    /** Why the workspace's chosen broker cannot go LIVE now; null when it can. Runs under the workspace's lock. */
    private String liveRefusal(long tenant, Long accountId) {
        if (accountId == null) {
            return "choose today's broker first";
        }
        BrokerAccount a = accounts.byId(accountId).orElse(null);
        if (a == null || a.tenantId() != tenant) {
            return "the day's broker no longer exists";
        }
        if (a.brokerType() == BrokerType.PAPER) {
            return a.displayName() + " is a paper account: connect a broker to trade LIVE";
        }
        if (!LIVE_BROKERS.contains(a.brokerType())) {
            return a.brokerType() + " has no LIVE order adapter yet: it trades PAPER only";
        }
        if (!a.enabled()) {
            return a.displayName() + " is disabled";
        }
        if (!a.liveTradingEnabled() || a.tradeMode() != TradeMode.LIVE) {
            return a.displayName() + " is not LIVE-eligible yet (the platform admin's one-time check)";
        }
        Boolean session = jdbc.queryForObject("select exists (select 1 from acct.broker_session where broker_account_id = ? "
                + "and status = 'ACTIVE' and expires_at > now()) and (select connection_status from acct.broker_account "
                + "where id = ?) = 'ACTIVE'", Boolean.class, a.id(), a.id());
        if (!Boolean.TRUE.equals(session)) {
            return "no broker session today: log in to " + a.displayName() + " first";
        }
        if (kills.tenantEngaged(tenant)) {
            return "your kill switch is engaged: release it first";
        }
        String killed = jdbc.queryForList("select scope || ': ' || reason from exec.kill_switch where released_at is null "
                + "and (scope = 'GLOBAL_LIVE' or (scope = 'ACCOUNT' and broker_account_id = ?)) limit 1", String.class,
                a.id()).stream().findFirst().orElse(null);
        if (killed != null) {
            return "a kill switch is engaged (" + killed + ")";
        }
        if (tradingDay.dailyLossReached(tenant, TradingDay.today())) {
            return "your daily loss limit (LIVE, all brokers) is reached for today";
        }
        List<Long> elsewhere = tradingDay.liveExposure(tenant).stream().filter(id -> id != a.id()).toList();
        if (!elsewhere.isEmpty()) {
            return "another broker still holds a live position or a working order (" + names(elsewhere)
                    + "): one broker live at a time";
        }
        return null;
    }

    record Limits(Double dailyLossLimit, Boolean clear) {
    }

    @PostMapping("/api/execution/workspaces/{tenant}/limits")
    Map<String, Object> limits(@RequestHeader(value = Access.HEADER, required = false) String subject,
                               @PathVariable long tenant, @RequestBody Limits body) {
        Access.User user = access.user(subject);
        seen(user, tenant);
        Access.require(user.manages(tenant), "only the workspace's OWNER (or SUPPORT with a grant) sets its loss limit");
        Double limit;
        if (Boolean.TRUE.equals(body.clear())) {
            limit = null;
        } else {
            require(body.dailyLossLimit() != null && body.dailyLossLimit() > 0 && body.dailyLossLimit() <= MAX_LIMIT,
                    "daily loss limit: a positive amount in rupees");
            limit = (double) Math.round(body.dailyLossLimit());
        }
        jdbc.update("update acct.tenant set daily_loss_limit = ? where id = ?", limit, tenant);
        kills.audit(tenant, null, user.subject(), "TENANT_LOSS_LIMIT", detail("dailyLossLimit", String.valueOf(limit)));
        return view(user, tenant);
    }

    // ------------------------------------------------------------------------------------------------- LIVE eligibility

    record Eligible(boolean on, String confirm) {
    }

    /**
     * The platform admin's one-time check that a real broker account may be switched LIVE at all (DAILY_SWITCH
     * workspaces): a broker with a LIVE adapter, the client id and broker app set, a login that worked, a risk file with
     * live limits at one lot an entry, and nothing open. Off: back to PAPER-only, only while nothing is open.
     */
    @PostMapping("/api/execution/accounts/{id}/live-eligible")
    Map<String, Object> liveEligible(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                     @PathVariable long id, @RequestBody Eligible body) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        Access.require(user.admin() && (user.support(a.tenantId()) || "ADMIN".equals(user.roles().get(a.tenantId()))),
                "only the platform admin marks an account LIVE-eligible");
        dailySwitch(a.tenantId());
        require(a.paperOf() == null, "a paper twin never trades LIVE");
        Map<String, Object> detail = new LinkedHashMap<>();
        if (body.on()) {
            require(a.displayName().equals(body.confirm()), "type the account's name (" + a.displayName() + ") to confirm");
            require(a.role().name().equals("FOLLOWER_TRADE") && a.enabled(), a.displayName() + " is not an enabled follower");
            require(LIVE_BROKERS.contains(a.brokerType()), a.brokerType() + " has no LIVE order adapter yet");
            Map<String, Object> row = jdbc.queryForMap("select external_broker_user_id, credential_profile_id from "
                    + "acct.broker_account where id = ?", id);
            require(row.get("external_broker_user_id") != null && row.get("credential_profile_id") != null,
                    "set the broker client id and the broker app first");
            Integer logins = jdbc.queryForObject("select count(*) from exec.audit_log where broker_account_id = ? "
                    + "and action = 'SESSION_LOGIN'", Integer.class, id);
            require(logins != null && logins > 0, "no broker login has worked yet: log in once first");
            require(a.sizeStepPct() == 0, "LIVE trades one lot an entry: set the size step to 0 first");
            FollowerRisk risk;
            try {
                risk = FollowerRisk.load(Path.of(a.riskFile()));
            } catch (RuntimeException e) {
                throw bad("risk file " + a.riskFile() + " not readable");
            }
            require(risk.live() != null && risk.orders() != null, "risk file " + a.riskFile()
                    + " has no live limits (follower-risk v7 or later)");
            int changed = jdbc.update("update acct.broker_account set trade_mode = 'LIVE', live_trading_enabled = true, "
                    + "updated_at = now() where id = ? and not live_trading_enabled" + NO_EXPOSURE, id);
            require(changed == 1, a.displayName() + " is already LIVE-eligible, or holds a position or a working order");
            detail.put("riskFile", a.riskFile());
            detail.put("maxLotsPerOrder", risk.live().maxLotsPerOrder());
            detail.put("strategies", new com.autotrade.execution.LiveStrategies(jdbc.getDataSource()).effective(id, risk));
        } else {
            int changed = tx.execute(t -> {
                tradingDay.lock(a.tenantId());
                int n = jdbc.update("update acct.broker_account set trade_mode = 'PAPER', live_trading_enabled = false, "
                        + "updated_at = now() where id = ? and live_trading_enabled" + NO_EXPOSURE, id);
                TradingDay.State now = tradingDay.stateNow(a.tenantId());
                if (n == 1 && now.mode() == TradingDay.Mode.LIVE && Long.valueOf(id).equals(now.brokerAccountId())) {
                    tradingDay.append(a.tenantId(), id, TradingDay.Mode.PAPER, "NOT_ELIGIBLE", user.id());
                }
                return n;
            });
            require(changed == 1, a.displayName() + " is not LIVE-eligible, or holds a position or a working order");
        }
        kills.audit(accounts.byId(id).orElseThrow(), user.subject(), body.on() ? "LIVE_ELIGIBLE_ON" : "LIVE_ELIGIBLE_OFF",
                detail);
        return Map.of("id", id, "account", a.displayName(), "liveEligible", body.on());
    }

    // ------------------------------------------------------------------------------------------------- household

    /** The platform admin's view of the family: one card per workspace they support. */
    @GetMapping("/api/execution/household")
    List<Map<String, Object>> household(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "the household view is the platform admin's");
        List<Map<String, Object>> out = new ArrayList<>();
        for (long tenant : user.tenants()) {
            if (!user.support(tenant)) {
                continue;
            }
            Map<String, Object> view = view(user, tenant);
            Map<String, Object> card = new LinkedHashMap<>();
            for (String key : List.of("tenantId", "name", "control", "members", "state", "lossLimit", "killSwitches",
                    "support", "pending")) {
                card.put(key, view.get(key));
            }
            card.put("today", view.get("totals"));
            out.add(card);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- the view

    private Map<String, Object> view(Access.User user, long tenant) {
        Map<String, Object> t = jdbc.queryForMap("select name, trading_control, daily_loss_limit from acct.tenant where id = ?",
                tenant);
        Instant now = Instant.now();
        LocalDate today = TradingDay.today();
        boolean daily = TradingDay.DAILY_SWITCH.equals(t.get("trading_control"));
        TradingDay.State state = tradingDay.state(tenant, now);
        Long target = daily ? tradingDay.entryTarget(tenant, now) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenant);
        out.put("name", t.get("name"));
        out.put("control", t.get("trading_control"));
        out.put("role", user.roles().get(tenant));
        out.put("day", today.toString());
        out.put("members", jdbc.queryForList("select u.external_subject username, u.display_name, m.role, "
                + "u.telegram_chat_id is not null telegram, u.status from acct.tenant_membership m join acct.app_user u "
                + "on u.id = m.app_user_id where m.tenant_id = ? and m.role not in ('SUPPORT', 'ADMIN') "
                + "order by m.role, u.external_subject", tenant));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("brokerAccountId", state.brokerAccountId());
        s.put("broker", state.brokerAccountId() == null ? null : name(state.brokerAccountId()));
        s.put("mode", state.mode().name());
        s.put("since", state.since() == null ? null : state.since().toString());
        s.put("targetAccountId", target);
        s.put("trading", target == null ? "OFF" : Long.valueOf(target).equals(state.brokerAccountId())
                && state.mode() == TradingDay.Mode.LIVE ? "LIVE" : "PAPER");
        out.put("state", daily ? s : null);

        List<Map<String, Object>> brokers = new ArrayList<>();
        double paperNet = 0, liveNet = 0;
        int paperOpen = 0, liveOpen = 0;
        for (BrokerAccount a : accounts.ofTenant(tenant)) {
            if (!a.role().name().equals("FOLLOWER_TRADE") || a.paperOf() != null) {
                continue;                                             // twins are shown with their real account
            }
            Long twin = tradingDay.paperTwin(a.id());
            Map<String, Object> b = new LinkedHashMap<>();
            Map<String, Object> row = jdbc.queryForMap("select a.connection_status, a.external_broker_user_id, "
                    + "p.name profile, (select max(x.expires_at) from acct.broker_session x where x.broker_account_id = a.id "
                    + "and x.status = 'ACTIVE' and x.expires_at > now()) session_until from acct.broker_account a "
                    + "left join acct.broker_credential_profile p on p.id = a.credential_profile_id where a.id = ?", a.id());
            b.put("id", a.id());
            b.put("name", a.displayName());
            b.put("broker", a.brokerType().name());
            b.put("clientId", row.get("external_broker_user_id"));
            b.put("brokerApp", row.get("profile"));
            b.put("connection", row.get("connection_status"));
            b.put("sessionUntil", row.get("session_until") == null ? null
                    : ((Timestamp) row.get("session_until")).toInstant().toString());
            b.put("enabled", a.enabled());
            b.put("liveCapable", LIVE_BROKERS.contains(a.brokerType()));
            b.put("liveEligible", a.liveTradingEnabled());
            b.put("paperTwinId", twin);
            b.put("allocation", a.allocatedCapital());
            b.put("equityMode", a.equityMode().name());
            b.put("sizeStepPct", a.sizeStepPct());
            b.put("strategies", jdbc.queryForList("select strategy_id from acct.strategy_subscription where "
                    + "broker_account_id = ? and enabled order by strategy_id", String.class, a.id()));
            b.put("liveStrategies", liveStrategies(a));
            b.put("chosen", Long.valueOf(a.id()).equals(state.brokerAccountId()));
            Map<String, Object> paper = result(a.brokerType() == BrokerType.PAPER ? Long.valueOf(a.id()) : twin, today);
            Map<String, Object> live = a.brokerType() == BrokerType.PAPER ? result(null, today) : result(a.id(), today);
            b.put("paper", paper);
            b.put("live", live);
            paperNet += (double) paper.get("net");
            liveNet += (double) live.get("net");
            paperOpen += (int) paper.get("open");
            liveOpen += (int) live.get("open");
            brokers.add(b);
        }
        out.put("brokers", brokers);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("paperNet", round(paperNet));
        totals.put("liveNet", round(liveNet));
        totals.put("paperOpen", paperOpen);
        totals.put("liveOpen", liveOpen);
        out.put("totals", totals);
        BigDecimal limit = (BigDecimal) t.get("daily_loss_limit");
        Map<String, Object> loss = new LinkedHashMap<>();
        loss.put("dailyLossLimit", limit == null ? null : limit.doubleValue());
        loss.put("liveNetToday", round(tradingDay.liveNet(tenant, today)));
        loss.put("reached", tradingDay.dailyLossReached(tenant, today));
        out.put("lossLimit", loss);
        out.put("killSwitches", jdbc.queryForList("select id, scope, broker_account_id, coalesce(strategy_id, instrument_key) "
                + "what, reason, engaged_by, " + Iso.of("engaged_at") + " engaged_at from exec.kill_switch "
                + "where released_at is null "
                + "and (tenant_id = ? or scope = 'GLOBAL_LIVE') order by engaged_at", tenant));
        out.put("support", jdbc.queryForList("select g.id, u.external_subject granted_to, g.break_glass, g.reason, "
                + Iso.of("g.granted_at") + " granted_at, " + Iso.of("g.expires_at") + " expires_at from acct.support_grant g "
                + "join acct.app_user u "
                + "on u.id = g.granted_to where g.tenant_id = ? and g.revoked_at is null and g.expires_at > now() "
                + "order by g.id", tenant));
        out.put("pending", jdbc.queryForList("select a.id, a.display_name name, case when a.credential_profile_id is null "
                + "then 'BROKER_APP' when a.external_broker_user_id is null then 'CLIENT_ID' else 'LOGIN' end needs "
                + "from acct.broker_account a where a.tenant_id = ? and a.broker_type <> 'PAPER' and a.enabled "
                + "and (a.credential_profile_id is null or a.external_broker_user_id is null or not exists (select 1 from "
                + "exec.audit_log l where l.broker_account_id = a.id and l.action = 'SESSION_LOGIN')) order by a.id", tenant));
        out.put("events", jdbc.queryForList("select to_char(e.changed_at at time zone 'Asia/Kolkata', 'HH24:MI:SS') at, "
                + "coalesce(a.display_name, 'Off') broker, e.mode, e.reason, coalesce(u.external_subject, 'system') by "
                + "from acct.trading_day_event e left join acct.broker_account a on a.id = e.broker_account_id "
                + "left join acct.app_user u on u.id = e.changed_by where e.tenant_id = ? "
                + "and (e.changed_at at time zone 'Asia/Kolkata')::date = ? order by e.changed_at, e.id", tenant,
                java.sql.Date.valueOf(today)));
        out.put("canSwitch", user.mayAct(tenant));
        out.put("canStop", user.emergency(tenant));
        out.put("canManage", user.manages(tenant));
        out.put("platformAdmin", user.admin());
        return out;
    }

    /** One account's day: closed trades' net, open positions (id null: zeros). */
    private Map<String, Object> result(Long accountId, LocalDate day) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (accountId == null) {
            r.put("net", 0.0);
            r.put("trades", 0);
            r.put("open", 0);
            return r;
        }
        Map<String, Object> row = jdbc.queryForMap("select coalesce(sum(realised_gross - coalesce(charges, 0)) filter "
                + "(where status = 'CLOSED' and (closed_at at time zone 'Asia/Kolkata')::date = ?), 0)::float8 net, "
                + "count(*) filter (where status = 'CLOSED' and (closed_at at time zone 'Asia/Kolkata')::date = ?) trades, "
                + "count(*) filter (where status <> 'CLOSED') open from exec.position where broker_account_id = ?",
                java.sql.Date.valueOf(day), java.sql.Date.valueOf(day), accountId);
        r.put("net", round(((Number) row.get("net")).doubleValue()));
        r.put("trades", ((Number) row.get("trades")).intValue());
        r.put("open", ((Number) row.get("open")).intValue());
        return r;
    }

    /** Risk files read once per file (risk files are versioned: a change is a new file). */
    private static final Map<String, FollowerRisk> RISKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** What trades LIVE on the account: the owner's choice within its risk file's list (V32, user 9 Nov 2026). */
    private List<String> liveStrategies(BrokerAccount a) {
        if (!LIVE_BROKERS.contains(a.brokerType())) {
            return List.of();
        }
        FollowerRisk risk;
        try {
            risk = RISKS.computeIfAbsent(a.riskFile(), file -> FollowerRisk.load(Path.of(file)));
        } catch (RuntimeException e) {
            return List.of();
        }
        return new com.autotrade.execution.LiveStrategies(jdbc.getDataSource()).effective(a.id(), risk);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static void seen(Access.User user, long tenant) {
        if (!user.sees(tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + tenant);
        }
    }

    private void dailySwitch(long tenant) {
        if (!tradingDay.dailySwitch(tenant)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this workspace trades each account in its own mode "
                    + "(no daily broker switch)");
        }
    }

    /** A broker the workspace can choose: its own enabled follower, a real broker account or a paper-only one, not a twin. */
    private BrokerAccount choosable(long tenant, long accountId) {
        BrokerAccount a = accounts.byId(accountId).filter(x -> x.tenantId() == tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + accountId
                        + " in this workspace"));
        require(a.role().name().equals("FOLLOWER_TRADE"), a.displayName() + " is not a trading account");
        require(a.paperOf() == null, "choose the broker itself, not its paper twin");
        require(a.enabled(), a.displayName() + " is disabled");
        require(a.brokerType() == BrokerType.PAPER || tradingDay.paperTwin(a.id()) != null,
                a.displayName() + " has no paper twin (contact the admin)");
        return a;
    }

    private String name(long accountId) {
        return jdbc.queryForList("select display_name from acct.broker_account where id = ?", String.class, accountId)
                .stream().findFirst().orElse(String.valueOf(accountId));
    }

    private String names(List<Long> ids) {
        return String.join(", ", ids.stream().map(this::name).toList());
    }

    private String tenantName(long tenant) {
        return jdbc.queryForObject("select name from acct.tenant where id = ?", String.class, tenant);
    }

    private static Map<String, Object> detail(String... pairs) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            d.put(pairs[i], pairs[i + 1]);
        }
        return d;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw bad(message);
        }
    }
}
