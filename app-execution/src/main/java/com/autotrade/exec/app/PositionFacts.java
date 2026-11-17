package com.autotrade.exec.app;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One open position's state now, for the places that show a position outside the web's book (Telegram's /positions and
 * its Exit card, 17 Nov 2026, positions phase 3): the newest captured bid (if recent), P&amp;L at that bid, the exposure
 * in rupees per index point (delta x quantity), and the book's two warnings: the strategy already exited the contract
 * ({@link LiveBookController#EXIT_SETTLE_SEC}) and a late join ({@link LiveBookController#LATE_JOIN_SEC}).
 */
final class PositionFacts {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;

    PositionFacts(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The facts of an open position (empty when it is not open). */
    Map<String, Object> of(long positionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select p.instrument_token, p.quantity_open, p.average_entry, "
                + "p.strategy_id, i.created_at intent_at, d0.decided_at signal_at, " + LiveBookController.STRATEGY_EXIT_AT
                + " strategy_exit_at from exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "left join exec.trade_batch b0 on b0.id = i.trade_batch_id "
                + "left join exec.signal_decision d0 on d0.id = b0.signal_decision_id "
                + "where p.id = ? and p.status = 'OPEN' and p.quantity_open > 0", positionId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return out;
        }
        Map<String, Object> r = rows.getFirst();
        long qty = ((Number) r.get("quantity_open")).longValue();
        double entry = ((Number) r.get("average_entry")).doubleValue();
        List<Map<String, Object>> last = jdbc.queryForList("select bid_px[1] bid, delta, recv_ts from md.option_tick "
                + "where instrument_token = ? and recv_ts > now() - make_interval(mins => ?) and bid_px[1] > 0 "
                + "order by recv_ts desc limit 1", ((Number) r.get("instrument_token")).longValue(),
                LiveBookController.QUOTE_MAX_AGE_MIN);
        if (!last.isEmpty()) {
            double bid = ((Number) last.getFirst().get("bid")).doubleValue();
            out.put("bid", bid);
            out.put("quoteAt", ((Timestamp) last.getFirst().get("recv_ts")).toInstant().atZone(IST).toLocalTime().withNano(0)
                    .toString());
            out.put("pnl", Math.round((bid - entry) * qty * 100) / 100.0);
            Object delta = last.getFirst().get("delta");
            if (delta != null) {
                out.put("perPoint", Math.round(((Number) delta).doubleValue() * qty * 100) / 100.0);
            }
        }
        Timestamp exitAt = (Timestamp) r.get("strategy_exit_at");
        if (exitAt != null && exitAt.toInstant().isBefore(Instant.now().minusSeconds(LiveBookController.EXIT_SETTLE_SEC))) {
            out.put("strategyExitedAt", exitAt.toInstant().atZone(IST).toLocalTime().withNano(0).toString());
        }
        Timestamp intentAt = (Timestamp) r.get("intent_at"), signalAt = (Timestamp) r.get("signal_at");
        if (intentAt != null && signalAt != null
                && intentAt.toInstant().isAfter(signalAt.toInstant().plusSeconds(LiveBookController.LATE_JOIN_SEC))) {
            out.put("lateJoin", signalAt.toInstant().atZone(IST).toLocalTime().withNano(0) + " signal, bought "
                    + intentAt.toInstant().atZone(IST).toLocalTime().withNano(0));
        }
        return out;
    }

    /** One line for Telegram: "bid 31.10 (11:42:03) · P&L −2,155 · ₹29/pt · NOT MANAGED: ... · LATE JOIN ...". */
    String line(long positionId) {
        Map<String, Object> f = of(positionId);
        StringBuilder s = new StringBuilder();
        if (f.containsKey("bid")) {
            s.append("bid ").append(String.format(java.util.Locale.ROOT, "%.2f", (Double) f.get("bid"))).append(" (")
                    .append(f.get("quoteAt")).append(") · P&L ").append(rupees((Double) f.get("pnl")));
            if (f.containsKey("perPoint")) {
                s.append(" · ").append(rupees((Double) f.get("perPoint"))).append("/pt");
            }
        } else {
            s.append("no recent quote");
        }
        if (f.containsKey("strategyExitedAt")) {
            s.append("\n  NOT MANAGED: its strategy exited this contract at ").append(f.get("strategyExitedAt"))
                    .append("; only your exit or the square-off closes it");
        }
        if (f.containsKey("lateJoin")) {
            s.append("\n  LATE JOIN: ").append(f.get("lateJoin")).append(" at the signal's price");
        }
        return s.toString();
    }

    private static String rupees(double v) {
        long r = Math.round(v);
        return (r < 0 ? "-" : r > 0 ? "+" : "") + "Rs " + String.format(java.util.Locale.ROOT, "%,d", Math.abs(r));
    }
}
