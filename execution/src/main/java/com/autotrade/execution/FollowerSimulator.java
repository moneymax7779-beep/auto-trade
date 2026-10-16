package com.autotrade.execution;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.CapitalPolicy;
import com.autotrade.account.EntryDecision;
import com.autotrade.account.TradeMode;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.Side;

/**
 * Live execution L1: runs one follower account's PAPER or DRY_RUN intents of a day, in decision order (time, then the
 * live set's priority), sizing and refusing as PAPER-1's risk engine does for the same equity (sizing parity, 16 Oct):
 * <ul>
 *   <li><b>ENTER (single leg)</b>: skipped when the account already holds that strategy's position on the underlying.
 *       Budget = the decision's budget x equity / reference capital x subscription multiplier, raised by the RVOL boost
 *       on spare capital only (at most 97 % of free capital, never below the budget). A budget under one lot refuses.
 *       The scale fixed at this entry = budget / (intended lots x lot size x price); lots = max(1, floor(strategy lots x
 *       scale)), as BudgetSizing; then the size step (0 = one-lot pilot) and the signal's liquidity ceiling.</li>
 *   <li><b>ADD</b>: max(1, floor(strategy lots x the position's scale)); none in the one-lot pilot.</li>
 *   <li><b>ENTER straddle</b> (both legs, one leg group): lots = floor(min(budget, free capital) / ((call ask + put ask)
 *       x lot size)), as the OMS sizes a straddle (it shrinks to free capital; a single leg is refused instead).</li>
 *   <li><b>Checks</b>, in the risk engine's order: daily loss (latched for the day once reached), open positions (not
 *       for adds; a straddle counts two), lots per underlying, capital (premium in use + this entry <= equity), broker
 *       funds in DRY_RUN (with the buffer).</li>
 *   <li><b>EXIT</b>: closes the matching position (a straddle leg by its side) at the signal's price; skipped when the
 *       account holds none.</li>
 * </ul>
 * <b>Retry</b>: an add refused for capital stays pending while its position is open and is retried whenever an exit
 * frees capital, at that moment's ask (as PAPER-1's strategies re-ask every snapshot; entries are not retried).
 * <b>Replay depth ceiling</b>: a position (entry plus adds, each straddle leg) never exceeds what the signal account's
 * own position got filled through the same order book (its trade.position quantity: PAPER-1's rules slice entries to
 * the visible depth and cancel the rest after the entry timeout). This stands in for depth in replays only; a live
 * follower's own broker book decides its fills.
 * Fills are simulated at the signal's price moved against the account by the risk file's adverse bps; charges from the
 * cost model. DRY_RUN records the order it would have sent; broker funds are {@code simulatedBrokerFunds} (a real broker
 * read in L2). LIVE intents are left untouched. Known difference from PAPER-1: the day net is realised only.
 */
public final class FollowerSimulator {

    /** One account's day: intents executed, skipped and rejected, positions closed and their net. */
    public record DayResult(String account, int executed, int skipped, int rejected, int closed, double net,
                            Map<String, Integer> reasons) {
    }

    private record Pending(long intentId, String mode, String action, String strategyId, String underlying,
                           String side, long token, String symbol, int lotSize, double price, double budget,
                           double referenceCapital, int lots, String legGroup, Instant at, String reason,
                           double sizeMultiplier, double strategyLots, double intendedLots, double liquidityLots,
                           double rvol, double callAsk, double putAsk, double bookQuantity) {
    }

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final CostModel costs;
    private final FollowerRisk risk;
    private final double simulatedBrokerFunds;
    private final QuoteSource quotes;            // null: capital-refused adds are not retried

    public FollowerSimulator(DataSource dataSource, CostModel costs, FollowerRisk risk, double simulatedBrokerFunds) {
        this(dataSource, costs, risk, simulatedBrokerFunds, null);
    }

