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
import com.autotrade.broker.BrokerException;
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

    private record Pending(long intentId, long copyGroupId, String mode, String action, String strategyId, String underlying,
                           String side, long token, String instrumentKey, String symbol, int lotSize, double price,
                           double budget, double referenceCapital, int lots, String legGroup, Instant at, String reason,
                           double sizeMultiplier, double strategyLots, double intendedLots, double liquidityLots,
                           double rvol, double callAsk, double putAsk, double bookQuantity, double stopPct) {
    }

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final CostModel costs;
    private final FollowerRisk risk;
    private final FollowerVenue venue;
    private final QuoteSource quotes;            // null: capital-refused adds are not retried
    private final KillSwitches kills;

    public FollowerSimulator(DataSource dataSource, CostModel costs, FollowerRisk risk, double simulatedBrokerFunds) {
        this(dataSource, costs, risk, simulatedBrokerFunds, null);
    }

    /** With {@code quotes}, a capital-refused add is retried when the account's capital frees (see {@link #retry}). */
    public FollowerSimulator(DataSource dataSource, CostModel costs, FollowerRisk risk, double simulatedBrokerFunds,
                             QuoteSource quotes) {
        this(dataSource, costs, risk, new SimulatedVenue(dataSource, simulatedBrokerFunds), quotes);
    }

    /** Orders through {@code venue} (a {@link BrokerVenue} for a broker-routed account). */
    public FollowerSimulator(DataSource dataSource, CostModel costs, FollowerRisk risk, FollowerVenue venue,
                             QuoteSource quotes) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.accounts = new AccountStore(dataSource);
        this.costs = costs;
        this.risk = risk;
        this.venue = venue;
        this.quotes = quotes;
        this.kills = new KillSwitches(dataSource);
    }

    private String workerId;                     // set by an execution worker: claim intents before running them
    private Long leaseEpoch;                     // the worker's lease epoch, recorded on every order (fencing)
    private final Map<String, Day> days = new java.util.HashMap<>();   // per account and date, across worker ticks
    private final Set<String> protectionChecked = new HashSet<>();       // account:date whose stops were verified

    /** Makes this an execution worker's simulator: intents are claimed by {@code id} and orders carry the epoch. */
    public FollowerSimulator asWorker(String id, long epoch) {
        this.workerId = id;
        this.leaseEpoch = epoch;
        return this;
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

    /**
     * Runs the account's not yet run intents of the day. Called once for a replayed day, or on every worker tick: the
     * day's state (the daily loss latch, pending retries) lives on between calls and is rebuilt from the database the
     * first time (after a restart).
     */
    public DayResult run(BrokerAccount account, long copyGroupId, LocalDate date) {
        Day day = days.computeIfAbsent(account.id() + ":" + date, k -> restore(new Day(account, date), copyGroupId));
        int executed = 0, skipped = 0, rejected = 0, closed = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        boolean sessionLost = venue.broker() && sessionLost(account);
        if (venue.broker() && !sessionLost) {
            try {
                for (FollowerVenue.StopFill stop : venue.stopFills(account)) {   // stops that sold while held
                    book(day, stop.positionId(), stop.quantity(), stop.price(), stop.at(), "BROKER_STOP");
                    closed++;
                }
            } catch (BrokerException e) {
                if (e.kind() == BrokerException.Kind.SESSION) {
                    sessionLost = true;
                    loseSession(account, e.getMessage());
                } else {
                    reasons.merge("STOP_READ_" + e.kind(), 1, Integer::sum);     // read again next turn
                }
            }
        }
        if (venue.broker() && !sessionLost && protectionChecked.add(account.id() + ":" + date)) {
            sessionLost = ensureProtected(day);             // recovery: every open position has its broker stop
        }
        List<Pending> pending = pending(account.id(), copyGroupId, date);
        Set<Long> handled = new HashSet<>();
        for (Pending p : pending) {
            if (!handled.add(p.intentId())) {
                continue;                                          // the second leg of a straddle already handled
            }
            List<String> outcomes = new ArrayList<>();
            if (sessionLost) {
                if (!p.action().equals("EXIT")) {                   // entries refused; exits wait for the next login
                    outcomes.add(reject(p, "BROKER_SESSION", "the broker session is not valid: log in again"));
                } else {
                    outcomes.add("PENDING_BROKER_SESSION");
                }
            } else {
                java.util.concurrent.locks.ReentrantLock tenantLock = risk.combined() != null && venue.broker()
                        && !p.action().equals("EXIT") ? TENANT_ENTRY_LOCKS.computeIfAbsent(account.tenantId(),
                        t -> new java.util.concurrent.locks.ReentrantLock()) : null;
                if (tenantLock != null) {
                    tenantLock.lock();
                }
                try {
                    if (p.legGroup() != null && p.action().equals("ENTER")) {
                        Pending other = pending.stream().filter(q -> q.intentId() != p.intentId()
                                && p.legGroup().equals(q.legGroup()) && q.action().equals("ENTER")).findFirst().orElse(null);
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
                } catch (BrokerException e) {
                    if (e.kind() != BrokerException.Kind.SESSION) {
                        throw e;
                    }
                    sessionLost = true;
                    loseSession(account, e.getMessage());
                    // an exit, or an entry with an order already at the broker, waits for the next login (it resumes
                    // by tag); an entry that sent nothing is refused
                    outcomes.add(p.action().equals("EXIT") || hasOrders(p.intentId()) ? "PENDING_BROKER_SESSION"
                            : reject(p, "BROKER_SESSION", e.getMessage()));
                } finally {
                    if (tenantLock != null) {
                        tenantLock.unlock();
                    }
                }
            }
            for (String outcome : outcomes) {
                if (outcome.startsWith("PENDING")) {
                    reasons.merge(outcome, 1, Integer::sum);           // left for the next turn, nothing changed
                    continue;
                }
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

    /** A day met again after a restart: the latched daily loss and the adds still waiting for capital. */
    private Day restore(Day day, long copyGroupId) {
        Integer latched = jdbc.queryForObject("select count(*) from exec.trade_intent i join exec.trade_batch b "
                + "on b.id = i.trade_batch_id join exec.signal_decision d on d.id = b.signal_decision_id "
                + "where i.broker_account_id = ? and d.session_date = ? and i.reject_code = 'DAILY_LOSS'", Integer.class,
                day.account.id(), Date.valueOf(day.date));
        day.dailyLossLatched = latched != null && latched > 0;
        if (quotes != null) {
            for (Pending add : query(PENDING + "where i.broker_account_id = ? and d.copy_group_id = ? and d.session_date = ? "
                    + "and i.state = 'REJECTED' and i.reject_code = 'CAPITAL' and d.action = 'ADD' "
                    + "order by d.decided_at, d.priority, d.id", day.account.id(), copyGroupId, Date.valueOf(day.date))) {
                Map<String, Object> position = openPosition(day.account.id(), add.strategyId(), add.underlying(), null);
                if (position != null) {
                    day.retries.add(new Retry(add, ((Number) position.get("id")).longValue()));
                }
            }
        }
        return day;
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
            lots = Math.min(lots, (long) Math.floor(p.liquidityLots() * liquidityShare(day, p)));   // what the book allowed
            if (lots < 1) {
                return reject(p, "LIQUIDITY_SHARE", "this account's share of the book is under one lot");
            }
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
        FollowerVenue.Fill fill = order(day.account, p, "ENTRY", "BUY", quantity, price, 1);
        if (fill.quantity() == 0) {
            return notFilled(day, p, fill);
        }
        long positionId = insertPosition(day, p, p.side(), fill, step == 0 ? Double.NaN : scale);
        protect(day, p, positionId);
        reconcileIfUnknown(day, p, fill);
        return done(p, fill.quantity());
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
        if (risk.combined() != null && risk.combined().shareLiquidity() && venue.broker() && p.liquidityLots() > 0) {
            lots = Math.min(lots, (long) Math.floor(p.liquidityLots() * liquidityShare(day, p)));   // v4: the add's book share
            if (lots < 1) {
                return skip(p, "LIQUIDITY_SHARE", "this account's share of the add's book is under one lot");
            }
        }
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
        FollowerVenue.Fill fill = order(day.account, p, "ENTRY", "BUY", quantity, price, 1);
        if (fill.quantity() == 0) {
            return notFilled(day, p, fill);
        }
        long positionId = ((Number) position.get("id")).longValue();
        addToPosition(day, p, positionId, held, ((Number) position.get("average_entry")).doubleValue(), fill);
        protect(day, p, positionId);                                   // the stop re-sized to the new quantity
        reconcileIfUnknown(day, p, fill);
        return done(p, fill.quantity());
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
        List<String> outcomes = new ArrayList<>();
        for (Pending leg : List.of(ce, pe)) {                            // a leg that does not fill leaves the other held
            FollowerVenue.Fill fill = order(day.account, leg, "ENTRY", "BUY", quantity, leg == ce ? ceAsk : peAsk, 1);
            if (fill.quantity() == 0) {
                outcomes.add(notFilled(day, leg, fill));
                continue;
            }
            long positionId = insertPosition(day, leg, leg.side(), fill, Double.NaN);
            protect(day, leg, positionId);
            reconcileIfUnknown(day, leg, fill);
            outcomes.add(done(leg, fill.quantity()));
        }
        return outcomes;
    }

    /** The risk engine's checks in its order; null = approved, else the REJECTED_ outcome (intent updated). */
    private String check(Day day, Pending p, boolean add, int newPositions, long lots, double premium) {
        String killed = kills.engaged(day.account, p.strategyId(), p.instrumentKey());
        if (killed != null) {
            return reject(p, "KILL_SWITCH", killed);
        }
        if (day.dailyLossLatched) {
            return reject(p, "DAILY_LOSS", "the daily loss limit was reached earlier today");
        }
        String combined = combinedRefusal(day, p, lots, premium);
        if (combined != null) {
            return reject(p, combined.substring(0, combined.indexOf(':')), combined.substring(combined.indexOf(':') + 2));
        }
        EntryDecision capital = day.policy.checkEntry(TradeMode.valueOf(p.mode()), day.equity, premium,
                openPremium(day.account.id()), day.todayNet(), funds(day.account, p.mode()));
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

    /** The tenant's broker-routed accounts (DRY_RUN, LIVE) that the combined limits cover. */
    private static final String TENANT_BROKER_ACCOUNTS = "select id from acct.broker_account where tenant_id = ? "
            + "and enabled and account_role = 'FOLLOWER_TRADE' and trade_mode = ?";
    /** The same, only those that can take the trade now: connected and not killed. */
    private static final String TENANT_BROKER_ACCOUNTS_TAKING = TENANT_BROKER_ACCOUNTS
            + " and connection_status not in ('TOKEN_EXPIRED', 'TOKEN_REJECTED', 'DISCONNECTED') and not exists (select 1 "
            + "from exec.kill_switch k where k.scope = 'ACCOUNT' and k.broker_account_id = acct.broker_account.id "
            + "and k.released_at is null)";
    /** One entry at a time per tenant in this worker while combined limits apply (check, order and booking together). */
    private static final Map<Long, java.util.concurrent.locks.ReentrantLock> TENANT_ENTRY_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * L6 cross-account limits (risk v4 {@code combined}, broker-routed accounts only, counted per trade mode so a DRY_RUN
     * rehearsal never uses a LIVE account's room): COMBINED_PREMIUM when the tenant's premium in use + this entry exceeds
     * the % of their combined allocation, COMBINED_LOTS when lots of this contract across them would exceed the cap;
     * null = within. Entries of a tenant are serialised in this worker while these apply ({@link #TENANT_ENTRY_LOCKS});
     * a tenant's accounts must be served by one worker.
     */
    private String combinedRefusal(Day day, Pending p, long lots, double premium) {
        FollowerRisk.Combined c = risk.combined();
        if (c == null || !venue.broker()) {
            return null;
        }
        Map<String, Object> t = jdbc.queryForMap("select coalesce(sum(p.quantity_open * p.average_entry), 0) premium, "
                        + "coalesce(sum(case when p.instrument_key = ? then p.quantity_open else 0 end), 0) contract_quantity, "
                        + "(select coalesce(sum(allocated_capital), 0) from acct.broker_account where id in ("
                        + TENANT_BROKER_ACCOUNTS + ")) allocations from exec.position p where p.status = 'OPEN' "
                        + "and p.broker_account_id in (" + TENANT_BROKER_ACCOUNTS + ")", p.instrumentKey(),
                day.account.tenantId(), day.account.tradeMode().name(), day.account.tenantId(),
                day.account.tradeMode().name());
        double inUse = ((Number) t.get("premium")).doubleValue();
        double allowed = ((Number) t.get("allocations")).doubleValue() * c.maxOpenPremiumPct() / 100;
        if (inUse + premium > allowed) {
            return String.format(java.util.Locale.ROOT, "COMBINED_PREMIUM: the tenant's broker accounts hold %.0f, + %.0f "
                    + "is over %.0f", inUse, premium, allowed);
        }
        long heldLots = ((Number) t.get("contract_quantity")).longValue() / Math.max(1, p.lotSize());
        if (heldLots + lots > c.maxLotsPerContract()) {
            return "COMBINED_LOTS: " + heldLots + " lots of " + p.symbol() + " held across the tenant's accounts, + " + lots
                    + " is over " + c.maxLotsPerContract();
        }
        return null;
    }

    /**
     * L6 liquidity allocator (risk v4 {@code share_liquidity}): the account's share of a decision's usable depth = its
     * allocation over the allocations of the tenant's broker-routed accounts of the same mode subscribed to the strategy in
     * the copy group that can take it now (connected, not killed): several accounts buying one strike at the same second
     * compete for one book. 1 otherwise.
     */
    private double liquidityShare(Day day, Pending p) {
        FollowerRisk.Combined c = risk.combined();
        if (c == null || !c.shareLiquidity() || !venue.broker()) {
            return 1;
        }
        Double all = jdbc.queryForObject("select coalesce(sum(a.allocated_capital), 0) from acct.broker_account a "
                + "join acct.strategy_subscription s on s.broker_account_id = a.id and s.enabled where s.copy_group_id = ? "
                + "and s.strategy_id = ? and a.id in (" + TENANT_BROKER_ACCOUNTS_TAKING + ")", Double.class, p.copyGroupId(),
                p.strategyId(), day.account.tenantId(), day.account.tradeMode().name());
        return all == null || all <= 0 ? 1 : Math.min(1, day.account.allocatedCapital() / all);
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
        if (!(p.price() > 0) && !venue.broker()) {
            return reject(p, "NO_EXIT_PRICE", "the signal's exit price is unknown");
        }
        long positionId = ((Number) position.get("id")).longValue();
        long quantity = ((Number) position.get("quantity_open")).longValue();
        // plan section 3, exit sequencing: the broker stop comes off first; if it already sold, nothing more is sent
        FollowerVenue.Release release = venue.release(day.account, positionId);
        if (release.retryLater()) {
            return "PENDING_STOP_RELEASE";
        }
        if (release.soldQuantity() > 0) {
            book(day, positionId, release.sold(), release.at(), "BROKER_STOP");
            quantity -= release.soldQuantity();
        }
        String outcome;
        if (quantity <= 0) {
            outcome = skip(p, "STOPPED_AT_BROKER", "the broker stop sold the position before this exit");
        } else {
            FollowerVenue.Fill fill = order(day.account, p, "EXIT", "SELL", quantity, sellPrice(p.price()), 1);
            if (fill.quantity() > 0) {
                book(day, positionId, fill.parts(), fill.at(), p.reason());
            }
            if (fill.quantity() < quantity) {
                // the chase ran out: the rest keeps a broker stop, the account takes no new entries, an alert goes out
                protect(day, p, positionId);
                kills.engageAccount(day.account, "EXIT_NOT_FILLED: " + (quantity - fill.quantity()) + " of " + p.symbol()
                        + " still held after the exit chase (" + fill.reason() + ")", "execution");
                return fail(p, fill.unknown() ? "ORDER_UNKNOWN" : "EXIT_NOT_FILLED", fill.reason());
            }
            outcome = done(p, quantity);
        }
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
            FollowerVenue.Fill fill = order(day.account, p, "ENTRY", "BUY", quantity, price, 2);
            if (fill.quantity() == 0) {
                day.retries.remove(r);
                continue;
            }
            addToPosition(day, p, r.positionId(), held, ((Number) position.get("average_entry")).doubleValue(), fill);
            protect(day, p, r.positionId());
            jdbc.update("update exec.trade_intent set state = 'EXECUTED', quantity = ?, attempts = attempts + 1, "
                    + "reject_code = 'CAPITAL_RETRIED', reject_reason = ?, updated_at = now() where id = ?", fill.quantity(),
                    "filled on retry at " + at + " for " + fill.price(), p.intentId());
            day.retries.remove(r);
            day.retried.merge("EXECUTED_RETRY", 1, Integer::sum);
        }
    }

    // ---------------------------------------------------------------- records

    /** The new position (its stop_pct: the signal's, else none); returns its id. */
    private long insertPosition(Day day, Pending p, String side, FollowerVenue.Fill fill, double scale) {
        return jdbc.queryForObject("insert into exec.position (tenant_id, broker_account_id, entry_trade_intent_id, "
                        + "strategy_id, underlying, option_side, instrument_token, instrument_key, symbol, quantity_open, "
                        + "average_entry, opened_at, charges, status, sizing_scale, stop_pct) values (?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?, 'OPEN', ?, ?) returning id", Long.class, day.account.tenantId(), day.account.id(),
                p.intentId(), p.strategyId(), p.underlying(), side, p.token(), p.instrumentKey(), p.symbol(), fill.quantity(),
                fill.price(), Timestamp.from(fill.at()), buyCharges(day, p, fill), Double.isNaN(scale) ? null : scale,
                Double.isNaN(p.stopPct()) ? null : p.stopPct());
    }

    private void addToPosition(Day day, Pending p, long positionId, long held, double average, FollowerVenue.Fill fill) {
        long total = held + fill.quantity();
        jdbc.update("update exec.position set quantity_open = ?, average_entry = ?, charges = charges + ? where id = ?",
                total, (average * held + fill.price() * fill.quantity()) / total, buyCharges(day, p, fill), positionId);
    }

    /**
     * Books a sale (its executed orders, each charged on its own): realised gross and charges added; the position closes
     * when nothing is left.
     */
    private void book(Day day, long positionId, List<FollowerVenue.Part> parts, Instant at, String reason) {
        Map<String, Object> pos = jdbc.queryForMap("select underlying, quantity_open, average_entry from exec.position "
                + "where id = ?", positionId);
        long open = ((Number) pos.get("quantity_open")).longValue();
        double average = ((Number) pos.get("average_entry")).doubleValue();
        String underlying = (String) pos.get("underlying");
        long sold = 0;
        double gross = 0, charges = 0;
        for (FollowerVenue.Part part : parts) {
            long q = Math.min(part.quantity(), open - sold);
            if (q <= 0) {
                break;
            }
            sold += q;
            gross += (part.price() - average) * q;
            charges += charge(day.date, underlying, Side.SELL, part.price(), q);
        }
        if (sold <= 0) {
            return;
        }
        boolean closes = sold == open;
        jdbc.update("update exec.position set quantity_open = quantity_open - ?, realised_gross = coalesce(realised_gross, 0) "
                        + "+ ?, charges = charges + ?, closed_at = case when ? then ? else closed_at end, exit_reason = case "
                        + "when ? then ? else exit_reason end, status = case when ? then 'CLOSED' else status end where id = ?",
                sold, gross, charges, closes, Timestamp.from(at), closes, reason, closes, positionId);
    }

    private void book(Day day, long positionId, long quantity, double price, Instant at, String reason) {
        book(day, positionId, List.of(new FollowerVenue.Part(quantity, price)), at, reason);
    }

    /** Charges of the buy orders of a fill, each child order on its own. */
    private double buyCharges(Day day, Pending p, FollowerVenue.Fill fill) {
        double total = 0;
        for (FollowerVenue.Part part : fill.parts()) {
            total += charge(day.date, p.underlying(), Side.BUY, part.price(), part.quantity());
        }
        return total;
    }

    /**
     * Arms (or re-sizes) the position's broker stop. A stop that sold in the meantime is booked first and the rest
     * re-protected. A stop that cannot be placed means an emergency exit (the long-option rule): every stop of the
     * position comes off first (so nothing is sold twice), then what is left is sold, and the account's entries stop.
     */
    private void protect(Day day, Pending p, long positionId) {
        try {
            protect(day, p, positionId, p.symbol());
        } catch (BrokerException e) {
            if (e.kind() != BrokerException.Kind.SESSION) {
                throw e;
            }
            // the fill is booked; the stop is placed at the next login (ensureProtected), entries wait till then
            loseSession(day.account, e.getMessage());
        }
    }

    /**
     * Every open position of the account has its broker stop (after a restart, a takeover or a login): stops are
     * re-armed where missing, sales by stops booked. Returns true when the session turned out to be lost.
     */
    private boolean ensureProtected(Day day) {
        for (Map<String, Object> pos : jdbc.queryForList("select id, symbol from exec.position where broker_account_id = ? "
                + "and status = 'OPEN' and quantity_open > 0 order by id", day.account.id())) {
            try {
                protect(day, null, ((Number) pos.get("id")).longValue(), (String) pos.get("symbol"));
            } catch (BrokerException e) {
                if (e.kind() != BrokerException.Kind.SESSION) {
                    throw e;
                }
                loseSession(day.account, e.getMessage());
                return true;
            }
        }
        return false;
    }

    private boolean hasOrders(long intentId) {
        Integer n = jdbc.queryForObject("select count(*) from exec.order_execution where trade_intent_id = ?", Integer.class,
                intentId);
        return n != null && n > 0;
    }

    /** {@code p} null (flatten): a stop that cannot be placed is reported, not followed by another exit. */
    private void protect(Day day, Pending p, long positionId, String symbol) {
        if (p == null) {
            FollowerVenue.Protection r = venue.protect(day.account, positionId, leaseEpoch);
            if (r.soldQuantity() > 0) {
                book(day, positionId, r.sold(), r.at(), "BROKER_STOP");
            }
            if (r.failure() != null) {
                kills.engageAccount(day.account, "flatten left " + symbol + " without a broker stop: " + r.failure(),
                        "flatten");
            }
            return;
        }
        for (int round = 0; round < 3; round++) {
            FollowerVenue.Protection r = venue.protect(day.account, positionId, leaseEpoch);
            if (r.soldQuantity() > 0) {
                book(day, positionId, r.sold(), r.at(), "BROKER_STOP");
                continue;
            }
            if (r.failure() == null) {
                return;
            }
            FollowerVenue.Release release = venue.release(day.account, positionId);
            if (release.retryLater()) {
                kills.engageAccount(day.account, "STOP_PLACEMENT_FAILED on " + p.symbol() + " (" + r.failure()
                        + ") and the stop's state is not confirmed: " + release.reason(), "execution");
                return;
            }
            if (release.soldQuantity() > 0) {
                book(day, positionId, release.sold(), release.at(), "BROKER_STOP");
            }
            long open = jdbc.queryForObject("select quantity_open from exec.position where id = ?", Long.class, positionId);
            FollowerVenue.Fill fill = open <= 0 ? null
                    : order(day.account, p, "EMERGENCY_EXIT", "SELL", open, sellPrice(p.price()), 1 + round);
            if (fill != null && fill.quantity() > 0) {
                book(day, positionId, fill.parts(), fill.at(), "STOP_PLACEMENT_FAILED");
            }
            long left = open - (fill == null ? 0 : fill.quantity());
            kills.engageAccount(day.account, "STOP_PLACEMENT_FAILED on " + p.symbol() + ": " + r.failure()
                    + (left > 0 ? "; " + left + " still held" : "; exited"), "execution");
            return;
        }
        kills.engageAccount(day.account, "the stop of " + p.symbol() + " kept selling while being re-armed", "execution");
    }

    private FollowerVenue.Fill order(BrokerAccount account, Pending p, String role, String side, long quantity, double price,
                                     int attempt) {
        return venue.execute(new FollowerVenue.OrderSpec(account, p.intentId(), p.mode(), role, side, p.underlying(),
                p.token(), p.instrumentKey(), p.symbol(), p.lotSize(), quantity, price, p.at(), attempt, leaseEpoch));
    }

    /** An order that filled nothing: refused (its code), or unknown at the broker (the account must be reconciled). */
    private String notFilled(Day day, Pending p, FollowerVenue.Fill fill) {
        if (fill.unknown()) {
            kills.engageAccount(day.account, "ORDER_UNKNOWN for intent " + p.intentId() + ": " + fill.reason(), "execution");
            jdbc.update("update exec.trade_intent set state = 'RECONCILIATION_REQUIRED', reject_code = ?, reject_reason = ?, "
                    + "updated_at = now() where id = ?", fill.code(), fill.reason(), p.intentId());
            return "RECONCILIATION_" + fill.code();
        }
        return reject(p, fill.code(), fill.reason());
    }

    /**
     * Flatten (live plan section 4, "Flatten all live"): every open position of the account is exited now, its broker
     * stop taken off first (what the stop already sold is booked, never sold twice). {@code price} gives the simulated
     * exit price of a position (a broker venue prices from the broker). The caller engages the kill switch first.
     * Returns one line per position.
     */
    public List<String> flatten(BrokerAccount account, LocalDate date, java.util.function.ToDoubleFunction<Map<String, Object>> price,
                                String reason) {
        Day day = new Day(account, date);
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> pos : jdbc.queryForList("select p.id, p.entry_trade_intent_id, p.strategy_id, p.underlying, "
                + "p.option_side, p.instrument_token, p.instrument_key, p.symbol, p.quantity_open, p.average_entry, "
                + "d.lot_size, i.execution_mode from exec.position p join exec.trade_intent i on i.id = p.entry_trade_intent_id "
                + "join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d on d.id = b.signal_decision_id "
                + "where p.broker_account_id = ? and p.status = 'OPEN' order by p.id", account.id())) {
            long positionId = ((Number) pos.get("id")).longValue();
            String symbol = (String) pos.get("symbol");
            FollowerVenue.Release release = venue.release(account, positionId);
            if (release.retryLater()) {
                lines.add(symbol + ": NOT flattened, " + release.reason());
                continue;
            }
            if (release.soldQuantity() > 0) {
                book(day, positionId, release.sold(), release.at(), "BROKER_STOP");
            }
            long open = jdbc.queryForObject("select quantity_open from exec.position where id = ?", Long.class, positionId);
            if (open <= 0) {
                lines.add(symbol + ": the broker stop had sold it");
                continue;
            }
            long intent = ((Number) pos.get("entry_trade_intent_id")).longValue();
            int attempt = jdbc.queryForObject("select coalesce(max(attempt_no), 0) + 1 from exec.order_execution "
                    + "where trade_intent_id = ? and role = 'EMERGENCY_EXIT'", Integer.class, intent);
            long token = ((Number) pos.get("instrument_token")).longValue();
            FollowerVenue.Fill fill = venue.execute(new FollowerVenue.OrderSpec(account, intent,
                    (String) pos.get("execution_mode"), "EMERGENCY_EXIT", "SELL", (String) pos.get("underlying"), token,
                    InstrumentKeys.normalize((String) pos.get("instrument_key"), token), symbol,
                    ((Number) pos.get("lot_size")).intValue(), open, price.applyAsDouble(pos), java.time.Instant.now(),
                    attempt, leaseEpoch));
            if (fill.quantity() > 0) {
                book(day, positionId, fill.parts(), fill.at(), reason);
            }
            if (fill.quantity() < open) {
                protect(day, null, positionId, symbol);
                lines.add(symbol + ": " + fill.quantity() + " of " + open + " sold (" + fill.code() + " " + fill.reason()
                        + "); the rest keeps its broker stop");
            } else {
                lines.add(symbol + ": " + open + " sold at " + String.format(java.util.Locale.ROOT, "%.2f", fill.price()));
            }
        }
        kills.audit(account, "flatten", "FLATTEN", Map.of("reason", reason, "positions", lines));
        return lines;
    }

    /** A child order whose end is not known (a cancel not confirmed): what filled is booked, the account reconciled. */
    private void reconcileIfUnknown(Day day, Pending p, FollowerVenue.Fill fill) {
        if (fill.unknown()) {
            kills.engageAccount(day.account, "ORDER_UNKNOWN for intent " + p.intentId() + " (" + fill.quantity()
                    + " booked): " + fill.reason(), "execution");
        }
    }

    private String fail(Pending p, String code, String reason) {
        jdbc.update("update exec.trade_intent set state = 'FAILED', reject_code = ?, reject_reason = ?, updated_at = now() "
                + "where id = ?", code, reason, p.intentId());
        return "FAILED_" + code;
    }

    /** True when the account's broker session is known to be invalid (set by {@link #loseSession}, cleared at login). */
    private boolean sessionLost(BrokerAccount account) {
        String status = jdbc.queryForObject("select connection_status from acct.broker_account where id = ?", String.class,
                account.id());
        return "TOKEN_EXPIRED".equals(status) || "TOKEN_REJECTED".equals(status) || "DISCONNECTED".equals(status);
    }

    private void loseSession(BrokerAccount account, String message) {
        protectionChecked.removeIf(k -> k.startsWith(account.id() + ":"));   // stops are verified again after the login
        jdbc.update("update acct.broker_account set connection_status = 'TOKEN_EXPIRED', updated_at = now() where id = ?",
                account.id());
        kills.audit(account, "execution", "BROKER_SESSION_LOST", Map.of("message", String.valueOf(message)));
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

    private double funds(BrokerAccount account, String mode) {
        return TradeMode.valueOf(mode).needsBrokerFunds() ? venue.funds(account) : Double.NaN;
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

    private static final String PENDING = "select i.id, d.copy_group_id, i.execution_mode, d.action, d.strategy_id, d.underlying, "
            + "d.option_side, d.instrument_token, d.instrument_key, d.symbol, d.lot_size, d.reference_price, d.premium_budget, "
            + "d.reference_capital, d.lots, d.leg_group, d.decided_at, d.reason, coalesce(s.size_multiplier, 1) as size_multiplier, "
            + "(d.context->>'strategy_lots')::float8 as strategy_lots, (d.context->>'intended_lots')::float8 as intended_lots, "
            + "(d.context->>'liquidity_lots')::float8 as liquidity_lots, (d.context->>'rvol_tod')::float8 as rvol_tod, "
            + "(d.context->>'call_ask')::float8 as call_ask, (d.context->>'put_ask')::float8 as put_ask, "
            + "(d.context->>'stop_pct')::float8 as stop_pct, "
            // replay depth ceiling: what the signal account's own position got filled through the same book
            + "(select tp.quantity from trade.position tp where tp.session_id = d.signal_session_id "
            + "and tp.strategy_id = d.strategy_id and tp.underlying = d.underlying and tp.symbol = d.symbol "
            + "and tp.opened_at <= d.decided_at + interval '60 seconds' "
            + "and (tp.closed_at is null or tp.closed_at >= d.decided_at) "
            + "order by abs(extract(epoch from tp.opened_at - d.decided_at)) limit 1) as book_quantity "
            + "from exec.trade_intent i join exec.trade_batch b on b.id = i.trade_batch_id "
            + "join exec.signal_decision d on d.id = b.signal_decision_id "
            + "left join acct.strategy_subscription s on s.copy_group_id = d.copy_group_id "
            + "and s.broker_account_id = i.broker_account_id and s.strategy_id = d.strategy_id ";

    private List<Pending> pending(long accountId, long copyGroupId, LocalDate day) {
        if (workerId != null) {
            // a worker claims what it runs (the lease already makes it the account's only writer; this is the second lock)
            jdbc.update("update exec.trade_intent set claimed_by = ?, claimed_at = now() where id in (select i.id "
                    + "from exec.trade_intent i where i.broker_account_id = ? and i.state = 'CREATED' "
                    + "and i.execution_mode in ('PAPER', 'DRY_RUN') and (i.claimed_by is null or i.claimed_by = ? "
                    + "or i.claimed_at < now() - interval '60 seconds') for update skip locked)", workerId, accountId,
                    workerId);
            return query(PENDING + "where i.broker_account_id = ? and d.copy_group_id = ? and d.session_date = ? "
                    + "and i.state = 'CREATED' and i.claimed_by = ? and i.execution_mode in ('PAPER', 'DRY_RUN') "
                    + "order by d.decided_at, d.priority, d.id", accountId, copyGroupId, Date.valueOf(day), workerId);
        }
        return query(PENDING + "where i.broker_account_id = ? and d.copy_group_id = ? and d.session_date = ? "
                + "and i.state = 'CREATED' and i.execution_mode in ('PAPER', 'DRY_RUN') order by d.decided_at, d.priority, d.id",
                accountId, copyGroupId, Date.valueOf(day));
    }

    private List<Pending> query(String sql, Object... args) {
        return jdbc.query(sql, FollowerSimulator::pendingRow, args);
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
        return new Pending(rs.getLong("id"), rs.getLong("copy_group_id"), rs.getString("execution_mode"), rs.getString("action"),
                rs.getString("strategy_id"), rs.getString("underlying"), rs.getString("option_side"),
                rs.getLong("instrument_token"), InstrumentKeys.normalize(rs.getString("instrument_key"),
                        rs.getLong("instrument_token")), rs.getString("symbol"), rs.getInt("lot_size"),
                orNaN(rs, "reference_price"), orNaN(rs, "premium_budget"), orNaN(rs, "reference_capital"),
                rs.getInt("lots"), rs.getString("leg_group"), rs.getTimestamp("decided_at").toInstant(),
                rs.getString("reason"), rs.getDouble("size_multiplier"), orNaN(rs, "strategy_lots"),
                orNaN(rs, "intended_lots"), orNaN(rs, "liquidity_lots"), orNaN(rs, "rvol_tod"), orNaN(rs, "call_ask"),
                orNaN(rs, "put_ask"), bookQuantity(rs), orNaN(rs, "stop_pct"));
    }
}
