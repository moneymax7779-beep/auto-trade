package com.autotrade.exec.app;

import com.autotrade.account.AccountRole;
import com.autotrade.execution.DeskOrders;
import com.autotrade.execution.ManualOrders;
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

/**
 * The owner's desk (plan 2026-11-23 feature B, PAPER only; user 23 Nov 2026: "start with desk trades PAPER only"): one trade
 * sent at once to the paper books of all or chosen accounts ({@link DeskOrders}). The platform admin's only.
 * <ul>
 *   <li>{@code GET /api/execution/desk/accounts}: every follower account the desk can send to, with its paper book</li>
 *   <li>{@code GET /api/execution/desk/quote}: the three strikes around ATM, with their book</li>
 *   <li>{@code POST /api/execution/desk/preview}: what a send would do now, account by account</li>
 *   <li>{@code POST /api/execution/desk/orders}: the send ("SEND n ORDERS" typed, a key per confirmation)</li>
 *   <li>{@code GET /api/execution/desk/orders}: today's desk orders with each account's position and P&amp;L</li>
 *   <li>{@code POST /api/execution/desk/orders/{id}/exit}: the order's group exit (a reason, "EXIT" typed)</li>
 * </ul>
 */
@RestController
class DeskController {

    private final Access access;
    private final ExecutionService service;
    private final JdbcTemplate jdbc;

    DeskController(Access access, ExecutionService service, DataSource dataSource) {
        this.access = access;
        this.service = service;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    record Order(String underlying, String side, String symbol, String sizing, Double value, Double stopPct, Double targetPct,
                 String reason, List<Long> accounts, String key, String confirm) {

        DeskOrders.Spec spec() {
            DeskOrders.Sizing s;
            try {
                s = DeskOrders.Sizing.valueOf(String.valueOf(sizing).trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sizing: PCT_EQUITY or FIXED_LOTS");
            }
            return new DeskOrders.Spec(underlying, side, symbol, s, value == null ? 0 : value,
                    stopPct == null ? ManualOrders.STOP_PCT : stopPct, targetPct, reason);
        }
    }

    record Exit(String reason, String confirm) {
    }

    private Access.User admin(String subject) {
        Access.User user = access.user(subject);
        Access.require(user.admin(), "the desk is the platform admin's");
        return user;
    }

    /** Every follower account (any workspace) with where a desk trade would go: itself if paper, else its paper twin. */
    @GetMapping("/api/execution/desk/accounts")
    List<Map<String, Object>> accounts(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        admin(subject);
        return jdbc.queryForList("select a.id, a.display_name account, a.broker_type broker, a.trade_mode mode, t.id tenant_id, "
                + "t.name tenant, case when a.broker_type = 'PAPER' then a.id else tw.id end paper_id, "
                + "case when a.broker_type = 'PAPER' then a.display_name else tw.display_name end paper_account, "
                + "a.allocated_capital from acct.broker_account a join acct.tenant t on t.id = a.tenant_id "
                + "left join acct.broker_account tw on tw.paper_of_account_id = a.id where a.account_role = ? and a.enabled "
                + "and a.mirror_of is null and a.paper_of_account_id is null order by t.name, a.display_name",
                AccountRole.FOLLOWER_TRADE.name());
    }

    @GetMapping("/api/execution/desk/quote")
    Map<String, Object> quote(@RequestHeader(value = Access.HEADER, required = false) String subject,
                              @RequestParam String underlying, @RequestParam String side) {
        admin(subject);
        try {
            ManualOrders.Pick p = service.deskQuote(underlying, side);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("underlying", p.underlying());
            out.put("side", p.side());
            out.put("spot", p.spot());
            out.put("spotAt", p.spotAt().toString());
            out.put("best", p.chosen().symbol());
            out.put("candidates", p.candidates());
            return out;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/api/execution/desk/preview")
    DeskOrders.Preview preview(@RequestHeader(value = Access.HEADER, required = false) String subject, @RequestBody Order o) {
        admin(subject);
        try {
            return service.deskPreview(o.spec(), o.accounts());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/api/execution/desk/orders")
    DeskOrders.Placed send(@RequestHeader(value = Access.HEADER, required = false) String subject, @RequestBody Order o) {
        Access.User user = admin(subject);
        int n = o.accounts() == null ? 0 : (int) o.accounts().stream().distinct().count();
        Access.require(("SEND " + n + " ORDERS").equals(o.confirm()), "confirm with \"SEND " + n + " ORDERS\" (the number of "
                + "accounts chosen)");
        try {
            return service.deskPlace(o.spec(), o.accounts(), user.subject(), o.key());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/api/execution/desk/orders")
    List<Map<String, Object>> orders(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        admin(subject);
        return service.deskOrders();
    }

    @PostMapping("/api/execution/desk/orders/{id}/exit")
    Map<String, Object> exit(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                             @RequestBody Exit e) {
        Access.User user = admin(subject);
        Access.require("EXIT".equals(e == null || e.confirm() == null ? null : e.confirm().trim().toUpperCase(
                java.util.Locale.ROOT)), "confirm with \"EXIT\"");
        try {
            return Map.of("order", id, "lines", service.deskExit(id, user.subject(), e.reason()));
        } catch (IllegalArgumentException x) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, x.getMessage());
        }
    }
}
