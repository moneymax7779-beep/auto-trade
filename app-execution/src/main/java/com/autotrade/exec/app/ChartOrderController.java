package com.autotrade.exec.app;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.execution.ChartOrders;

/**
 * Chart orders (user, 14 Nov 2026; plan docs/plans/2026-11-14-chart-line-trading-plan.md, P0): the trader's entry, stop
 * and target lines drawn on the index chart for one follower account; the live session's chart-order engine watches
 * them. POST only (the UI's proxy forwards GET and POST):
 * <ul>
 *   <li>{@code GET /api/execution/accounts/{id}/chart-orders}: today's chart orders of the account, with their events;</li>
 *   <li>{@code POST /api/execution/accounts/{id}/chart-orders} with {@code {"underlying", "side", "entry", "stop",
 *       "target", "lots", "validUntil"}} (index prices; validUntil "HH:mm", optional): arms one;</li>
 *   <li>{@code POST .../chart-orders/{orderId}/move} with {@code {"version", "entry", "stop", "target"}} (any of the
 *       three; version the one the screen showed): moves its lines;</li>
 *   <li>{@code POST .../chart-orders/{orderId}/cancel}: cancels a waiting one;</li>
 *   <li>{@code POST .../chart-orders/{orderId}/exit} with {@code {"confirm": "EXIT"}}: Exit now (a waiting order is
 *       cancelled; otherwise the manual EXIT of its index: a buy not yet run is cancelled, the position is sold).</li>
 * </ul>
 * Arming, moving and cancelling need a role that may act on the account; Exit now is open to SUPPORT, as every Exit is.
 * A refusal is a 409 with the reason.
 */
@RestController
class ChartOrderController {

    private final Access access;
    private final AccountStore accounts;
    private final ChartOrders orders;
    private final ExecutionService service;

    ChartOrderController(Access access, DataSource dataSource, ExecutionService service) {
        this.access = access;
        this.accounts = new AccountStore(dataSource);
        this.orders = new ChartOrders(dataSource);
        this.service = service;
    }

    @GetMapping("/api/execution/accounts/{id}/chart-orders")
    Map<String, Object> today(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id) {
        Access.User user = access.user(subject);
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", a.displayName());
        out.put("mode", a.tradeMode().name());
        out.put("canArm", user.mayAct(a.tenantId()));
        out.put("canExit", user.emergency(a.tenantId()));
        out.put("orders", orders.today(a.id(), service.day()));
        // review 16 Nov 2026 (D6): whether an engine watches the day's chart orders (arm and move are refused if not)
        ChartOrders.Engine engine = orders.engine(service.day());
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("running", engine.running());
        e.put("lastSeen", engine.lastSeen() == null ? null : engine.lastSeen().toString());
        out.put("engine", e);
        return out;
    }

    record Arm(String underlying, String side, Double entry, Double stop, Double target, Integer lots, String validUntil,
               String expectKind) {
    }

    @PostMapping("/api/execution/accounts/{id}/chart-orders")
    Map<String, Object> arm(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                            @RequestBody Arm b) {
        Access.User user = access.user(subject);
        BrokerAccount a = account(user, id, false);
        return refusals(() -> {
            if (b.entry() == null || b.stop() == null || b.target() == null) {
                throw new IllegalArgumentException("entry, stop and target are needed");
            }
            LocalTime until = b.validUntil() == null || b.validUntil().isBlank() ? null : LocalTime.parse(b.validUntil());
            ChartOrders.Order o = orders.arm(a, service.manualGroup(a.id()), new ChartOrders.Arm(b.underlying(), b.side(),
                    b.entry(), b.stop(), b.target(), b.lots() == null ? 1 : b.lots(), until, null, null, b.expectKind()),
                    user.subject(),
                    service.day());
            return ChartOrders.view(o);
        });
    }

    record Move(Integer version, Double entry, Double stop, Double target) {
    }

    @PostMapping("/api/execution/accounts/{id}/chart-orders/{orderId}/move")
    Map<String, Object> move(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                             @PathVariable long orderId, @RequestBody Move b) {
        Access.User user = access.user(subject);
        BrokerAccount a = account(user, id, false);
        Access.require(b.version() != null, "the version the screen showed is needed");
        return refusals(() -> ChartOrders.view(orders.move(a, orderId, b.version(), b.entry(), b.stop(), b.target(),
                user.subject(), service.day())));
    }

    @PostMapping("/api/execution/accounts/{id}/chart-orders/{orderId}/cancel")
    Map<String, Object> cancel(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                               @PathVariable long orderId) {
        Access.User user = access.user(subject);
        BrokerAccount a = account(user, id, false);
        return refusals(() -> ChartOrders.view(orders.cancel(a, orderId, user.subject())));
    }

    record Exit(String confirm) {
    }

    @PostMapping("/api/execution/accounts/{id}/chart-orders/{orderId}/exit")
    Map<String, Object> exit(@RequestHeader(value = Access.HEADER, required = false) String subject, @PathVariable long id,
                             @PathVariable long orderId, @RequestBody Exit b) {
        Access.User user = access.user(subject);
        Access.require(b != null && "EXIT".equals(b.confirm()), "confirm with \"EXIT\"");
        BrokerAccount a = account(user, id, true);
        return refusals(() -> {
            orders.byId(orderId).filter(x -> x.accountId() == a.id())
                    .orElseThrow(() -> new IllegalArgumentException("no chart order " + orderId + " on " + a.displayName()));
            // code review 15 Nov 2026: a waiting order is cancelled only if it still waits (the engine may have fired a
            // moment ago, then its buy is cancelled or sold below); a finished order sells nothing (its index may hold
            // another manual position); the order's status is left to the engine, which keeps its stop watched until the
            // position is seen closed
            if (orders.exitNow(a, orderId, user.subject())) {
                Map<String, Object> out = ChartOrders.view(orders.byId(orderId).orElseThrow());
                out.put("result", List.of("cancelled before the entry"));
                return out;
            }
            ChartOrders.Order o = orders.byId(orderId).orElseThrow();
            List<String> result = service.manualExit(a.id(), o.underlying(), user.subject());
            Map<String, Object> out = ChartOrders.view(orders.exitRan(a, orderId, user.subject(), result));
            out.put("result", result);
            return out;
        });
    }

    private interface Work {
        Map<String, Object> run() throws Exception;
    }

    private static Map<String, Object> refusals(Work work) {
        try {
            return work.run();
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BrokerAccount account(Access.User user, long id, boolean emergency) {
        BrokerAccount a = accounts.byId(id).filter(x -> user.sees(x.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no account " + id));
        Access.require(emergency ? user.emergency(a.tenantId()) : user.mayAct(a.tenantId()), user.support(a.tenantId())
                ? "SUPPORT needs the member's grant for this" : "read-only role for this account");
        return a;
    }
}
