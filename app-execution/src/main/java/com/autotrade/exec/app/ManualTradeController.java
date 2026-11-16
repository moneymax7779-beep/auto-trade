package com.autotrade.exec.app;

import java.nio.file.Path;
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

import com.autotrade.account.AccountRole;
import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.FollowerRisk;
import com.autotrade.execution.KillSwitches;
import com.autotrade.execution.ManualOrders;

/**
 * The trader's manual BUY CE / BUY PE / EXIT (user, 9 Nov 2026), on follower accounts only ({@link ManualOrders} says
 * what is bought and how):
 * <ul>
 *   <li>{@code GET /api/execution/manual/accounts}: the follower accounts the caller may buy on, with mode and whether
 *       their risk file lets {@code manual} trade LIVE;</li>
 *   <li>{@code GET /api/execution/accounts/{id}/manual/quote?underlying=NIFTY&side=CE}: the contract a buy on the
 *       account would take now (the confirmation shows it; needs a role that may buy there);</li>
 *   <li>{@code POST /api/execution/accounts/{id}/manual/buy} with {@code {"underlying", "side", "symbol", "confirm"}}:
 *       {@code confirm} "BUY" ("BUY LIVE" on a LIVE account), {@code symbol} the contract the trader confirmed (refused
 *       when the best ATM moved since);</li>
 *   <li>{@code POST /api/execution/accounts/{id}/manual/exit} with {@code {"underlying", "confirm": "EXIT"}}: cancels a
 *       manual buy not yet sent and sells the index's manual position (no underlying: every index);</li>
 *   <li>{@code GET /api/execution/accounts/{id}/manual}: today's manual buys with their order states and positions.</li>
 * </ul>
 * Buying needs a role that may act; EXIT is open to SUPPORT as the per-position Exit is ({@link Access.User#emergency}).
 * A refusal is a 409 with the reason.
 */
@RestController
class ManualTradeController {

    private final Access access;
    private final AccountStore accounts;
    private final KillSwitches kills;
    private final ExecutionService service;
    private final JdbcTemplate jdbc;

    ManualTradeController(Access access, DataSource dataSource, ExecutionService service) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.kills = new KillSwitches(dataSource);
        this.service = service;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @GetMapping("/api/execution/manual/accounts")
    List<Map<String, Object>> manualAccounts(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        Access.User user = access.user(subject);
        List<Map<String, Object>> out = new ArrayList<>();
        for (long tenant : user.tenants()) {
            for (BrokerAccount a : accounts.ofTenant(tenant)) {
                if (a.role() != AccountRole.FOLLOWER_TRADE || !a.enabled()) {
                    continue;
                }
                out.add(view(user, a));
            }
        }
        return out;
    }

