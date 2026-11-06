package com.autotrade.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.BrokerAccount;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.Side;

/**
 * The trader books a hand exit (7 Nov 2026, after two hand sales on ZERODHA-1 left our record OPEN and the account
 * killed): one open position of ours closes as MANUAL at the price and time the trader enters, charged by the cost
 * model on that sale, audited MANUAL_EXIT with who booked it. Nothing is sent to the broker. Refused while any order of
 * the position may still work at the broker (a resting stop could sell what is no longer held), and refused when the
 * broker is read and still holds the contract (that is an Exit, not a hand sale); with the broker not readable (no
 * session) only when the trader confirms booking unchecked. The next reconciliation catches a wrong booking.
 */
public final class ManualExit {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Order states of the position's orders that may still work at the broker. */
    static final List<String> NOT_ENDED = List.of("CREATED", "SUBMITTED", "ACKNOWLEDGED", "OPEN", "PARTIALLY_FILLED",
            "CANCEL_PENDING", "UNKNOWN", "RECONCILIATION_REQUIRED");

    /** What the broker says it holds of the contract: {@code net} null when it could not be read ({@code why}). */
    public record BrokerHolding(Long net, String why) {
    }

    private final JdbcTemplate jdbc;
    private final CostModel costs;
    private final KillSwitches kills;

    public ManualExit(DataSource dataSource, CostModel costs) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.costs = costs;
        this.kills = new KillSwitches(dataSource);
    }

    /** Books it; returns the line shown to the trader. Throws IllegalArgumentException when refused. */
    public String book(BrokerAccount account, long positionId, double price, Instant at, String actor, BrokerHolding broker,
                       boolean unchecked) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, entry_trade_intent_id, underlying, symbol, "
                + "instrument_key, quantity_open, average_entry, opened_at, status from exec.position where id = ? and "
                + "broker_account_id = ?", positionId, account.id());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no position " + positionId + " on " + account.displayName());
        }
        Map<String, Object> p = rows.getFirst();
        long open = ((Number) p.get("quantity_open")).longValue();
        String symbol = (String) p.get("symbol");
        if ("CLOSED".equals(p.get("status")) || open <= 0) {
            throw new IllegalArgumentException(symbol + " is already closed");
        }
        if (!(price > 0) || price > 100_000) {
            throw new IllegalArgumentException("the sale price must be above 0");
        }
        Instant opened = ((Timestamp) p.get("opened_at")).toInstant();
        if (at == null || at.isBefore(opened) || at.isAfter(Instant.now().plusSeconds(60))) {
            throw new IllegalArgumentException("the sale time must be between the entry (" + opened.atZone(IST)
                    .toLocalDateTime() + ") and now");
        }
        List<String> working = jdbc.queryForList("select role || ' ' || status from exec.order_execution where "
                + "broker_account_id = ? and trade_intent_id = ? and status in (" + String.join(", ", NOT_ENDED.stream()
                .map(x -> "'" + x + "'").toList()) + ") order by id", String.class, account.id(),
                p.get("entry_trade_intent_id"));
        if (!working.isEmpty()) {
            throw new IllegalArgumentException(symbol + " still has orders that may work at the broker (" + String.join(", ",
                    working) + "): cancel them in Kite and let the reconciler see them end first");
        }
        String key = (String) p.get("instrument_key");
        if (broker.net() != null) {
            long oursOther = jdbc.queryForObject("select coalesce(sum(quantity_open), 0) from exec.position where "
                    + "broker_account_id = ? and status <> 'CLOSED' and instrument_key = ? and id <> ?", Long.class,
                    account.id(), key, positionId);
            if (broker.net() > oursOther) {
                throw new IllegalArgumentException("the broker still holds " + broker.net() + " of " + symbol + " (ours "
                        + (oursOther + open) + "): it was not sold by hand; use Exit to sell it");
            }
        } else if (!unchecked) {
            throw new IllegalArgumentException("the broker could not be read (" + broker.why() + "), so it is not known "
                    + "that " + symbol + " was sold: confirm booking it unchecked");
        }
        double average = ((Number) p.get("average_entry")).doubleValue();
        String underlying = (String) p.get("underlying");
        double gross = (price - average) * open;
        double charges = costs.costs(at.atZone(IST).toLocalDate(), "SENSEX".equals(underlying) ? "BSE" : "NSE", Side.SELL,
                price, open).total();
        int done = jdbc.update("update exec.position set quantity_open = 0, realised_gross = coalesce(realised_gross, 0) + ?, "
                + "charges = charges + ?, closed_at = ?, exit_reason = 'MANUAL', status = 'CLOSED' where id = ? and status "
                + "<> 'CLOSED' and quantity_open = ?", gross, charges, Timestamp.from(at), positionId, open);
        if (done != 1) {
            throw new IllegalArgumentException(symbol + " changed while booking: reload and try again");
        }
        kills.audit(account, actor, "MANUAL_EXIT", Map.of("position", positionId, "symbol", symbol, "quantity", open,
                "price", round(price), "gross", round(gross), "charges", round(charges), "at", at.toString(),
                "rule", "booked by the trader", "broker", broker.net() == null ? "not read: " + broker.why()
                        : "holds " + broker.net()));
        return symbol + ": " + open + " booked sold by hand at " + String.format(java.util.Locale.ROOT, "%.2f", price)
                + " (gross " + String.format(java.util.Locale.ROOT, "%.2f", gross) + ")";
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
