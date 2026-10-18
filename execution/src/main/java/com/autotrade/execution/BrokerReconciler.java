package com.autotrade.execution;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.CapitalPolicy;
import com.autotrade.account.LedgerDay;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderSide;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.Side;

/**
 * Our book against the broker's (live plan section 4, "Reconciliation mismatch"; L2 "EOD broker reconciliation").
 * <ul>
 *   <li>{@link #check}: intraday, at every lease takeover and on a timer. The broker's net quantity per contract (our
 *       product only) against our open positions. Any difference, including a position we do not hold (a manual trade),
 *       engages the account's kill switch (entries stop; exits for our positions continue) and is audited with both
 *       sides. Nothing is flattened automatically.</li>
 *   <li>{@link #endOfDay}: after the close. The broker's trade book against our fills, contract by contract (bought and
 *       sold quantity and value), and the day's ledger row: our gross and charges, the broker's gross from its trades with
 *       charges from the cost model on those trades (until contract notes are read), and the difference. A difference of
 *       a rupee or more must be acknowledged before the account's next live start ({@link LedgerDay}).</li>
 * </ul>
 */
public final class BrokerReconciler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Check(boolean matched, List<String> differences) {
    }

    public record EndOfDay(LedgerDay ledger, List<String> differences) {
    }

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final KillSwitches kills;
    private final CostModel costs;

    public BrokerReconciler(DataSource dataSource, CostModel costs) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.accounts = new AccountStore(dataSource);
        this.kills = new KillSwitches(dataSource);
        this.costs = costs;
    }

    public Check check(BrokerAccount account, BrokerConnection broker) {
        Map<String, Long> ours = new TreeMap<>();
        jdbc.query("select instrument_key, sum(quantity_open) from exec.position where broker_account_id = ? "
                + "and status <> 'CLOSED' and quantity_open > 0 group by 1", rs -> {
                    ours.put(String.valueOf(rs.getString(1)), rs.getLong(2));
                }, account.id());
        Map<String, Long> theirs = new TreeMap<>();
        for (BrokerPosition p : broker.positions()) {
            if (p.netQuantity() != 0) {
                theirs.merge(p.instrumentKey() == null ? p.symbol() : p.instrumentKey(), p.netQuantity(), Long::sum);
            }
        }
        List<String> differences = new ArrayList<>();
        for (String key : union(ours, theirs)) {
            long o = ours.getOrDefault(key, 0L), t = theirs.getOrDefault(key, 0L);
            if (o != t) {
                differences.add(key + ": ours " + o + ", broker " + t + (o == 0 ? " (not ours: a manual trade?)" : ""));
            }
        }
        if (!differences.isEmpty()) {
            kills.engageAccount(account, "RECONCILIATION: " + String.join("; ", differences), "reconciler");
        }
        return new Check(differences.isEmpty(), differences);
    }

    public EndOfDay endOfDay(BrokerAccount account, BrokerConnection broker, LocalDate day) {
        Map<String, double[]> ours = new TreeMap<>();      // key -> bought qty, bought value, sold qty, sold value
        // every fill of the day by its decision's contract (a stop or emergency exit belongs to its entry's intent)
        jdbc.query("select d.instrument_key, e.order_side, sum(e.quantity_filled), "
                        + "sum(e.quantity_filled * e.average_fill_price) from exec.order_execution e "
                        + "join exec.trade_intent i on i.id = e.trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                        + "join exec.signal_decision d on d.id = b.signal_decision_id where e.broker_account_id = ? "
                        + "and e.quantity_filled > 0 and d.session_date = ? group by 1, 2", rs -> {
                    String key = keyOf(rs.getString(1));
                    double[] v = ours.computeIfAbsent(key, k -> new double[4]);
                    int i = "BUY".equals(rs.getString(2)) ? 0 : 2;
                    v[i] += rs.getLong(3);
                    v[i + 1] += rs.getDouble(4);
                }, account.id(), Date.valueOf(day));
        Map<String, double[]> theirs = new TreeMap<>();
        double brokerCharges = 0;
        for (BrokerTrade t : broker.trades()) {
            if (t.time() != null && !t.time().atZone(IST).toLocalDate().equals(day)) {
                continue;
            }
            double[] v = theirs.computeIfAbsent(t.instrumentKey(), k -> new double[4]);
            int i = t.side() == OrderSide.BUY ? 0 : 2;
            v[i] += t.quantity();
            v[i + 1] += t.quantity() * t.price();
            brokerCharges += costs.costs(day, t.instrumentKey().startsWith("BSE") ? "BSE" : "NSE",
                    t.side() == OrderSide.BUY ? Side.BUY : Side.SELL, t.price(), t.quantity()).total();
        }
        List<String> differences = new ArrayList<>();
        double brokerGross = 0;
        for (String key : union(ours, theirs)) {
            double[] o = ours.getOrDefault(key, new double[4]), t = theirs.getOrDefault(key, new double[4]);
            brokerGross += t[3] - t[1];
            if (o[0] != t[0] || o[2] != t[2] || Math.abs(o[1] - t[1]) >= 1 || Math.abs(o[3] - t[3]) >= 1) {
                differences.add(String.format(java.util.Locale.ROOT, "%s: ours bought %.0f for %.2f, sold %.0f for %.2f; "
                        + "broker bought %.0f for %.2f, sold %.0f for %.2f", key, o[0], o[1], o[2], o[3], t[0], t[1], t[2], t[3]));
            }
        }
        Map<String, Object> own = jdbc.queryForMap("select coalesce(sum(realised_gross), 0) gross, coalesce(sum(charges), 0) "
                + "charges, count(*) trades from exec.position where broker_account_id = ? and status = 'CLOSED' "
                + "and (closed_at at time zone 'Asia/Kolkata')::date = ?", account.id(), Date.valueOf(day));
        double realisedBefore = jdbc.queryForObject("select coalesce(sum(realised_gross - charges), 0) from exec.position "
                + "where broker_account_id = ? and status = 'CLOSED' and (closed_at at time zone 'Asia/Kolkata')::date >= ? "
                + "and (closed_at at time zone 'Asia/Kolkata')::date < ?", Double.class, account.id(),
                Date.valueOf(account.equityFrom()), Date.valueOf(day));
        double opening = CapitalPolicy.equity(account, accounts.capitalMovements(account.id(), day), realisedBefore);
        LedgerDay ledger = LedgerDay.close(account.id(), day, opening, ((Number) own.get("gross")).doubleValue(),
                ((Number) own.get("charges")).doubleValue(), ((Number) own.get("trades")).intValue())
                .reconciled(brokerGross - brokerCharges, brokerCharges);
        accounts.writeLedgerDay(ledger);
        kills.audit(account, "reconciler", "END_OF_DAY", Map.of("day", day.toString(), "difference",
                String.valueOf(ledger.difference()), "contracts_different", differences.size()));
        return new EndOfDay(ledger, differences);
    }

    private static String keyOf(String keyOrTag) {
        return keyOrTag == null ? "?" : keyOrTag;
    }

    private static java.util.Set<String> union(Map<String, ?> a, Map<String, ?> b) {
        java.util.Set<String> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }
}
