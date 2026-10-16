package com.autotrade.execution;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.CapitalPolicy;
import com.autotrade.account.EntryDecision;
import com.autotrade.account.Sizing;
import com.autotrade.account.TradeMode;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.Side;

/**
 * Live execution L1: runs one follower account's PAPER or DRY_RUN intents of a day, in decision order (time, then the
 * live set's priority), through the same account rules a live follower will use:
 * <ul>
 *   <li>ENTER: skipped when the account already holds that strategy's position on the underlying; sized from the
 *       decision's budget scaled to the account's equity and subscription multiplier, then its size step (one-lot pilot
 *       or a ladder share), and never above the signal's lots (what the order book allowed, risk v12 participation);
 *       refused, never shrunk, by {@link CapitalPolicy} (daily loss, per trade, free capital, broker funds in DRY_RUN).</li>
 *   <li>ADD: in proportion to the account's position against the signal account's entry; checked like an entry.</li>
 *   <li>EXIT: closes the account's position at the signal's exit price; skipped when it holds none.</li>
 *   <li>A straddle leg (leg group) is skipped in L1.</li>
 * </ul>
 * Fills are simulated at the signal's price moved against the account by the risk file's adverse bps; charges from the
 * cost model. DRY_RUN records the order it would have sent; broker funds are {@code simulatedBrokerFunds} (a real broker
 * read in L2). LIVE intents are left untouched.
 */
public final class FollowerSimulator {

    /** One account's day: intents executed, skipped and rejected, positions closed and their net. */
    public record DayResult(String account, int executed, int skipped, int rejected, int closed, double net,
                            Map<String, Integer> reasons) {
    }

    private record Pending(long intentId, String mode, String action, String strategyId, String underlying,
                           String side, long token, String symbol, int lotSize, double price, double budget,
                           double referenceCapital, int lots, String legGroup, Instant at, String reason,
                           double sizeMultiplier) {
    }

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final CostModel costs;
    private final FollowerRisk risk;
    private final double simulatedBrokerFunds;