    @GetMapping("/api/execution/accounts/{id}/manual/quote")
    Map<String, Object> quote(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                              @RequestParam String underlying, @RequestParam String side) {
        Access.User user = access.user(subject);
        BrokerAccount a = account(user, id, false);
        try {
            return pick(service.manualQuote(a.id(), underlying, side));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    record Buy(String underlying, String side, String symbol, String confirm) {
    }

    @PostMapping("/api/execution/accounts/{id}/manual/buy")
    Map<String, Object> buy(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                            @RequestBody Buy b) {
        Access.User user = access.user(subject);
        BrokerAccount a = account(user, id, false);
        String expected = a.tradeMode() == TradeMode.LIVE ? "BUY LIVE" : "BUY";
        Access.require(expected.equals(b.confirm()), "confirm with \"" + expected + "\"");
        try {
            ManualOrders.Placed placed = service.manualBuy(a.id(), b.underlying(), b.side(), b.symbol(), user.subject());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("account", a.displayName());
            out.put("mode", placed.mode());
            out.put("intentId", placed.intentId());
            out.put("quantity", placed.quantity());
            out.putAll(pick(placed.pick()));
            return out;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    record Exit(String underlying, String confirm) {
    }

    @PostMapping("/api/execution/accounts/{id}/manual/exit")
    Map<String, Object> exit(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                             @RequestBody Exit e) throws Exception {
        Access.User user = access.user(subject);
        Access.require("EXIT".equals(e.confirm()), "confirm with \"EXIT\"");
        BrokerAccount a = account(user, id, true);
        try {
            return Map.of("account", a.displayName(), "result", service.manualExit(a.id(), e.underlying(),
                    user.subject()));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @GetMapping("/api/execution/accounts/{id}/manual")
    Map<String, Object> today(@RequestHeader(value = Access.HEADER, required = false) String subject,
                              @PathVariable long id) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account"));
        Map<String, Object> out = view(user, a);
        out.put("killSwitch", kills.engaged(a, ManualOrders.STRATEGY, null));
        out.put("orders", service.manualToday(a.id()));
        return out;
    }

    private Map<String, Object> view(Access.User user, BrokerAccount a) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", a.id());
        v.put("name", a.displayName());
        v.put("broker", a.brokerType().name());
        v.put("mode", a.tradeMode().name());
        v.put("liveSwitch", a.liveTradingEnabled());
        v.put("canBuy", user.mayAct(a.tenantId()));
        v.put("canExit", user.emergency(a.tenantId()));
        String why = null;
        if (a.tradeMode() == TradeMode.LIVE) {
            try {
                FollowerRisk risk = FollowerRisk.load(Path.of(a.riskFile()));
                if (risk.live() == null || !risk.live().strategies().contains(ManualOrders.STRATEGY)) {
                    why = "its risk file " + Path.of(a.riskFile()).getFileName() + " does not list manual as a LIVE "
                            + "strategy (follower-risk v8 does)";
                }
            } catch (RuntimeException e) {
                why = "its risk file cannot be read: " + e.getMessage();
            }
            if (!a.liveTradingEnabled()) {
                why = "its LIVE switch is off";
            }
        }
        v.put("liveBlocked", why);
        // a DAILY_SWITCH workspace's account that does not take entries now: shown before the trader clicks
        v.put("blocked", new ManualOrders(jdbc.getDataSource()).notEntryTarget(a, service.day()));
        v.put("lots", ManualOrders.LOTS);
        v.put("open", jdbc.queryForList("select id, underlying, option_side side, symbol, quantity_open quantity, "
                + "average_entry entry from exec.position where broker_account_id = ? and strategy_id = ? and status <> "
                + "'CLOSED' and quantity_open > 0 order by opened_at", a.id(), ManualOrders.STRATEGY));
        return v;
    }

    private BrokerAccount account(Access.User user, long id, boolean emergency) {
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + id));
        Access.require(emergency ? user.emergency(a.tenantId()) : user.mayAct(a.tenantId()), user.support(a.tenantId())
                ? "SUPPORT needs the member's grant for this" : "read-only role for this account");
        return a;
    }

    private static Map<String, Object> pick(ManualOrders.Pick p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("underlying", p.underlying());
        out.put("side", p.side());
        out.put("spot", Math.round(p.spot() * 100) / 100.0);
        out.put("symbol", p.chosen().symbol());
        out.put("strike", p.chosen().strike());
        out.put("expiry", p.chosen().expiry().toString());
        out.put("lotSize", p.chosen().lotSize());
        out.put("bid", p.chosen().bid());
        out.put("ask", p.chosen().ask());
        out.put("spreadPct", Double.isNaN(p.chosen().spreadPct()) ? null : p.chosen().spreadPct());
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ManualOrders.Candidate c : p.candidates()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", c.symbol());
            m.put("strike", c.strike());
            m.put("bid", c.bid());
            m.put("ask", c.ask());
            m.put("spreadPct", Double.isNaN(c.spreadPct()) ? null : c.spreadPct());
            m.put("refusal", c.refusal());
            candidates.add(m);
        }
        out.put("candidates", candidates);
        return out;
    }
}
