package com.autotrade.exec.app;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.FollowerRisk;
import com.autotrade.execution.KillSwitches;
import com.autotrade.execution.LiveStrategies;

/**
 * {@code POST /api/execution/accounts/{id}/live}: the account owner's own LIVE switch (the Kite pilot, user 2 Nov 2026).
 * Nothing else makes an account LIVE. On: an OWNER or ADMIN of the account's tenant types the account's name, and the
 * account must be an enabled Zerodha follower in DRY_RUN, on a risk file with {@code live} limits, at one lot an entry
 * ({@code size_step_pct} 0), with today's Kite session, and holding no position (a simulated one would be lost). Off:
 * back to DRY_RUN, only while it holds no position (to stop entries with positions open, engage its kill switch; exits
 * are never blocked). Both are audited. The worker sees the change on its next turn and rebuilds the route.
 */
@RestController
class LiveSwitchController {

    record Switch(boolean on, String confirm) {
    }

    /**
     * No position and no order still working at the broker (code review 3 Nov 2026: an entry is a working order before
     * its position row exists, and an order of unknown outcome has none; switching the route under either would leave a
     * real order or position to the wrong venue).
     */
    private static final String NO_POSITION = " and not exists (select 1 from exec.position p where p.broker_account_id = "
            + "acct.broker_account.id and p.status <> 'CLOSED') and not exists (select 1 from exec.order_execution e "
            + "where e.broker_account_id = acct.broker_account.id and e.status in ('CREATED', 'SUBMITTED', 'ACKNOWLEDGED', "
            + "'OPEN', 'PARTIALLY_FILLED', 'CANCEL_PENDING', 'UNKNOWN', 'RECONCILIATION_REQUIRED') "
            + "and e.created_at > now() - interval '1 day')";

    private final Access access;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final KillSwitches audit;