    /** With {@code quotes}, a capital-refused add is retried when the account's capital frees (see {@link #retry}). */
    public FollowerSimulator(DataSource dataSource, CostModel costs, FollowerRisk risk, double simulatedBrokerFunds,
                             QuoteSource quotes) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.accounts = new AccountStore(dataSource);
        this.costs = costs;
        this.risk = risk;
        this.simulatedBrokerFunds = simulatedBrokerFunds;
        this.quotes = quotes;
    }

    /** A capital-refused add waiting for capital: the add's decision and the position it belongs to. */
    private record Retry(Pending add, long positionId) {
    }

    /** The state of one account's day while its intents run. */
    private final class Day {
        final BrokerAccount account;
        final LocalDate date;
        final CapitalPolicy policy = new CapitalPolicy(risk.limits());
        final double equity;
        boolean dailyLossLatched;
        final List<Retry> retries = new ArrayList<>();
        final Map<String, Integer> retried = new LinkedHashMap<>();

        Day(BrokerAccount account, LocalDate date) {
            this.account = account;
            this.date = date;
            this.equity = CapitalPolicy.equity(account, accounts.capitalMovements(account.id(), date),
                    realisedNet(account.id(), account.equityFrom(), date));
        }

        double todayNet() {
            return realisedNet(account.id(), date, date.plusDays(1));
        }
    }

    public DayResult run(BrokerAccount account, long copyGroupId, LocalDate date) {
        Day day = new Day(account, date);
        int executed = 0, skipped = 0, rejected = 0, closed = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        List<Pending> pending = pending(account.id(), copyGroupId, date);
        Set<Long> handled = new HashSet<>();
        for (Pending p : pending) {
            if (!handled.add(p.intentId())) {
                continue;                                          // the second leg of a straddle already handled
            }
            List<String> outcomes = new ArrayList<>();
            if (p.legGroup() != null && p.action().equals("ENTER")) {
                Pending other = pending.stream().filter(q -> q.intentId() != p.intentId() && p.legGroup().equals(q.legGroup())
                        && q.action().equals("ENTER")).findFirst().orElse(null);
                if (other != null) {
                    handled.add(other.intentId());
                }
                outcomes.addAll(enterStraddle(day, p, other));
            } else {
                outcomes.add(switch (p.action()) {
                    case "ENTER" -> enter(day, p);
                    case "ADD" -> add(day, p);
                    default -> exit(day, p);
                });
            }
            for (String outcome : outcomes) {
                reasons.merge(outcome, 1, Integer::sum);
                if (outcome.equals("EXECUTED")) {
                    executed++;
                    if (p.action().equals("EXIT")) {
                        closed++;
                    }
                } else if (outcome.startsWith("SKIPPED")) {
                    skipped++;
                } else {
                    rejected++;
                }
            }
        }
        // a retried add counts once as refused (when refused) and once more here when it later filled
        day.retried.forEach((outcome, n) -> reasons.merge(outcome, n, Integer::sum));
        executed += day.retried.getOrDefault("EXECUTED_RETRY", 0);
        rejected -= day.retried.getOrDefault("EXECUTED_RETRY", 0);
        return new DayResult(account.displayName(), executed, skipped, rejected, closed, day.todayNet(), reasons);
    }

    // ---------------------------------------------------------------- entries

    private String enter(Day day, Pending p) {
        if (openPosition(day.account.id(), p.strategyId(), p.underlying(), null) != null) {
            return skip(p, "ALREADY_HELD", "the account already holds this strategy's position");
        }
        double price = buyPrice(p.price());
        double budget = scaledBudget(day, p);
        double free = day.equity - openPremium(day.account.id());
        double boost = risk.boost(p.rvol());
        if (boost > 1) {
            budget = Math.max(budget, Math.min(budget * boost, 0.97 * free));          // risk v17: spare capital only
        }
        if (!(price > 0) || budget < p.lotSize() * price) {
            return reject(p, "SIZE", String.format("budget %.0f is under one lot (%.0f)", budget, p.lotSize() * price));
        }
        double scale = p.intendedLots() > 0 && p.strategyLots() > 0
                ? budget / (p.intendedLots() * p.lotSize() * price) : Double.NaN;
        long lots = Double.isNaN(scale) ? (long) Math.floor(budget / (p.lotSize() * price))
                : Math.max(1, (long) Math.floor(p.strategyLots() * scale));
        double step = day.account.sizeStepPct();
        if (step == 0) {
            lots = 1;                                                                   // the one-lot pilot
        } else if (step < 100) {
            lots = (long) Math.floor(lots * step / 100);
            scale = scale * step / 100;
        }
        if (p.liquidityLots() > 0) {
            lots = Math.min(lots, (long) p.liquidityLots());                            // what the book allowed
        }
        if (p.bookQuantity() >= 0) {                                                    // replay depth ceiling
            if (p.bookQuantity() < p.lotSize()) {
                return skip(p, "BOOK_EMPTY", "the signal account got no fill in this book");
            }
            lots = Math.min(lots, (long) p.bookQuantity() / p.lotSize());
        }
        if (lots < 1) {
            return reject(p, "SIZE", "under one lot after the size step");
        }
        long quantity = lots * p.lotSize();
        String refused = check(day, p, false, 1, lots, quantity * price);
        if (refused != null) {
            return refused;
        }
        order(day.account, p, "ENTRY", "BUY", quantity, price);
        insertPosition(day, p, p.side(), quantity, price, step == 0 ? Double.NaN : scale);
        return done(p, quantity);
    }

    private String add(Day day, Pending p) {
        Map<String, Object> position = openPosition(day.account.id(), p.strategyId(), p.underlying(), null);
        if (position == null) {
            return skip(p, "NOT_HELD", "no position to add to");
        }
        if (day.account.sizeStepPct() == 0) {
            return skip(p, "PILOT_NO_ADD", "the one-lot pilot takes no adds");
        }
        Object scaleValue = position.get("sizing_scale");
        double scale = scaleValue == null ? Double.NaN : ((Number) scaleValue).doubleValue();
        if (Double.isNaN(scale) || !(p.strategyLots() > 0)) {
            return skip(p, "NO_SCALE", "the position's entry scale or the add's size is unknown");
        }
        long lots = Math.max(1, (long) Math.floor(p.strategyLots() * scale));
        long held = ((Number) position.get("quantity_open")).longValue();
        if (p.bookQuantity() >= 0) {                                    // replay depth ceiling: entry + adds
            lots = Math.min(lots, (long) (p.bookQuantity() - held) / p.lotSize());
            if (lots < 1) {
                return skip(p, "BOOK_LIMIT", "the signal account's fills leave no room for this add");
            }
        }
        long quantity = lots * p.lotSize();
        double price = buyPrice(p.price());
        String refused = check(day, p, true, 0, lots, quantity * price);
        if (refused != null) {
            if (refused.equals("REJECTED_CAPITAL") && quotes != null) {
                day.retries.add(new Retry(p, ((Number) position.get("id")).longValue()));
            }
            return refused;
        }
        double average = ((Number) position.get("average_entry")).doubleValue();
        long total = held + quantity;
        double charges = charge(day.date, p.underlying(), Side.BUY, price, quantity);
        order(day.account, p, "ENTRY", "BUY", quantity, price);
        jdbc.update("update exec.position set quantity_open = ?, average_entry = ?, charges = charges + ? where id = ?",
                total, (average * held + price * quantity) / total, charges, ((Number) position.get("id")).longValue());
        return done(p, quantity);
    }

    private List<String> enterStraddle(Day day, Pending first, Pending second) {
        if (second == null) {
            return List.of(skip(first, "STRADDLE_LEG_MISSING", "the other leg of the straddle was not published"));
        }
        Pending ce = "CE".equals(first.side()) ? first : second;
        Pending pe = ce == first ? second : first;
        if (openPosition(day.account.id(), ce.strategyId(), ce.underlying(), "CE") != null
                || openPosition(day.account.id(), pe.strategyId(), pe.underlying(), "PE") != null) {
            return List.of(skip(ce, "ALREADY_HELD", "the account already holds this straddle"),
                    skip(pe, "ALREADY_HELD", "the account already holds this straddle"));
        }
        double ceAsk = buyPrice(ce.price()), peAsk = buyPrice(pe.price());
        double perLot = (ceAsk + peAsk) * ce.lotSize();
        double free = day.equity - openPremium(day.account.id());
        long lots = perLot > 0 ? (long) Math.floor(Math.min(scaledBudget(day, ce), free) / perLot) : 0;
        double step = day.account.sizeStepPct();
        if (step == 0 && lots >= 1) {
            lots = 1;
        } else if (step > 0 && step < 100) {
            lots = (long) Math.floor(lots * step / 100);
        }
        for (Pending leg : List.of(ce, pe)) {                            // replay depth ceiling, per leg
            if (leg.bookQuantity() >= 0) {
                lots = Math.min(lots, (long) leg.bookQuantity() / leg.lotSize());
            }
        }
        if (lots < 1) {
            return List.of(reject(ce, "SIZE", "the straddle budget buys no lot pair"),
                    reject(pe, "SIZE", "the straddle budget buys no lot pair"));
        }
        String refused = check(day, ce, false, 2, 2 * lots, lots * perLot);
        if (refused != null) {
            reject(pe, refused.replace("REJECTED_", ""), "the straddle's other leg was refused");
            return List.of(refused, refused);
        }
        long quantity = lots * ce.lotSize();
        order(day.account, ce, "ENTRY", "BUY", quantity, ceAsk);
        order(day.account, pe, "ENTRY", "BUY", quantity, peAsk);
        insertPosition(day, ce, "CE", quantity, ceAsk, Double.NaN);
        insertPosition(day, pe, "PE", quantity, peAsk, Double.NaN);
        return List.of(done(ce, quantity), done(pe, quantity));
    }

    /** The risk engine's checks in its order; null = approved, else the REJECTED_ outcome (intent updated). */
    private String check(Day day, Pending p, boolean add, int newPositions, long lots, double premium) {
        if (day.dailyLossLatched) {
            return reject(p, "DAILY_LOSS", "the daily loss limit was reached earlier today");
        }
        EntryDecision capital = day.policy.checkEntry(TradeMode.valueOf(p.mode()), day.equity, premium,
                openPremium(day.account.id()), day.todayNet(), funds(p.mode()));
        if (!capital.approved() && capital.code().equals("DAILY_LOSS")) {
            day.dailyLossLatched = true;
            return reject(p, capital.code(), capital.reason());
        }
        if (!add && openPositions(day.account.id()) + newPositions > risk.maxOpenPositions()) {
            return reject(p, "MAX_POSITIONS", "max open positions " + risk.maxOpenPositions());
        }
        if (lotsHeld(day.account.id(), p.underlying()) + lots > risk.maxLotsPerUnderlying()) {
            return reject(p, "MAX_LOTS", "max lots per underlying " + risk.maxLotsPerUnderlying());
        }
        if (!capital.approved()) {
            return reject(p, capital.code(), capital.reason());
        }
        return null;
    }

    private double scaledBudget(Day day, Pending p) {
        if (!(p.budget() > 0) || !(p.referenceCapital() > 0)) {
            return 0;
        }
        return p.budget() * (day.equity / p.referenceCapital()) * p.sizeMultiplier();
    }

    // ---------------------------------------------------------------- exits

    private String exit(Day day, Pending p) {
        String legSide = p.legGroup() != null && p.legGroup().startsWith("STRADDLE:") ? p.legGroup().substring(9) : null;
        Map<String, Object> position = openPosition(day.account.id(), p.strategyId(), p.underlying(), legSide);
        if (position == null) {
            return skip(p, "NOT_HELD", "the account holds no position to exit");
        }
        if (!(p.price() > 0)) {
            return reject(p, "NO_EXIT_PRICE", "the signal's exit price is unknown");
        }
        long quantity = ((Number) position.get("quantity_open")).longValue();
        double price = sellPrice(p.price());
        double average = ((Number) position.get("average_entry")).doubleValue();
        double charges = charge(day.date, p.underlying(), Side.SELL, price, quantity);
        order(day.account, p, "EXIT", "SELL", quantity, price);
        long positionId = ((Number) position.get("id")).longValue();
        jdbc.update("update exec.position set quantity_open = 0, closed_at = ?, exit_reason = ?, realised_gross = ?, "
                        + "charges = charges + ?, status = 'CLOSED' where id = ?", Timestamp.from(p.at()), p.reason(),
                (price - average) * quantity, charges, positionId);
        String outcome = done(p, quantity);
        day.retries.removeIf(r -> r.positionId() == positionId);       // its pending adds die with the position
        retry(day, p.at());                                            // this exit freed capital
        return outcome;
    }

    /**
     * Capital-refused adds, retried when an exit frees capital, as PAPER-1's strategies re-ask every snapshot: each
     * pending add whose position is still open is re-sized at the position's scale (and the replay depth ceiling),
     * priced at the contract's ask at that moment (stored ticks), and checked again; a retry still refused for capital
     * stays pending, any other refusal or a missing quote drops it.
     */
    private void retry(Day day, Instant at) {
        for (Retry r : List.copyOf(day.retries)) {
            Pending p = r.add();
            Map<String, Object> position = jdbc.queryForList("select id, quantity_open, average_entry, sizing_scale, status "
                    + "from exec.position where id = ?", r.positionId()).getFirst();
            if (!"OPEN".equals(position.get("status"))) {
                day.retries.remove(r);
                continue;
            }
            double ask = quotes.askAt(day.date, p.underlying(), p.token(), at);
            if (!(ask > 0)) {
                day.retries.remove(r);
                day.retried.merge("RETRY_NO_QUOTE", 1, Integer::sum);
                continue;
            }
            double scale = ((Number) position.get("sizing_scale")).doubleValue();
            long held = ((Number) position.get("quantity_open")).longValue();
            long lots = Math.max(1, (long) Math.floor(p.strategyLots() * scale));
            if (p.bookQuantity() >= 0) {
                lots = Math.min(lots, (long) (p.bookQuantity() - held) / p.lotSize());
            }
            if (lots < 1) {
                day.retries.remove(r);
                continue;
            }
            long quantity = lots * p.lotSize();
            double price = buyPrice(ask);
            String refused = check(day, p, true, 0, lots, quantity * price);
            if (refused != null) {
                if (!refused.equals("REJECTED_CAPITAL")) {
                    day.retries.remove(r);
                }
                continue;
            }
            double average = ((Number) position.get("average_entry")).doubleValue();
            long total = held + quantity;
            order(day.account, p, "ENTRY", "BUY", quantity, price);
            jdbc.update("update exec.position set quantity_open = ?, average_entry = ?, charges = charges + ? where id = ?",
                    total, (average * held + price * quantity) / total,
                    charge(day.date, p.underlying(), Side.BUY, price, quantity), r.positionId());
            jdbc.update("update exec.trade_intent set state = 'EXECUTED', quantity = ?, attempts = attempts + 1, "
                    + "reject_code = 'CAPITAL_RETRIED', reject_reason = ?, updated_at = now() where id = ?", quantity,
                    "filled on retry at " + at + " for " + price, p.intentId());
            day.retries.remove(r);
            day.retried.merge("EXECUTED_RETRY", 1, Integer::sum);
        }
    }

    // ---------------------------------------------------------------- records

    private void insertPosition(Day day, Pending p, String side, long quantity, double price, double scale) {
        jdbc.update("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, strategy_id, underlying, "
                        + "option_side, instrument_token, symbol, quantity_open, average_entry, opened_at, charges, status, "
                        + "sizing_scale) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)", day.account.tenantId(),
                day.account.id(), p.intentId(), p.strategyId(), p.underlying(), side, p.token(), p.symbol(), quantity,
                price, Timestamp.from(p.at()), charge(day.date, p.underlying(), Side.BUY, price, quantity),
                Double.isNaN(scale) ? null : scale);
    }

    private void order(BrokerAccount account, Pending p, String role, String side, long quantity, double price) {
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                        + "client_tag, attempt_no, order_side, order_type, quantity_requested, quantity_filled, limit_price, "
                        + "average_fill_price, status, raw_status) values (?, ?, ?, ?, ?, ?, 1, ?, 'LIMIT', ?, ?, ?, ?, "
                        + "'FILLED', ?)", account.tenantId(), p.intentId(), account.id(), account.brokerType().name(), role,
                "F" + p.intentId() + "A1", side, quantity, quantity, price, price,
                p.mode().equals("DRY_RUN") ? "DRY_RUN_SIMULATED" : "PAPER_SIMULATED");
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

    // ---------------------------------------------------------------- queries

    /** The open position of a strategy on an underlying; {@code side} narrows to one straddle leg. */
    private Map<String, Object> openPosition(long accountId, String strategyId, String underlying, String side) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, entry_trade_intent_id, quantity_open, average_entry, "
                + "sizing_scale from exec.position where broker_account_id = ? and strategy_id = ? and underlying = ? "
                + "and status = 'OPEN' and (?::text is null or option_side = ?) order by id limit 1", accountId, strategyId,
                underlying, side, side);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private double openPremium(long accountId) {
        Double premium = jdbc.queryForObject("select coalesce(sum(quantity_open * average_entry), 0) from exec.position "
                + "where broker_account_id = ? and status = 'OPEN'", Double.class, accountId);
        return premium == null ? 0 : premium;
    }

    private int openPositions(long accountId) {
        Integer open = jdbc.queryForObject("select count(*) from exec.position where broker_account_id = ? "
                + "and status = 'OPEN'", Integer.class, accountId);
        return open == null ? 0 : open;
    }

    /** Lots held in an underlying, each position's quantity over its contract's lot size. */
    private long lotsHeld(long accountId, String underlying) {
        Long lots = jdbc.queryForObject("select coalesce(sum(p.quantity_open / nullif(d.lot_size, 0)), 0) from exec.position p "
                + "join exec.trade_intent i on i.id = p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                + "join exec.signal_decision d on d.id = b.signal_decision_id where p.broker_account_id = ? "
                + "and p.underlying = ? and p.status = 'OPEN'", Long.class, accountId, underlying);
        return lots == null ? 0 : lots;
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
                + "d.lots, d.leg_group, d.decided_at, d.reason, coalesce(s.size_multiplier, 1) as size_multiplier, "
                + "(d.context->>'strategy_lots')::float8 as strategy_lots, (d.context->>'intended_lots')::float8 as intended_lots, "
                + "(d.context->>'liquidity_lots')::float8 as liquidity_lots, (d.context->>'rvol_tod')::float8 as rvol_tod, "
                + "(d.context->>'call_ask')::float8 as call_ask, (d.context->>'put_ask')::float8 as put_ask, "
                // replay depth ceiling: what the signal account's own position got filled through the same book
                + "(select tp.quantity from trade.position tp where tp.session_id = d.signal_session_id "
                + "and tp.strategy_id = d.strategy_id and tp.underlying = d.underlying and tp.symbol = d.symbol "
                + "and tp.opened_at <= d.decided_at + interval '60 seconds' "
                + "and (tp.closed_at is null or tp.closed_at >= d.decided_at) "
                + "order by abs(extract(epoch from tp.opened_at - d.decided_at)) limit 1) as book_quantity "
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

    /** -1 when the signal session's position is not found (no ceiling, e.g. decisions written without a session). */
    private static double bookQuantity(ResultSet rs) throws SQLException {
        long quantity = rs.getLong("book_quantity");
        return rs.wasNull() ? -1 : quantity;
    }

    private static Pending pendingRow(ResultSet rs, int i) throws SQLException {
        return new Pending(rs.getLong("id"), rs.getString("execution_mode"), rs.getString("action"),
                rs.getString("strategy_id"), rs.getString("underlying"), rs.getString("option_side"),
                rs.getLong("instrument_token"), rs.getString("symbol"), rs.getInt("lot_size"),
                orNaN(rs, "reference_price"), orNaN(rs, "premium_budget"), orNaN(rs, "reference_capital"),
                rs.getInt("lots"), rs.getString("leg_group"), rs.getTimestamp("decided_at").toInstant(),
                rs.getString("reason"), rs.getDouble("size_multiplier"), orNaN(rs, "strategy_lots"),
                orNaN(rs, "intended_lots"), orNaN(rs, "liquidity_lots"), orNaN(rs, "rvol_tod"), orNaN(rs, "call_ask"),
                orNaN(rs, "put_ask"), bookQuantity(rs));
    }
}
