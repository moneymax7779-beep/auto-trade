package com.autotrade.trading;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chart orders in a paced replay (user, 15 Nov 2026: "in the replay i should be able to draw and run the replay"):
 * the live chart-order API's shape ({@code app-execution ChartOrderController}) on the replay's own paper account, so the
 * same panel draws on a replay's chart. Operator only (every /api path but the members' is, SecurityConfig).
 * 409 when no paced replay runs, 400 with the reason when a line or field is refused.
 */
@RestController
@RequestMapping("/api/replay")
class ReplayChartOrderController {

    private final SessionRunner runner;

    ReplayChartOrderController(SessionRunner runner) {
        this.runner = runner;
    }

    /** The panel's view: the replay's one paper account, whether lines can be drawn, the orders. */
    @GetMapping("/chart-orders")
    ResponseEntity<Map<String, Object>> list() {
        TradingSession session = runner.current();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("account", "replay");
        view.put("mode", "PAPER_REPLAY");
        String why = unavailable(session);
        view.put("canArm", why == null);
        view.put("canExit", why == null);
        view.put("unavailable", why);
        view.put("orders", why == null || session != null && session.chartOrdersOn() ? safeOrders(session) : List.of());
        return ResponseEntity.ok(view);
    }

    record Arm(String underlying, String side, Double entry, Double stop, Double target, Integer lots, String validUntil,
               String expectKind) {
    }

    @PostMapping("/chart-orders")
    ResponseEntity<?> arm(@RequestBody Arm a) {
        return call(s -> s.chartArm(a.underlying(), a.side(), a.entry(), a.stop(), a.target(), a.lots(), a.validUntil(),
                a.expectKind()));
    }

    record Move(Integer version, Double entry, Double stop, Double target) {
    }

    @PostMapping("/chart-orders/{orderId}/move")
    ResponseEntity<?> move(@PathVariable long orderId, @RequestBody Move m) {
        return call(s -> s.chartMove(orderId, m.version(), m.entry(), m.stop(), m.target()));
    }

    @PostMapping("/chart-orders/{orderId}/cancel")
    ResponseEntity<?> cancel(@PathVariable long orderId) {
        return call(s -> s.chartCancel(orderId));
    }

    record Exit(String confirm) {
    }

    @PostMapping("/chart-orders/{orderId}/exit")
    ResponseEntity<?> exit(@PathVariable long orderId, @RequestBody(required = false) Exit e) {
        if (e == null || !"EXIT".equals(e.confirm())) {
            return ResponseEntity.badRequest().body(Map.of("error", "confirm: EXIT"));
        }
        return call(s -> s.chartExit(orderId));
    }

    /** The ATM option an order would buy now (the ticket's estimate), on the live API's path shape. */
    @GetMapping("/manual/quote")
    ResponseEntity<?> quote(@RequestParam String underlying, @RequestParam String side) {
        return call(s -> s.chartQuote(underlying, side));
    }

    private ResponseEntity<?> call(java.util.function.Function<TradingSession, Object> action) {
        TradingSession session = runner.current();
        String why = unavailable(session);
        if (why != null) {
            return ResponseEntity.status(409).body(Map.of("error", why));
        }
        try {
            return ResponseEntity.ok(action.apply(session));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Why lines cannot be drawn now, null when they can: a paced replay that is still running. */
    private String unavailable(TradingSession session) {
        if (session == null || !session.chartOrdersOn()) {
            return "no replay running";
        }
        if (!"RUNNING".equals(session.runState())) {
            return "the replay is " + session.runState();
        }
        if (!runner.replayControl().attached()) {
            return "this replay runs unpaced (speed 0): start it with a speed to draw on it";
        }
        return null;
    }

    private static List<Map<String, Object>> safeOrders(TradingSession session) {
        Supplier<List<Map<String, Object>>> read = session::chartOrders;
        try {
            return read.get();
        } catch (IllegalStateException e) {
            return List.of();
        }
    }
}
