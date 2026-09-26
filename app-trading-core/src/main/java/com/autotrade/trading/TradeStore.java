package com.autotrade.trading;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderUpdate;
import com.autotrade.oms.ManagedPosition;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.SideView;

import tools.jackson.databind.json.JsonMapper;

/** Persists everything a trading session does (trade.* and ops.kill_switch_event). */
final class TradeStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    TradeStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    long startSession(String account, String mode, LocalDate session, String feed, String strategyId,
                      String strategyHash, Map<String, String> configs, String codeVersion) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("insert into trade.session (account, mode, session_date, feed, "
                    + "strategy_id, strategy_hash, configs, code_version, status) values (?,?,?,?,?,?,?::jsonb,?,'RUNNING')",
                    new String[] {"id"});
            statement.setString(1, account);
            statement.setString(2, mode);
            statement.setObject(3, session);
            statement.setString(4, feed);
            statement.setString(5, strategyId);
            statement.setString(6, strategyHash);
            statement.setString(7, JSON.writeValueAsString(configs));
            statement.setString(8, codeVersion);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    void endSession(long id, String status, Map<String, Object> summary, String error) {
        jdbc.update("update trade.session set status = ?, ended_at = now(), summary = ?::jsonb, error = ? where id = ?",
                status, JSON.writeValueAsString(summary), error, id);
    }

    void order(long session, ManagedPosition position, String role, OrderRequest request, Instant at) {
        jdbc.update("insert into trade.orders (client_order_id, session_id, underlying, option_side, role, order_side, "
                        + "order_type, symbol, quantity, limit_price, trigger_price, sent_at) values (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "on conflict (client_order_id) do nothing",
                request.clientOrderId(), session, position.underlying(), position.side().name(), role,
                request.side().name(), request.type().name(), request.symbol(), request.quantity(), request.limitPrice(),
                request.triggerPrice() > 0 ? request.triggerPrice() : null, ts(at));
    }

    void orderEvent(OrderUpdate update) {
        jdbc.update("insert into trade.order_event (client_order_id, status, filled, average_price, last_quantity, "
                        + "last_price, at, message) values (?,?,?,?,?,?,?,?)",
                update.clientOrderId(), update.status().name(), update.filledQuantity(),
                update.averagePrice() > 0 ? update.averagePrice() : null, update.lastFillQuantity(),
                update.lastFillPrice() > 0 ? update.lastFillPrice() : null, ts(update.time()), update.message());
    }

    void position(long session, ManagedPosition position) {
        jdbc.update("insert into trade.position (session_id, underlying, option_side, symbol, opened_at, closed_at, "
                        + "stages, exit_reason, realised, costs, net) values (?,?,?,?,?,?,?,?,?,?,?)",
                session, position.underlying(), position.side().name(), position.contract().symbol(),
                ts(position.openedAt()), ts(position.closedAt()),
                position.stages().toArray(new String[0]), position.exitReason(), position.realised(), position.costs(),
                position.net());
    }

    void decision(long session, Decision decision, double spot) {
        StringBuilder orders = new StringBuilder();
        for (OrderIntent order : decision.orders()) {
            orders.append(orders.isEmpty() ? "" : " ").append(order.action()).append(':').append(order.side())
                    .append(':').append(order.lots()).append(':').append(order.reason());
        }
        jdbc.update("insert into trade.decision (session_id, underlying, snap_time, spot, ce_stage, pe_stage, ce_scores, "
                        + "pe_scores, state, orders) values (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?) "
                        + "on conflict do nothing",
                session, decision.underlying(), ts(decision.time()), Double.isFinite(spot) ? spot : null,
                decision.ce().stage().name(), decision.pe().stage().name(), scores(decision.ce()), scores(decision.pe()),
                JSON.writeValueAsString(decision.state()), orders.isEmpty() ? null : orders.toString());
    }

    void rejection(long session, String underlying, String intent, String reason, Instant at) {
        jdbc.update("insert into trade.rejection (session_id, underlying, intent, reason, at) values (?,?,?,?,?)",
                session, underlying, intent, reason, ts(at));
    }

    void killSwitch(Long session, String scope, boolean engaged, String reason, Instant at, String by) {
        jdbc.update("insert into ops.kill_switch_event (session_id, scope, engaged, reason, at, by_whom) "
                + "values (?,?,?,?,?,?)", session, scope, engaged, reason, ts(at), by);
    }

    List<Map<String, Object>> orders(long session) {
        return jdbc.queryForList("select o.client_order_id, o.underlying, o.role, o.order_side, o.order_type, o.symbol, "
                + "o.quantity, o.limit_price, o.trigger_price, o.sent_at, e.status, e.filled, e.average_price "
                + "from trade.orders o left join lateral (select status, filled, average_price from trade.order_event "
                + "where client_order_id = o.client_order_id order by id desc limit 1) e on true "
                + "where o.session_id = ? order by o.sent_at", session);
    }

    /** Stage, scores and the named conditions that held (the evidence behind the stage). */
    private static String scores(SideView view) {
        List<String> held = view.conditions().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)
                .toList();
        return JSON.writeValueAsString(Map.of("stage", view.stage().name(), "early", finite(view.earlyScore()),
                "confirm", finite(view.confirmScore()), "runner", finite(view.runnerScore()),
                "cas", finite(view.casScore()), "held", held));
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? Math.round(value * 100) / 100.0 : -1;
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
