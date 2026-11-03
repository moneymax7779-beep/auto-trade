package com.autotrade.exec.app;

import java.nio.file.Path;
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
import com.autotrade.account.BrokerType;
import com.autotrade.account.TradeMode;
import com.autotrade.execution.FollowerRisk;
import com.autotrade.execution.KillSwitches;

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
        Integer open = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? "
                + "and status <> 'CLOSED'", Integer.class, id);
        require(open == null || open == 0, a.displayName() + " holds " + open + " position(s): flatten them first"
                + (body.on() ? "" : ", or engage its kill switch to stop new entries"));
        Map<String, Object> detail = new LinkedHashMap<>();
        if (body.on()) {
            require(a.displayName().equals(body.confirm()), "type the account's name (" + a.displayName() + ") to confirm");
            require(a.enabled() && a.role().name().equals("FOLLOWER_TRADE"), a.displayName() + " is not an enabled follower");
            require(a.brokerType() == BrokerType.ZERODHA, "LIVE is built for Zerodha only");
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
            detail.put("strategies", new java.util.TreeSet<>(risk.live().strategies()));
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

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