    LiveSwitchController(Access access, DataSource dataSource) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.audit = new KillSwitches(dataSource);
    }

    @PostMapping("/api/execution/accounts/{id}/live")
    Map<String, Object> live(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                             @RequestBody Switch body) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        Access.require(user.manages(a.tenantId()), "only an OWNER or ADMIN may switch an account LIVE");
        // a member's workspace switches LIVE by the day (WorkspaceController), never an account's mode of its own (V29)
        require(!"DAILY_SWITCH".equals(jdbc.queryForObject("select trading_control from acct.tenant where id = ?",
                String.class, a.tenantId())), a.displayName() + " is in a workspace with a daily PAPER/LIVE switch: use "
                + "Today's switch (and the admin's LIVE eligibility)");
        Integer open = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? "
                + "and status <> 'CLOSED'", Integer.class, id);
        require(open == null || open == 0, a.displayName() + " holds " + open + " position(s): flatten them first"
                + (body.on() ? "" : ", or engage its kill switch to stop new entries"));
        Map<String, Object> detail = new LinkedHashMap<>();
        if (body.on()) {
            require(a.displayName().equals(body.confirm()), "type the account's name (" + a.displayName() + ") to confirm");
            require(a.enabled() && a.role().name().equals("FOLLOWER_TRADE"), a.displayName() + " is not an enabled follower");
            require(com.autotrade.execution.LiveBrokers.live(a.brokerType()), a.brokerType() + " has no LIVE order adapter yet");
            require(a.tradeMode() == TradeMode.DRY_RUN, a.displayName() + " is " + a.tradeMode() + ", not DRY_RUN");
            require(a.sizeStepPct() == 0, "the LIVE pilot trades one lot an entry: set the size step to 0 first");
            FollowerRisk risk;
            try {
                risk = FollowerRisk.load(Path.of(a.riskFile()));
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "risk file " + a.riskFile() + " not readable");
            }
            require(risk.live() != null && risk.orders() != null, "risk file " + a.riskFile()
                    + " has no live limits (follower-risk v7 or later)");
            Integer session = jdbc.queryForObject("select count(*) from acct.broker_session where broker_account_id = ? "
                    + "and status = 'ACTIVE' and expires_at > now()", Integer.class, id);
            require(session != null && session > 0, "no Kite session today: log in first");
            // the no-position check again in the same statement: an entry the worker opened since the check above
            int changed = jdbc.update("update acct.broker_account set trade_mode = 'LIVE', live_trading_enabled = true, "
                    + "updated_at = now() where id = ? and trade_mode = 'DRY_RUN' and not live_trading_enabled" + NO_POSITION,
                    id);
            require(changed == 1, a.displayName() + " changed, or has a position or a working order; reload and try again");
            detail.put("riskFile", a.riskFile());
            detail.put("maxLotsPerOrder", risk.live().maxLotsPerOrder());
            detail.put("maxOpenPositions", risk.live().maxOpenPositions());
            detail.put("strategies", new com.autotrade.execution.LiveStrategies(jdbc.getDataSource()).effective(id, risk));
            audit.audit(accounts.byId(id).orElseThrow(), user.subject(), "LIVE_ON", detail);
        } else {
            int changed = jdbc.update("update acct.broker_account set trade_mode = 'DRY_RUN', live_trading_enabled = false, "
                    + "updated_at = now() where id = ? and trade_mode = 'LIVE'" + NO_POSITION, id);
            require(changed == 1, a.displayName() + " is not LIVE, or has a position or an order still working at the "
                    + "broker; wait for it to end (or flatten) and try again");
            audit.audit(accounts.byId(id).orElseThrow(), user.subject(), "LIVE_OFF", detail);
        }
        BrokerAccount now = accounts.byId(id).orElseThrow();
        return Map.of("id", id, "account", now.displayName(), "mode", now.tradeMode().name(),
                "liveTradingEnabled", now.liveTradingEnabled());
    }

    /**
     * The account's LIVE strategies (V32, user 9 Nov 2026: the owner selects or deselects from every strategy the risk
     * file lets trade LIVE): {@code available} (the risk file's live.strategies), {@code defaults} (its
     * live.default_strategies), {@code chosen} (the owner's choice, null = the defaults), {@code effective} (what
     * trades LIVE now) and {@code canEdit} (the platform admin only).
     */
    @GetMapping("/api/execution/accounts/{id}/live-strategies")
    Map<String, Object> liveStrategies(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                       @PathVariable long id) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        return liveView(user, a);
    }

    /** {@code strategies}: the strategies to trade LIVE (an empty list: none); null with {@code reset}: the defaults. */
    record LiveChoice(java.util.List<String> strategies, Boolean reset) {
    }

    @PostMapping("/api/execution/accounts/{id}/live-strategies")
    Map<String, Object> chooseLiveStrategies(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                             @PathVariable long id, @RequestBody LiveChoice body) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        // the owner's decision (user 9 Nov 2026: "As the owner I should be able to select or deselect"): members and
        // tenant owners see it, only the platform admin changes it
        Access.require(user.admin(), "only the platform owner chooses LIVE strategies");
        require(a.role().name().equals("FOLLOWER_TRADE") && a.brokerType() != BrokerType.PAPER,
                a.displayName() + " is not a broker follower account");
        boolean reset = Boolean.TRUE.equals(body.reset());
        require(reset || body.strategies() != null, "strategies: the list to trade LIVE (or reset: true)");
        FollowerRisk risk = risk(a);
        LiveStrategies live = new LiveStrategies(jdbc.getDataSource());
        java.util.List<String> before = live.effective(a.id(), risk);
        try {
            live.choose(a.id(), risk, reset ? null : body.strategies());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", live.effective(a.id(), risk));
        detail.put("reset", reset);
        detail.put("riskFile", a.riskFile());
        audit.audit(a, user.subject(), "LIVE_STRATEGIES", detail);
        return liveView(user, a);
    }

    private Map<String, Object> liveView(Access.User user, BrokerAccount a) {
        FollowerRisk risk = risk(a);
        LiveStrategies live = new LiveStrategies(jdbc.getDataSource());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id());
        out.put("account", a.displayName());
        out.put("riskFile", Path.of(a.riskFile()).getFileName().toString());
        out.put("available", risk.live() == null ? java.util.List.of()
                : java.util.List.copyOf(new java.util.TreeSet<>(risk.live().strategies())));
        out.put("defaults", risk.live() == null ? java.util.List.of()
                : java.util.List.copyOf(new java.util.TreeSet<>(risk.live().defaults())));
        out.put("chosen", live.chosen(a.id()));
        out.put("global", live.global());
        out.put("source", live.source(a.id()).name());
        out.put("effective", live.effective(a.id(), risk));
        out.put("canEdit", user.admin() && risk.live() != null);
        return out;
    }

    // ------------------------------------------------------------------------------------------- the global list (V33)

    /**
     * The owner's global LIVE strategies (user 9 Nov 2026: "as admin ... select or deselect the live strategies at
     * global", standing until changed): {@code available} (every strategy some follower account's risk file lets trade
     * LIVE), {@code global} (the list; null = not set, each account's default), {@code accounts} (every broker follower
     * account the caller sees: where its list comes from and what trades LIVE on it now) and {@code canEdit}.
     */
    @GetMapping("/api/execution/live-strategies")
    Map<String, Object> globalLiveStrategies(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        return globalView(user);
    }

    @PostMapping("/api/execution/live-strategies")
    Map<String, Object> chooseGlobalLiveStrategies(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                                   @RequestBody LiveChoice body) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "only the platform owner chooses LIVE strategies");
        boolean reset = Boolean.TRUE.equals(body.reset());
        require(reset || body.strategies() != null, "strategies: the list to trade LIVE (or reset: true)");
        LiveStrategies live = new LiveStrategies(jdbc.getDataSource());
        java.util.List<String> before = live.global();
        try {
            live.chooseGlobal(reset ? null : body.strategies(), available(), user.subject());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before == null ? "default" : before);
        java.util.List<String> after = live.global();
        detail.put("after", after == null ? "default" : after);
        audit.audit(null, null, user.subject(), "LIVE_STRATEGIES_GLOBAL", detail);
        return globalView(user);
    }

    /** Every strategy that some enabled broker follower account's risk file lets trade LIVE. */
    private java.util.Set<String> available() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String file : jdbc.queryForList("select distinct risk_file from acct.broker_account where account_role = "
                + "'FOLLOWER_TRADE' and broker_type <> 'PAPER' and enabled", String.class)) {
            try {
                FollowerRisk risk = FollowerRisk.load(Path.of(file));
                if (risk.live() != null) {
                    out.addAll(risk.live().strategies());
                }
            } catch (RuntimeException e) {
                // an unreadable file offers nothing; the account's own view says so
            }
        }
        return out;
    }

    private Map<String, Object> globalView(Access.User user) {
        LiveStrategies live = new LiveStrategies(jdbc.getDataSource());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", java.util.List.copyOf(available()));
        out.put("global", live.global());
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (long tenant : user.tenants()) {
            for (BrokerAccount a : accounts.ofTenant(tenant)) {
                if (!a.role().name().equals("FOLLOWER_TRADE") || a.brokerType() == BrokerType.PAPER) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", a.id());
                r.put("account", a.displayName());
                r.put("mode", a.tradeMode().name());
                r.put("source", live.source(a.id()).name());
                java.util.List<String> effective;
                try {
                    effective = live.effective(a.id(), FollowerRisk.load(Path.of(a.riskFile())));
                } catch (RuntimeException e) {
                    effective = java.util.List.of();
                }
                r.put("effective", effective);
                rows.add(r);
            }
        }
        out.put("accounts", rows);
        out.put("canEdit", user.admin());
        return out;
    }

    private static FollowerRisk risk(BrokerAccount a) {
        try {
            return FollowerRisk.load(Path.of(a.riskFile()));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "risk file " + a.riskFile() + " not readable");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