    public FollowerSimulator(DataSource dataSource, CostModel costs, FollowerRisk risk, double simulatedBrokerFunds) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.accounts = new AccountStore(dataSource);
        this.costs = costs;
        this.risk = risk;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
    }

    public DayResult run(BrokerAccount account, long copyGroupId, LocalDate day) {
        CapitalPolicy policy = new CapitalPolicy(risk.limits());
        double movements = accounts.capitalMovements(account.id(), day);
        double realisedBefore = realisedNet(account.id(), account.equityFrom(), day);
        double equity = CapitalPolicy.equity(account, movements, realisedBefore);
        int executed = 0, skipped = 0, rejected = 0, closed = 0;
        double net = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Pending p : pending(account.id(), copyGroupId, day)) {
            String outcome;
            if (p.legGroup() != null) {
                outcome = skip(p, "STRADDLE_NOT_FOLLOWED", "straddle legs are not followed in L1");
            } else {
                outcome = switch (p.action()) {
                    case "ENTER" -> enter(account, policy, equity, day, p);
                    case "ADD" -> add(account, policy, equity, day, p);
                    default -> exit(account, day, p);
                };
            }
            reasons.merge(outcome, 1, Integer::sum);
            if (outcome.equals("EXECUTED")) {
                executed++;
            } else if (outcome.startsWith("SKIPPED")) {
                skipped++;
            } else {
                rejected++;
            }
            if (outcome.equals("EXECUTED") && p.action().equals("EXIT")) {
                closed++;
            }
        }
        net = realisedNet(account.id(), day, day.plusDays(1));
        return new DayResult(account.displayName(), executed, skipped, rejected, closed, net, reasons);
    }

    private String enter(BrokerAccount account, CapitalPolicy policy, double equity, LocalDate day, Pending p) {
        if (openPosition(account.id(), p.strategyId(), p.underlying()) != null) {
            return skip(p, "ALREADY_HELD", "the account already holds this strategy's position");
        }
        double budget = Sizing.followerBudget(p.budget(), p.referenceCapital(), equity, p.sizeMultiplier());
        double price = buyPrice(p.price());
        long lots = Sizing.lots(budget, price, p.lotSize(), account.sizeStepPct());
        if (p.lots() > 0) {
            lots = Math.min(lots, p.lots());     // never more than the order book allowed the signal (liquidity ceiling)
        }
        if (lots < 1) {
            return reject(p, "SIZE", String.format("budget %.0f buys no lot at %.2f x %d", budget, price, p.lotSize()));
        }
        long quantity = lots * p.lotSize();
        double premium = quantity * price;
        EntryDecision check = policy.checkEntry(TradeMode.valueOf(p.mode()), equity, premium, openPremium(account.id()),
                realisedNet(account.id(), day, day.plusDays(1)), funds(p.mode()));
        if (!check.approved()) {
            return reject(p, check.code(), check.reason());
        }
        double charges = charge(day, p.underlying(), Side.BUY, price, quantity);
        order(account, p, "ENTRY", "BUY", quantity, price);
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                        + "option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, charges, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')", account.tenantId(), account.id(),
                p.intentId(), p.strategyId(), p.underlying(), p.side(), p.token(), p.symbol(), quantity, price,
                Timestamp.from(p.at()), charges);
        return done(p, quantity);
    }

    private String add(BrokerAccount account, CapitalPolicy policy, double equity, LocalDate day, Pending p) {
        Map<String, Object> position = openPosition(account.id(), p.strategyId(), p.underlying());
        if (position == null) {
            return skip(p, "NOT_HELD", "no position to add to");
        }
        Integer signalEntryLots = jdbc.queryForObject("select d.lots from exec.trade_intent i "
                + "join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d on d.id = b.signal_decision_id "
                + "where i.id = ?", Integer.class, ((Number) position.get("entry_trade_intent_id")).longValue());
        long held = ((Number) position.get("quantity_open")).longValue();
        if (signalEntryLots == null || signalEntryLots < 1 || p.lots() < 1) {
            return skip(p, "NO_RATIO", "the signal's entry or add size is unknown");
        }
        long lots = (long) Math.floor((double) p.lots() * held / ((long) signalEntryLots * p.lotSize()));
        if (lots < 1) {
            return skip(p, "ADD_BELOW_ONE_LOT", "the account's share of the add is under one lot");
        }
        long quantity = lots * p.lotSize();
        double price = buyPrice(p.price());
        EntryDecision check = policy.checkEntry(TradeMode.valueOf(p.mode()), equity, quantity * price,
                openPremium(account.id()), realisedNet(account.id(), day, day.plusDays(1)), funds(p.mode()));
        if (!check.approved()) {
            return reject(p, check.code(), check.reason());
        }
        double average = ((Number) position.get("average_entry")).doubleValue();
        long total = held + quantity;
        double charges = charge(day, p.underlying(), Side.BUY, price, quantity);
        order(account, p, "ENTRY", "BUY", quantity, price);
        jdbc.update("update exec.position set quantity_open = ?, average_entry = ?, charges = charges + ? where id = ?",
                total, (average * held + price * quantity) / total, charges,
                ((Number) position.get("id")).longValue());
        return done(p, quantity);
    }

    private String exit(BrokerAccount account, LocalDate day, Pending p) {
        Map<String, Object> position = openPosition(account.id(), p.strategyId(), p.underlying());
        if (position == null) {
            return skip(p, "NOT_HELD", "the account holds no position to exit");
        }
        if (!(p.price() > 0)) {
            return reject(p, "NO_EXIT_PRICE", "the signal's exit price is unknown");
        }
        long quantity = ((Number) position.get("quantity_open")).longValue();
        double price = sellPrice(p.price());
        double average = ((Number) position.get("average_entry")).doubleValue();
        double charges = charge(day, p.underlying(), Side.SELL, price, quantity);
        order(account, p, "EXIT", "SELL", quantity, price);
        jdbc.update("update exec.position set quantity_open = 0, closed_at = ?, exit_reason = ?, realised_gross = ?, "
                        + "charges = charges + ?, status = 'CLOSED' where id = ?", Timestamp.from(p.at()), p.reason(),
                (price - average) * quantity, charges, ((Number) position.get("id")).longValue());
        return done(p, quantity);
    }

    private void order(BrokerAccount account, Pending p, String role, String side, long quantity, double price) {
        String tag = "F" + p.intentId() + "A1";
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, "
                        + "broker_type, role, client_tag, attempt_no, order_side, order_type, quantity_requested, "
                        + "quantity_filled, limit_price, average_fill_price, status, raw_status) values "
                        + "(?, ?, ?, ?, ?, ?, 1, ?, 'LIMIT', ?, ?, ?, ?, 'FILLED', ?)",
                account.tenantId(), p.intentId(), account.id(), account.brokerType().name(), role, tag, side, quantity,
                quantity, price, price, p.mode().equals("DRY_RUN") ? "DRY_RUN_SIMULATED" : "PAPER_SIMULATED");
    }

    private String done(Pending p, long quantity) {
        jdbc.update("update exec.trade_intent set state = 'EXECUTED', quantity = ?, attempts = attempts + 1, "
                + "updated_at = now() where id = ?", quantity, p.intentId());
        return "EXECUTED";
    }

    private String skip(Pending p, String code, String reason) {
        jdbc.update("update exec.trade_intent set state = 'SKIPPED', reject_code = ?, reject_reason = ?, updated_at = now() "
                + "where id = ?", code, reason, p.intentId());
        return "SKIPPED_" + code;
    }

    private String reject(Pending p, String code, String reason) {
        jdbc.update("update exec.trade_intent set state = 'REJECTED', reject_code = ?, reject_reason = ?, updated_at = now() "
                + "where id = ?", code, reason, p.intentId());
        return "REJECTED_" + code;
    }

    private double funds(String mode) {
        return TradeMode.valueOf(mode).needsBrokerFunds() ? simulatedBrokerFunds : Double.NaN;
    }

    private double buyPrice(double price) {
        return price * (1 + risk.adverseBps() / 10_000);
    }

    private double sellPrice(double price) {
        return price * (1 - risk.adverseBps() / 10_000);
    }

    private double charge(LocalDate day, String underlying, Side side, double price, long quantity) {
        return costs.costs(day, "SENSEX".equals(underlying) ? "BSE" : "NSE", side, price, quantity).total();
    }

    private Map<String, Object> openPosition(long accountId, String strategyId, String underlying) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, entry_trade_intent_id, quantity_open, average_entry "
                + "from exec.position where broker_account_id = ? and strategy_id = ? and underlying = ? and status = 'OPEN' "
                + "order by id limit 1", accountId, strategyId, underlying);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private double openPremium(long accountId) {
        Double premium = jdbc.queryForObject("select coalesce(sum(quantity_open * average_entry), 0) from exec.position "
                + "where broker_account_id = ? and status = 'OPEN'", Double.class, accountId);
        return premium == null ? 0 : premium;
    }

    /** Realised net (gross minus charges) of the account's positions closed on days [from, until). */
    private double realisedNet(long accountId, LocalDate from, LocalDate until) {
        Double net = jdbc.queryForObject("select coalesce(sum(realised_gross - charges), 0) from exec.position "
                + "where broker_account_id = ? and status = 'CLOSED' and (closed_at at time zone 'Asia/Kolkata')::date >= ? "
                + "and (closed_at at time zone 'Asia/Kolkata')::date < ?", Double.class, accountId, Date.valueOf(from),
                Date.valueOf(until));
        return net == null ? 0 : net;
    }

    private List<Pending> pending(long accountId, long copyGroupId, LocalDate day) {
        return jdbc.query("select i.id, i.execution_mode, d.action, d.strategy_id, d.underlying, d.option_side, "
                + "d.instrument_token, d.symbol, d.lot_size, d.reference_price, d.premium_budget, d.reference_capital, "
                + "d.lots, d.leg_group, d.decided_at, d.reason, coalesce(s.size_multiplier, 1) as size_multiplier "
                + "from exec.trade_intent i join exec.trade_batch b on b.id = i.trade_batch_id "
                + "join exec.signal_decision d on d.id = b.signal_decision_id "
                + "left join acct.strategy_subscription s on s.copy_group_id = d.copy_group_id "
                + "and s.broker_account_id = i.broker_account_id and s.strategy_id = d.strategy_id "
                + "where i.broker_account_id = ? and d.copy_group_id = ? and d.session_date = ? and i.state = 'CREATED' "
                + "and i.execution_mode in ('PAPER', 'DRY_RUN') order by d.decided_at, d.priority, d.id",
                FollowerSimulator::pendingRow, accountId, copyGroupId, Date.valueOf(day));
    }

    private static double orNaN(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }

    private static Pending pendingRow(ResultSet rs, int i) throws SQLException {
        return new Pending(rs.getLong("id"), rs.getString("execution_mode"), rs.getString("action"),
                rs.getString("strategy_id"), rs.getString("underlying"), rs.getString("option_side"),
                rs.getLong("instrument_token"), rs.getString("symbol"), rs.getInt("lot_size"),
                orNaN(rs, "reference_price"), orNaN(rs, "premium_budget"), orNaN(rs, "reference_capital"),
                rs.getInt("lots"), rs.getString("leg_group"), rs.getTimestamp("decided_at").toInstant(),
                rs.getString("reason"), rs.getDouble("size_multiplier"));
    }
}
