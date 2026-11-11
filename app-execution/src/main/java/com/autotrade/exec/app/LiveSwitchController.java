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
 * ({@code size_step_pct} 0) unless the file sizes LIVE from the allocation (v11: live.max_lots_per_order above 1), with
 * today's Kite session, and holding no position (a simulated one would be lost). Off:
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
    private final org.springframework.transaction.support.TransactionTemplate tx;

    LiveSwitchController(Access access, DataSource dataSource) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
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
            FollowerRisk risk;
            try {
                risk = FollowerRisk.load(Path.of(a.riskFile()));
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "risk file " + a.riskFile() + " not readable");
            }
            require(risk.live() != null && risk.orders() != null, "risk file " + a.riskFile()
                    + " has no live limits (follower-risk v7 or later)");
            require(a.sizeStepPct() == 0 || risk.live().maxLotsPerOrder() > 1, "the risk file " + a.riskFile()
                    + " trades one lot an entry (live.max_lots_per_order 1): set the size step to 0, or move the account "
                    + "to a risk file that sizes LIVE from the allocation (follower-risk v11)");
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
        // user 11 Nov 2026: "only if choose LIVE - Follower/Demo accounts should switch from PAPER/LIVE": whoever manages
        // the account (its tenant's OWNER or ADMIN, and the platform owner) switches the owner's LIVE strategies between
        // PAPER and LIVE for it; what the owner has on PAPER or OFF cannot be chosen (LiveStrategies.choose refuses it)
        Access.require(user.admin() || user.manages(a.tenantId()),
                "only the account's owner or the platform owner chooses its LIVE strategies");
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
        // what the account may switch between PAPER and LIVE: the owner's LIVE strategies within its risk file; the
        // owner's PAPER ones are listed apart, OFF ones not at all (user 11 Nov 2026)
        java.util.List<String> choosable = live.choosable(risk);
        out.put("off", java.util.List.copyOf(live.off()));
        out.put("choosable", choosable);
        java.util.Set<String> paperOnly = new java.util.TreeSet<>(risk.live() == null ? java.util.Set.of()
                : risk.live().strategies());
        paperOnly.removeAll(choosable);
        paperOnly.removeAll(live.off());
        out.put("paperOnly", java.util.List.copyOf(paperOnly));
        out.put("canEdit", (user.admin() || user.manages(a.tenantId())) && risk.live() != null);
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
        // the owner's strategy set-up is not for members (user 10 Nov 2026: members see the chart and trades only); a
        // member still sees what trades LIVE on their own account through its own live-strategies view
        Access.require(user.admin(), "only the platform owner sees the strategy switches");
        return globalView(user);
    }

    /**
     * {@code strategies}: the global LIVE list (empty: none); {@code reset}: back to each account's default; {@code off}:
     * the whole OFF list (V35, empty: every strategy on). Any of them may be sent alone; a strategy cannot be both LIVE
     * and OFF.
     */
    record GlobalChoice(java.util.List<String> strategies, Boolean reset, java.util.List<String> off) {
    }

    @PostMapping("/api/execution/live-strategies")
    Map<String, Object> chooseGlobalLiveStrategies(@RequestHeader(value = Access.HEADER, required = false) String subject,
                                                   @RequestBody GlobalChoice body) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "only the platform owner chooses PAPER and LIVE strategies");
        boolean reset = Boolean.TRUE.equals(body.reset());
        java.util.Set<String> strategies = clean(body.strategies());
        java.util.Set<String> off = clean(body.off());
        boolean liveChange = reset || strategies != null;
        require(liveChange || off != null,
                "strategies: the list to trade LIVE (or reset: true), and/or off: the strategies to turn OFF");
        require(!(reset && strategies != null), "send strategies or reset, not both");
        LiveStrategies live = new LiveStrategies(jdbc.getDataSource());
        java.util.Set<String> beforeOff = live.off();
        if (strategies != null) {
            java.util.Set<String> both = new java.util.TreeSet<>(strategies);
            both.retainAll(off != null ? off : beforeOff);
            require(both.isEmpty(), "a strategy cannot be both LIVE and OFF: " + both);
        }
        java.util.Set<String> available = available();
        // what may be OFF: what runs, what may trade LIVE, and whatever is OFF already (so a strategy since removed from the
        // deployed set and the risk files can still be kept OFF or switched back on)
        java.util.Set<String> known = new java.util.TreeSet<>(available);
        known.addAll(running());
        known.addAll(beforeOff);
        if (strategies != null) {
            java.util.Set<String> unknown = new java.util.TreeSet<>(strategies);
            unknown.removeAll(available);
            require(unknown.isEmpty(), "not a LIVE strategy of any account's risk file: " + unknown
                    + " (may choose from " + available + ")");
        }
        if (off != null) {
            java.util.Set<String> unknown = new java.util.TreeSet<>(off);
            unknown.removeAll(known);
            require(unknown.isEmpty(), "not a running or LIVE-eligible strategy: " + unknown + " (may choose from "
                    + known + ")");
        }
        java.util.List<String> beforeLive = live.global();
        // every write and its audit rows in one transaction: a failure part-way changes nothing
        try {
            tx.executeWithoutResult(status -> write(user, live, reset, strategies, off, liveChange, available, known,
                    beforeOff, beforeLive));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return globalView(user);
    }

    private void write(Access.User user, LiveStrategies live, boolean reset, java.util.Set<String> strategies,
                       java.util.Set<String> off, boolean liveChange, java.util.Set<String> available,
                       java.util.Set<String> known, java.util.Set<String> beforeOff, java.util.List<String> beforeLive) {
        {
            java.util.List<Long> droppedFrom = java.util.List.of();
            if (liveChange) {
                live.chooseGlobal(reset ? null : strategies, available, user.subject());
            }
            if (off != null) {
                live.chooseOff(off, known, user.subject());
                // turning a strategy OFF also takes it out of every LIVE list, so switching it back on makes it PAPER,
                // never real money again without a fresh LIVE choice
                java.util.Set<String> newlyOff = new java.util.TreeSet<>(off);
                newlyOff.removeAll(beforeOff);
                if (!newlyOff.isEmpty()) {
                    java.util.Map<Long, java.util.List<String>> before = new java.util.HashMap<>();
                    for (Long id : jdbc.queryForList("select id from acct.broker_account where live_strategies && "
                            + "?::text[]", Long.class, "{" + String.join(",", newlyOff) + "}")) {
                        before.put(id, live.chosen(id));
                    }
                    droppedFrom = live.dropFromLiveLists(newlyOff, user.subject());
                    for (long id : droppedFrom) {                // each account's own history shows why its list changed
                        accounts.byId(id).ifPresent(a -> {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("before", before.get(id));
                            detail.put("after", live.chosen(id));
                            detail.put("reason", "turned OFF for every account: " + newlyOff);
                            audit.audit(a, user.subject(), "LIVE_STRATEGIES", detail);
                        });
                    }
                }
            }
            java.util.List<String> afterLive = live.global();
            if (liveChange || !java.util.Objects.equals(beforeLive, afterLive)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("before", beforeLive == null ? "default" : beforeLive);
                detail.put("after", afterLive == null ? "default" : afterLive);
                audit.audit(null, null, user.subject(), "LIVE_STRATEGIES_GLOBAL", detail);
            }
            java.util.Set<String> afterOff = live.off();
            if (off != null && !beforeOff.equals(afterOff)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("before", java.util.List.copyOf(beforeOff));
                detail.put("after", java.util.List.copyOf(afterOff));
                detail.put("accountListsChanged", droppedFrom);
                audit.audit(null, null, user.subject(), "STRATEGIES_OFF", detail);
            }
        }
    }

    /** The names sent, trimmed, without blanks or nulls; null when the list itself was not sent. */
    private static java.util.Set<String> clean(java.util.List<String> names) {
        if (names == null) {
            return null;
        }
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                out.add(n.trim());
            }
        }
        return out;
    }

    /**
     * The strategies the live PAPER sessions run: every primary PAPER_LIVE session (not SIGNAL-*, not SHADOW-*) of the
     * latest day with one (trade.session.strategy_id is "a+b+c"). Before the day's session starts, it is the last day's
     * set; a strategy added by a deploy shows from its first live session.
     */
    private java.util.Set<String> running() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String ids : jdbc.queryForList("select strategy_id from trade.session where mode = 'PAPER_LIVE' and account "
                + "not like 'SIGNAL-%' and account not like 'SHADOW-%' and session_date = (select max(session_date) from "
                + "trade.session where mode = 'PAPER_LIVE' and account not like 'SIGNAL-%' and account not like 'SHADOW-%')",
                String.class)) {
            for (String id : ids == null ? new String[0] : ids.split("\\+")) {
                if (!id.isBlank()) {
                    out.add(id.trim());
                }
            }
        }
        return out;
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
        out.put("running", java.util.List.copyOf(running()));
        out.put("global", live.global());
        out.put("off", java.util.List.copyOf(live.off()));
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
