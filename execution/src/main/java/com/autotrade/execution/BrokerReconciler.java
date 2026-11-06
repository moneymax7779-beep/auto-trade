package com.autotrade.execution;

import java.sql.Date;
import java.time.Instant;
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
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.BrokerPosition;
import com.autotrade.broker.BrokerTrade;
import com.autotrade.broker.OrderSide;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.Side;

/**
 * Our book against the broker's (live plan section 4, "Reconciliation mismatch"; L2 "EOD broker reconciliation").
 * <ul>
 *   <li>{@link #check}: intraday, at every lease takeover and on a timer. The broker's net quantity per contract (our
 *       product only) against our open positions. A difference, including a position we do not hold (a manual trade),
 *       that is still there at the next check engages the account's kill switch (entries stop; exits for our positions
 *       continue) and is audited with both sides. One check alone is not enough (code review 3 Nov 2026): a stop that
 *       filled since the last turn is booked only after the check, and Kite's positions can lag its order book, so a
 *       normal stop-out or fill would otherwise stop the account's entries for the day. Nothing is flattened
 *       automatically. One difference is booked instead (6 Nov 2026, the second hand exit in two days on ZERODHA-1):
 *       a position of ours the broker no longer holds at all, with none of its stops live at the broker, sold by
 *       sells that are not ours ({@link #bookHandSale}). It closes as a MANUAL exit and the account is not stopped.</li>
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
    /**
     * The contracts that differed at each account's last check (a contract must differ twice in a row), each with the
     * time of the check before it first differed (null: none since the start): a hand sale came after that.
     */
    private final Map<Long, Map<String, Instant>> lastDifferences = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each account's last check. */
    private final Map<Long, Instant> lastCheck = new java.util.concurrent.ConcurrentHashMap<>();
    /** Our order tags: F, the trade intent, the role letter (FollowerSimulator / BrokerVenue). */
    private static final java.util.regex.Pattern OUR_TAG = java.util.regex.Pattern.compile("F\\d+[A-Z].*");

    public BrokerReconciler(DataSource dataSource, CostModel costs) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.accounts = new AccountStore(dataSource);
        this.kills = new KillSwitches(dataSource);
        this.costs = costs;
    }

    public Check check(BrokerAccount account, BrokerConnection broker) {
        Instant now = Instant.now();
        Instant previous = lastCheck.put(account.id(), now);
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
        Map<String, Instant> before = lastDifferences.getOrDefault(account.id(), Map.of());
        Map<String, Instant> differing = new TreeMap<>();
        List<String> differences = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        BrokerBooks books = new BrokerBooks(broker);
        for (String key : union(ours, theirs)) {
            long o = ours.getOrDefault(key, 0L), t = theirs.getOrDefault(key, 0L);
            if (o == t) {
                continue;
            }
            boolean again = before.containsKey(key);
            Instant since = again ? before.get(key) : previous;
            // ours open, the broker flat: sold by hand? (a stop's own fill is ours, booked by the next turn)
            if (again && o > 0 && t == 0 && bookHandSale(account, books, key, since)) {
                continue;
            }
            differing.put(key, since);
            String difference = key + ": ours " + o + ", broker " + t + (o == 0 ? " (not ours: a manual trade?)" : "");
            differences.add(difference);
            if (again) {
                repeated.add(difference);
            }
        }
        if (differing.isEmpty()) {
            lastDifferences.remove(account.id());
        } else {
            lastDifferences.put(account.id(), differing);
        }
        if (!repeated.isEmpty()) {                                   // a contract that differed last time too
            kills.engageAccount(account, "RECONCILIATION: " + String.join("; ", differences), "reconciler");
        }
        return new Check(differences.isEmpty(), differences);
    }

    /**
     * Books a hand sale of our position in {@code key}: the broker holds none of it, none of its stops is live at the
     * broker (a live one could still sell: nothing is booked then), and today's sells of the contract that are not
     * ours (not our order ids, not our tags, not booked by an earlier hand sale) cover what we hold. Sells after
     * {@code since} (the check before the difference showed) come first, in time order, then the latest before it.
     * Each position closes as MANUAL at its sells' price, charged on them; the sells used are audited (MANUAL_EXIT).
     */
    private boolean bookHandSale(BrokerAccount account, BrokerBooks books, String key, Instant since) {
        List<Map<String, Object>> positions = jdbc.queryForList("select id, entry_trade_intent_id, underlying, symbol, "
                + "quantity_open, average_entry, opened_at from exec.position where broker_account_id = ? and status <> "
                + "'CLOSED' and quantity_open > 0 and instrument_key = ? order by opened_at, id", account.id(), key);
        for (Map<String, Object> p : positions) {
            for (String tag : jdbc.queryForList("select client_tag from exec.order_execution where trade_intent_id = ? "
                    + "and broker_account_id = ? and role = 'STOP'", String.class, p.get("entry_trade_intent_id"),
                    account.id())) {
                if (books.orders().stream().anyMatch(o -> tag.equals(o.clientTag()) && !o.status().isTerminal())) {
                    return false;
                }
            }
        }
        Instant opened = ((java.sql.Timestamp) positions.getFirst().get("opened_at")).toInstant();
        java.util.Set<String> ourOrders = new java.util.HashSet<>(jdbc.queryForList("select broker_order_id from "
                + "exec.order_execution where broker_account_id = ? and broker_order_id is not null", String.class,
                account.id()));
        books.orders().stream().filter(o -> o.clientTag() != null && OUR_TAG.matcher(o.clientTag()).matches())
                .forEach(o -> ourOrders.add(o.brokerOrderId()));
        java.util.Set<String> used = new java.util.HashSet<>(jdbc.queryForList("select jsonb_array_elements_text(detail "
                + "-> 'trades') from exec.audit_log where broker_account_id = ? and action = 'MANUAL_EXIT' and at > now() "
                + "- interval '2 days'", String.class, account.id()));
        List<BrokerTrade> sells = books.trades().stream().filter(t -> key.equals(t.instrumentKey())
                && t.side() == OrderSide.SELL && !ourOrders.contains(t.brokerOrderId()) && !used.contains(t.tradeId())
                && (t.time() == null || !t.time().isBefore(opened))).toList();
        List<BrokerTrade> ordered = new ArrayList<>(sells.stream().filter(t -> since != null && t.time() != null
                && t.time().isAfter(since)).sorted(java.util.Comparator.comparing(BrokerTrade::time)).toList());
        sells.stream().filter(t -> !ordered.contains(t))
                .sorted(java.util.Comparator.comparing(BrokerTrade::time, java.util.Comparator.nullsFirst(
                        java.util.Comparator.<Instant>naturalOrder())).reversed()).forEach(ordered::add);
        long held = positions.stream().mapToLong(p -> ((Number) p.get("quantity_open")).longValue()).sum();
        if (ordered.stream().mapToLong(BrokerTrade::quantity).sum() < held) {
            return false;                                            // no hand sale explains it: the kill switch
        }
        java.util.Iterator<BrokerTrade> next = ordered.iterator();
        BrokerTrade current = null;
        long left = 0;
        for (Map<String, Object> p : positions) {
            long open = ((Number) p.get("quantity_open")).longValue();
            double average = ((Number) p.get("average_entry")).doubleValue();
            String underlying = (String) p.get("underlying");
            long sold = 0;
            double value = 0, charges = 0;
            Instant at = null;
            List<String> trades = new ArrayList<>();
            while (sold < open) {
                if (left == 0) {
                    current = next.next();
                    left = current.quantity();
                }
                long q = Math.min(left, open - sold);
                sold += q;
                left -= q;
                value += q * current.price();
                LocalDate day = (current.time() == null ? Instant.now() : current.time()).atZone(IST).toLocalDate();
                charges += costs.costs(day, "SENSEX".equals(underlying) ? "BSE" : "NSE", Side.SELL, current.price(), q)
                        .total();
                at = current.time() == null || (at != null && at.isAfter(current.time())) ? at : current.time();
                trades.add(current.tradeId());
            }
            double price = value / sold;
            double gross = value - average * sold;
            jdbc.update("update exec.position set quantity_open = 0, realised_gross = coalesce(realised_gross, 0) + ?, "
                    + "charges = charges + ?, closed_at = ?, exit_reason = 'MANUAL', status = 'CLOSED' where id = ?",
                    gross, charges, java.sql.Timestamp.from(at == null ? Instant.now() : at), p.get("id"));
            kills.audit(account, "reconciler", "MANUAL_EXIT", Map.of("position", p.get("id"), "symbol", p.get("symbol"),
                    "quantity", sold, "price", Math.round(price * 100) / 100.0, "gross", Math.round(gross * 100) / 100.0,
                    "charges", Math.round(charges * 100) / 100.0, "trades", trades,
                    "rule", "broker holds none, no stop live, sold by hand"));
        }
        return true;
    }

    /** The broker's order and trade books, each read once per check and only when needed. */
    private static final class BrokerBooks {
        private final BrokerConnection broker;
        private List<BrokerOrder> orders;
        private List<BrokerTrade> trades;

        BrokerBooks(BrokerConnection broker) {
            this.broker = broker;
        }

        List<BrokerOrder> orders() {
            return orders == null ? (orders = broker.orders()) : orders;
        }

        List<BrokerTrade> trades() {
            return trades == null ? (trades = broker.trades()) : trades;
        }
    }

    public EndOfDay endOfDay(BrokerAccount account, BrokerConnection broker, LocalDate day) {
        Map<String, double[]> ours = new TreeMap<>();      // key -> bought qty, bought value, sold qty, sold value
        // every fill of the day by its decision's contract (a stop or emergency exit belongs to its entry's intent)
        jdbc.query("select d.instrument_key, e.order_side, sum(e.quantity_filled), "
                        + "sum(e.quantity_filled * e.average_fill_price), d.instrument_token from exec.order_execution e "
                        + "join exec.trade_intent i on i.id = e.trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                        + "join exec.signal_decision d on d.id = b.signal_decision_id where e.broker_account_id = ? "
                        + "and e.quantity_filled > 0 and d.session_date = ? group by 1, 2, 5", rs -> {
                    // the exchange key, as the broker reports it (a zt replay's ZT|<token> normalised)
                    String key = keyOf(InstrumentKeys.normalize(rs.getString(1), rs.getLong(5)));
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
        // each differing contract is kept with the day, so a gap can be traced after the worker's log has gone
        kills.audit(account, "reconciler", "END_OF_DAY", Map.of("day", day.toString(), "difference",
                String.valueOf(ledger.difference()), "contracts_different", differences.size(), "differences", differences));
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
