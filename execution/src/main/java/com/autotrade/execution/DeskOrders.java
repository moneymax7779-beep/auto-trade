package com.autotrade.execution;

import com.autotrade.account.AccountRole;
import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.account.CapitalPolicy;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Desk trades (plan docs/plans/2026-11-23-owner-exit-all-and-desk-trades-plan.md, feature B; user 23 Nov 2026: "start with
 * desk trades PAPER only"): the owner's own trade, sent at once to the paper books of all or chosen accounts.
 * <ul>
 *   <li><b>Where it goes (PAPER only)</b>: a paper account itself; a real broker account's paper twin. Never a mirror,
 *       a signal source, a disabled account, or one whose paper book follows no copy group the worker serves.</li>
 *   <li><b>Size</b>: % of each paper account's equity (default) or a fixed number of lots, never above
 *       {@link ManualOrders#MAX_LOTS}; the desk's own cap: open desk premium at most {@link #CAP_PCT} % of equity, so a
 *       desk trade cannot take the capital the strategies need (A-360: the live set is capital-bound). An account where
 *       one lot does not fit is refused, never forced.</li>
 *   <li><b>How</b>: per target one decision of strategy {@link #STRATEGY} (its lots fixed, its stop %), one MANUAL batch
 *       and one PAPER intent, in one transaction with the order; the account's own turn runs it through
 *       {@link FollowerSimulator} (kill switches, daily loss, positions, lots, capital, stop-risk cap, the paper fill
 *       model of its risk file). {@link FanOut} never copies it.</li>
 *   <li><b>One open desk position per account and index</b>: a second desk order on the same index waits for the first
 *       to exit (an EXIT sells the account's desk position of the index; two would be ambiguous).</li>
 *   <li><b>Exit</b>: the owner's group exit, or the order's stop / target on the contract's captured bid against the
 *       order's reference ask ({@link #watch}): an EXIT intent per account still holding it; what cannot be sold yet (no
 *       fresh bid) is tried again on the next watch until nothing of the order is open.</li>
 * </ul>
 * The contract is one of the three around ATM the manual desk offers ({@link ManualOrders#pick}), chosen by the owner.
 */
public final class DeskOrders {

    public static final String STRATEGY = "desk";
    /** Open desk premium at most this % of an account's equity. */
    public static final double CAP_PCT = 20;
    /** A % of equity above this is a typo (the cap is 20 % anyway). */
    static final double MAX_PCT = 20;
    /** A bid older than this is no price to exit a paper position at, nor to judge the stop or target by. */
    static final Duration BID_MAX_AGE = Duration.ofSeconds(15);
    /** An exit refused on its turn is sent again at most this often. */
    static final Duration RETRY_EVERY = Duration.ofSeconds(30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** The advisory lock class of desk orders (one written at a time). */
    private static final int LOCK_CLASS = 7104;
    /** The advisory lock class of one desk order's exits (one holder's exit written at a time per order). */
    private static final int EXIT_LOCK_CLASS = 7105;

    public enum Sizing { PCT_EQUITY, FIXED_LOTS }

    /** What the owner asks: the contract (one of the ATM three), size, the stop and target as % of the reference ask. */
    public record Spec(String underlying, String side, String symbol, Sizing sizing, double value, double stopPct,
                       Double targetPct, String reason) {
    }

    /** One chosen account: where the trade goes and what it gets, or why not ({@code refusal}). */
    public record Target(long requestedId, String requested, String tenant, Long accountId, String account, double equity,
                         double deskOpen, int lots, long quantity, double premium, String refusal) {
    }

    /** What a send would do now: the contract, each account's share, and the book's depth against the whole order. */
    public record Preview(ManualOrders.Candidate contract, double spot, List<Target> targets, long totalQuantity,
                          long askDepth, String depthWarning) {
    }

    /** An order written: its id, and each target as written (a refused one with its reason, no intent). */
    public record Placed(long orderId, boolean repeat, List<Target> targets) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    private final KillSwitches kills;
    private Clock clock = Clock.systemUTC();

    public DeskOrders(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.accounts = new AccountStore(dataSource);
        this.kills = new KillSwitches(dataSource);
    }

    /** Tests: the clock of the trading hours and of quote freshness. */
    DeskOrders withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    private ManualOrders manual() {
        return new ManualOrders(dataSource).withClock(clock);
    }

    // ------------------------------------------------------------------------------------------------- preview / send

    /** The three strikes around ATM, as the manual desk offers them (refusals per strike). */
    public ManualOrders.Pick quote(String underlying, String side, LocalDate day) {
        return manual().pick(underlying, side, day, 1, Map.of());
    }

    /**
     * What sending {@code spec} to {@code accountIds} would do now; {@code groupOf} the served copy group a paper account
     * follows (IllegalArgumentException: none). IllegalArgumentException when the order itself is refused.
     */
    public Preview preview(Spec spec, List<Long> accountIds, LocalDate day, ToLongFunction<BrokerAccount> groupOf) {
        check(spec, accountIds, day);
        ManualOrders.Pick pick = quote(spec.underlying(), spec.side(), day);
        ManualOrders.Candidate c = pick.candidates().stream().filter(x -> x.symbol().equals(spec.symbol())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(spec.symbol() + " is no longer one of the strikes around ATM ("
                        + String.join(", ", pick.candidates().stream().map(ManualOrders.Candidate::symbol).toList())
                        + "): choose again"));
        if (c.refusal() != null) {
            throw new IllegalArgumentException(c.symbol() + " cannot be bought now: " + c.refusal());
        }
        List<Target> targets = new ArrayList<>();
        Set<Long> paperSeen = new LinkedHashSet<>();
        for (long id : new LinkedHashSet<>(accountIds)) {
            targets.add(target(id, spec, c, day, groupOf, paperSeen));
        }
        long total = targets.stream().filter(t -> t.refusal() == null).mapToLong(Target::quantity).sum();
        String depth = total > c.askQuantity() ? String.format(Locale.ROOT, "%,d to buy against %,d offered at the ask (five "
                + "levels): a real order this size would move the price; paper fills are judged on the captured book",
                total, c.askQuantity()) : null;
        return new Preview(c, pick.spot(), List.copyOf(targets), total, c.askQuantity(), depth);
    }

    private Target target(long requestedId, Spec spec, ManualOrders.Candidate c, LocalDate day,
                          ToLongFunction<BrokerAccount> groupOf, Set<Long> paperSeen) {
        BrokerAccount requested = accounts.byId(requestedId).orElse(null);
        if (requested == null) {
            return refused(requestedId, "#" + requestedId, null, "no such account");
        }
        String tenant = jdbc.queryForList("select name from acct.tenant where id = ?", String.class, requested.tenantId())
                .stream().findFirst().orElse(null);
        String why = refusal(requested);
        BrokerAccount paper = requested;
        if (why == null && requested.brokerType() != BrokerType.PAPER) {
            Long twin = jdbc.queryForList("select id from acct.broker_account where paper_of_account_id = ?", Long.class,
                    requested.id()).stream().findFirst().orElse(null);
            paper = twin == null ? null : accounts.byId(twin).orElse(null);
            why = paper == null ? "no paper twin (PAPER only: a real account's trade goes to its twin)" : refusal(paper);
        }
        if (why == null && !paperSeen.add(paper.id())) {
            why = "its paper book is already in this order";
        }
        if (why == null) {
            try {
                groupOf.applyAsLong(paper);
            } catch (IllegalArgumentException e) {
                why = e.getMessage();
            }
        }
        if (why == null) {
            why = busy(paper, spec.underlying(), day);
        }
        if (why == null) {
            String killed = kills.engaged(paper, STRATEGY, InstrumentKeys.normalize(null, c.token()));
            why = killed == null ? null : "kill switch engaged (" + killed + ")";
        }
        if (why != null) {
            return new Target(requestedId, requested.displayName(), tenant, paper == null ? null : paper.id(),
                    paper == null ? null : paper.displayName(), Double.NaN, Double.NaN, 0, 0, 0, why);
        }
        double equity = equity(paper, day);
        double open = deskOpen(paper.id());
        double perLot = c.ask() * c.lotSize();
        int lots = spec.sizing() == Sizing.FIXED_LOTS ? (int) spec.value()
                : (int) Math.floor(equity * spec.value() / 100 / perLot);
        int cap = (int) Math.floor(Math.max(0, equity * CAP_PCT / 100 - open) / perLot);
        int sized = Math.min(Math.min(lots, cap), ManualOrders.MAX_LOTS);
        String refusal = null;
        if (sized < 1) {
            refusal = lots < 1
                    ? String.format(Locale.ROOT, "1 lot costs Rs %,.0f, more than %.1f %% of its equity Rs %,.0f", perLot,
                    spec.value(), equity)
                    : String.format(Locale.ROOT, "the desk cap: open desk premium Rs %,.0f of at most %.0f %% of equity Rs "
                    + "%,.0f leaves no room for a lot (Rs %,.0f)", open, CAP_PCT, equity, perLot);
        }
        int lotsOut = refusal == null ? sized : 0;
        return new Target(requestedId, requested.displayName(), tenant, paper.id(), paper.displayName(), round(equity),
                round(open), lotsOut, (long) lotsOut * c.lotSize(), round(lotsOut * perLot), refusal);
    }

    private static Target refused(long id, String name, String tenant, String why) {
        return new Target(id, name, tenant, null, null, Double.NaN, Double.NaN, 0, 0, 0, why);
    }

    /** Why an account cannot take a desk trade at all (null: it can). */
    private String refusal(BrokerAccount a) {
        if (a.role() != AccountRole.FOLLOWER_TRADE) {
            return "a signal source takes no desk trade";
        }
        if (!a.enabled()) {
            return "disabled";
        }
        String mirrorOf = jdbc.queryForList("select mirror_of from acct.broker_account where id = ?", String.class, a.id())
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return mirrorOf == null ? null : "mirrors " + mirrorOf + " and takes no trades of its own";
    }

    /** Refused while the paper account holds a desk position of the index or has a desk buy of it not yet run. */
    private String busy(BrokerAccount a, String underlying, LocalDate day) {
        List<String> held = jdbc.queryForList("select symbol from exec.position where broker_account_id = ? and strategy_id "
                + "= ? and underlying = ? and status <> 'CLOSED'", String.class, a.id(), STRATEGY, underlying);
        if (!held.isEmpty()) {
            return "already holds desk " + held.getFirst() + ": exit that desk order first";
        }
        List<String> working = jdbc.queryForList("select symbol from exec.trade_intent where broker_account_id = ? and "
                + "strategy_id = ? and underlying = ? and action = 'ENTER' and state = 'CREATED' and (created_at at time zone "
                + "'Asia/Kolkata')::date = ?", String.class, a.id(), STRATEGY, underlying, Date.valueOf(day));
        return working.isEmpty() ? null : "a desk buy of " + working.getFirst() + " has not run yet";
    }

    private double equity(BrokerAccount a, LocalDate day) {
        Double realised = jdbc.queryForObject("select coalesce(sum(realised_gross - charges), 0) from exec.position "
                + "where broker_account_id = ? and status = 'CLOSED' and (closed_at at time zone 'Asia/Kolkata')::date >= ? "
                + "and (closed_at at time zone 'Asia/Kolkata')::date < ?", Double.class, a.id(), Date.valueOf(a.equityFrom()),
                Date.valueOf(day));
        return CapitalPolicy.equity(a, accounts.capitalMovements(a.id(), day), realised == null ? 0 : realised);
    }

    /**
     * The account's desk premium: open desk positions at their entry, and desk buys written but not yet run at their
     * order's price (review 23 Nov 2026: only the positions counted, so a NIFTY order and a SENSEX order sent before the
     * first ran could each take the whole cap).
     */
    private double deskOpen(long accountId) {
        Double open = jdbc.queryForObject("select coalesce(sum(quantity_open * average_entry), 0) from exec.position where "
                + "broker_account_id = ? and strategy_id = ? and status <> 'CLOSED'", Double.class, accountId, STRATEGY);
        Double waiting = jdbc.queryForObject("select coalesce(sum(i.quantity * d.reference_price), 0) from exec.trade_intent i "
                + "join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d on d.id = b.signal_decision_id "
                + "where i.broker_account_id = ? and i.strategy_id = ? and i.action = 'ENTER' and i.state in ('CREATED', "
                + "'VALIDATED', 'EXECUTING')", Double.class, accountId, STRATEGY);
        return (open == null ? 0 : open) + (waiting == null ? 0 : waiting);
    }

    private void check(Spec s, List<Long> accountIds, LocalDate day) {
        if (s == null) {
            throw new IllegalArgumentException("the order is missing");
        }
        if (accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("choose at least one account");
        }
        if (s.symbol() == null || s.symbol().isBlank()) {
            throw new IllegalArgumentException("choose the contract (one of the strikes around ATM)");
        }
        if (s.sizing() == null) {
            throw new IllegalArgumentException("sizing: PCT_EQUITY or FIXED_LOTS");
        }
        if (s.sizing() == Sizing.PCT_EQUITY && !(s.value() > 0 && s.value() <= MAX_PCT)) {
            throw new IllegalArgumentException("% of equity: more than 0 and at most " + (int) MAX_PCT);
        }
        if (s.sizing() == Sizing.FIXED_LOTS && (s.value() < 1 || s.value() > ManualOrders.MAX_LOTS
                || s.value() != Math.floor(s.value()))) {
            throw new IllegalArgumentException("lots: a whole number from 1 to " + ManualOrders.MAX_LOTS);
        }
        if (!(s.stopPct() > 0) || s.stopPct() >= 100) {
            throw new IllegalArgumentException("the stop must be between 0 and 100 % below the price");
        }
        if (s.targetPct() != null && !(s.targetPct() > 0 && s.targetPct() <= 1000)) {
            throw new IllegalArgumentException("the target: more than 0 % above the price (or none)");
        }
        if (s.reason() == null || s.reason().trim().length() < 3 || s.reason().length() > 300) {
            throw new IllegalArgumentException("reason: 3 to 300 characters (it is audited)");
        }
        LocalTime time = LocalTime.ofInstant(clock.instant(), IST);
        if (day.equals(LocalDate.ofInstant(clock.instant(), IST))
                && (time.isBefore(ManualOrders.FIRST_BUY) || !time.isBefore(ManualOrders.LAST_BUY))) {
            throw new IllegalArgumentException("desk orders only from " + ManualOrders.FIRST_BUY + " to "
                    + ManualOrders.LAST_BUY + " IST (square-off 15:20)");
        }
    }

    /**
     * Writes the order and one PAPER intent per target that can take it, in one transaction; {@code key} the owner's
     * idempotency key (the same key again answers the first order and writes nothing). Each refused target is written with
     * its reason and gets no intent. IllegalArgumentException when the order is refused as a whole or no target can take it.
     */
    public Placed place(Spec spec, List<Long> accountIds, String actor, String key, LocalDate day,
                        ToLongFunction<BrokerAccount> groupOf) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new IllegalArgumentException("key: 1 to 100 characters (one per confirmation)");
        }
        Long earlier = jdbc.queryForList("select id from exec.desk_order where created_by = ? and idempotency_key = ?",
                Long.class, actor, key).stream().findFirst().orElse(null);
        if (earlier != null) {
            return new Placed(earlier, true, targetsOf(earlier));
        }
        return transactions.execute(tx -> {
            jdbc.query("select pg_advisory_xact_lock(?, 0)", (org.springframework.jdbc.core.RowCallbackHandler) rs -> { },
                    LOCK_CLASS);
            Long again = jdbc.queryForList("select id from exec.desk_order where created_by = ? and idempotency_key = ?",
                    Long.class, actor, key).stream().findFirst().orElse(null);
            if (again != null) {
                return new Placed(again, true, targetsOf(again));
            }
            Preview p = preview(spec, accountIds, day, groupOf);       // again, under the lock: nothing stale is written
            if (p.targets().stream().allMatch(t -> t.refusal() != null)) {
                throw new IllegalArgumentException("no account can take it: " + String.join("; ", p.targets().stream()
                        .map(t -> t.requested() + ": " + t.refusal()).toList()));
            }
            ManualOrders.Candidate c = p.contract();
            String u = spec.underlying().trim().toUpperCase(Locale.ROOT);
            String side = spec.side().trim().toUpperCase(Locale.ROOT);
            long order = jdbc.queryForObject("insert into exec.desk_order (created_by, session_date, underlying, option_side, "
                    + "instrument_token, symbol, lot_size, reference_ask, reference_bid, sizing, sizing_value, stop_pct, "
                    + "target_pct, reason, idempotency_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                    Long.class, actor, Date.valueOf(day), u, side, c.token(), c.symbol(), c.lotSize(), c.ask(),
                    c.bid() > 0 ? c.bid() : null, spec.sizing().name(), spec.value(), spec.stopPct(), spec.targetPct(),
                    spec.reason().trim(), key);
            Instant now = clock.instant();
            String instrumentKey = InstrumentKeys.normalize(null, c.token());
            List<Target> written = new ArrayList<>();
            for (Target t : p.targets()) {
                Long intent = null;
                if (t.refusal() == null) {
                    BrokerAccount paper = accounts.byId(t.accountId()).orElseThrow();
                    long group = groupOf.applyAsLong(paper);
                    String idem = "DESK:" + order + ":" + paper.id();
                    Map<String, Double> context = new LinkedHashMap<>();
                    context.put("fixed_lots", (double) t.lots());
                    context.put("strategy_lots", (double) t.lots());
                    context.put("intended_lots", (double) t.lots());
                    context.put("stop_pct", spec.stopPct());
                    context.put("desk_order", (double) order);
                    new SignalDecisionStore(dataSource).insert(new SignalDecision(group, 0, day, now, STRATEGY, 0, u,
                            SignalDecision.Action.ENTER, side, null, "DESK", "desk order #" + order + " by " + actor + ": "
                            + spec.reason().trim(), c.token(), instrumentKey, c.symbol(), c.lotSize(), c.ask(), t.premium(),
                            Double.NaN, t.lots(), idem, context));
                    long decision = jdbc.queryForObject("select id from exec.signal_decision where idempotency_key = ?",
                            Long.class, idem);
                    long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, copy_group_id, "
                            + "signal_decision_id, source, idempotency_key, status) values (?, ?, ?, 'MANUAL', ?, 'FANNED_OUT') "
                            + "returning id", Long.class, paper.tenantId(), group, decision, idem);
                    intent = jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                            + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, "
                            + "instrument_token, symbol, quantity, state, idempotency_key) values (?, ?, ?, ?, 'PAPER', ?, 0, ?, "
                            + "'ENTER', ?, ?, ?, ?, 'CREATED', ?) returning id", Long.class, paper.tenantId(), batch, paper.id(),
                            paper.brokerType().name(), STRATEGY, u, side, c.token(), c.symbol(), t.quantity(), idem + ":"
                            + paper.id());
                    Map<String, Object> audit = new LinkedHashMap<>();
                    audit.put("desk_order", order);
                    audit.put("intent", intent);
                    audit.put("symbol", c.symbol());
                    audit.put("lots", t.lots());
                    audit.put("ask", c.ask());
                    audit.put("reason", spec.reason().trim());
                    audit.put("for", t.requested());
                    kills.audit(paper, actor, "DESK_ORDER", audit);
                }
                jdbc.update("insert into exec.desk_order_target (desk_order_id, requested_account_id, broker_account_id, "
                        + "equity, lots, quantity, trade_intent_id, refusal) values (?, ?, ?, ?, ?, ?, ?, ?)", order,
                        t.requestedId(), t.accountId(), Double.isNaN(t.equity()) ? null : t.equity(), t.lots(), t.quantity(),
                        intent, t.refusal());
                written.add(t);
            }
            return new Placed(order, false, List.copyOf(written));
        });
    }

    private List<Target> targetsOf(long order) {
        return jdbc.query("select t.requested_account_id, r.display_name, tn.name, t.broker_account_id, p.display_name, "
                + "t.equity, t.lots, t.quantity, t.refusal, o.reference_ask, o.lot_size from exec.desk_order_target t "
                + "join exec.desk_order o on o.id = t.desk_order_id join acct.broker_account r on r.id = "
                + "t.requested_account_id join acct.tenant tn on tn.id = r.tenant_id left join acct.broker_account p on p.id = "
                + "t.broker_account_id where t.desk_order_id = ? order by t.requested_account_id", (rs, i) -> new Target(
                rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), rs.getString(5),
                rs.getObject(6) == null ? Double.NaN : rs.getDouble(6), Double.NaN, rs.getInt(7), rs.getLong(8),
                round(rs.getInt(7) * rs.getDouble(10) * rs.getInt(11)), rs.getString(9)), order);
    }

    // ------------------------------------------------------------------------------------------------- exit

    /**
     * The order's group exit: asked once (who, why), then {@link #sellWhatIsOpen} at once and on every watch until nothing
     * of it is open. Returns one line per target.
     */
    public List<String> exit(long order, String actor, String reason, LocalDate day, ToLongFunction<BrokerAccount> groupOf) {
        if (reason == null || reason.trim().length() < 3) {
            throw new IllegalArgumentException("reason: at least 3 characters (it is audited)");
        }
        int asked = jdbc.update("update exec.desk_order set exit_reason = ?, exit_requested_at = now(), exit_requested_by = ? "
                + "where id = ? and exit_requested_at is null", "OWNER: " + reason.trim(), actor, order);
        if (asked == 0 && jdbc.queryForList("select 1 from exec.desk_order where id = ?", Integer.class, order).isEmpty()) {
            throw new IllegalArgumentException("no desk order #" + order);
        }
        return sellWhatIsOpen(order, actor, day, groupOf);
    }

    /**
     * For every target of an order whose exit was asked: a desk buy not yet run is cancelled; an open desk position gets one
     * EXIT intent (at the contract's latest captured bid, the paper fill price), unless one is already waiting. A target
     * with no fresh bid is left for the next watch.
     */
    List<String> sellWhatIsOpen(long order, String actor, LocalDate day, ToLongFunction<BrokerAccount> groupOf) {
        Map<String, Object> o = jdbc.queryForMap("select underlying, option_side, instrument_token, symbol, lot_size, "
                + "exit_reason from exec.desk_order where id = ?", order);
        String reason = String.valueOf(o.get("exit_reason"));
        long token = ((Number) o.get("instrument_token")).longValue();
        String symbol = (String) o.get("symbol");
        String u = (String) o.get("underlying");
        String side = (String) o.get("option_side");
        int lotSize = ((Number) o.get("lot_size")).intValue();
        List<String> lines = new ArrayList<>();
        int cancelled = jdbc.update("update exec.trade_intent i set state = 'CANCELLED', reject_code = 'DESK_EXIT', "
                + "reject_reason = 'the desk order was exited before this buy ran', updated_at = now() from "
                + "exec.desk_order_target t where t.desk_order_id = ? and t.trade_intent_id = i.id and i.state = 'CREATED' "
                + "and not exists (select 1 from exec.order_execution e where e.trade_intent_id = i.id)", order);
        if (cancelled > 0) {
            lines.add(cancelled + " buy(s) not yet run: cancelled");
        }
        Double bid = latestBid(token);
        for (Map<String, Object> p : jdbc.queryForList("select p.id, p.broker_account_id, p.quantity_open, a.display_name "
                + "from exec.desk_order_target t join exec.position p on p.entry_trade_intent_id = t.trade_intent_id "
                + "join acct.broker_account a on a.id = p.broker_account_id where t.desk_order_id = ? and p.status <> 'CLOSED' "
                + "and p.quantity_open > 0 order by p.id", order)) {
            long accountId = ((Number) p.get("broker_account_id")).longValue();
            String name = (String) p.get("display_name");
            long positionId = ((Number) p.get("id")).longValue();
            String prefix = "DESK-EXIT:" + order + ":" + accountId + ":" + positionId + ":";
            if (bid == null) {
                lines.add(name + ": no bid for " + symbol + " in the last " + BID_MAX_AGE.toSeconds() + " s: tried again "
                        + "on the next watch");
                continue;
            }
            BrokerAccount a = accounts.byId(accountId).orElseThrow();
            long quantity = ((Number) p.get("quantity_open")).longValue();
            Map<String, Double> context = new LinkedHashMap<>();
            context.put("quantity", (double) quantity);
            context.put("desk_order", (double) order);
            long group = groupOf.applyAsLong(a);
            Instant now = clock.instant();
            // review 23 Nov 2026: the owner's Exit and the watcher's stop at the same moment both passed the checks and
            // wrote the same key (the second write failed: a 500 on Exit). One holder's exit is now decided and written
            // under the order's lock, the checks again inside it
            String said = transactions.execute(tx -> {
                jdbc.query("select pg_advisory_xact_lock(?, ?)", (org.springframework.jdbc.core.RowCallbackHandler) rs -> { },
                        EXIT_LOCK_CLASS, (int) order);
                boolean waiting = !jdbc.queryForList("select 1 from exec.trade_intent where broker_account_id = ? and "
                        + "strategy_id = ? and underlying = ? and action = 'EXIT' and state = 'CREATED'", Integer.class,
                        accountId, STRATEGY, u).isEmpty();
                if (waiting) {
                    return name + ": its exit is already waiting for its turn";
                }
                // an exit refused on its turn (no price) is sent again, at most every RETRY_EVERY, each attempt its own key
                boolean recent = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from exec.signal_decision "
                        + "where idempotency_key like ? and created_at > now() - make_interval(secs => ?))", Boolean.class,
                        prefix + "%", (double) RETRY_EVERY.toSeconds()));
                if (recent) {
                    return name + ": an exit was sent less than " + RETRY_EVERY.toSeconds() + " s ago";
                }
                Integer attempts = jdbc.queryForObject("select count(*) from exec.signal_decision where idempotency_key like ?",
                        Integer.class, prefix + "%");
                String idem = prefix + ((attempts == null ? 0 : attempts) + 1);
                new SignalDecisionStore(dataSource).insert(new SignalDecision(group, 0, day, now, STRATEGY, 0, u,
                        SignalDecision.Action.EXIT, side, null, "DESK", reason, token, InstrumentKeys.normalize(null, token),
                        symbol, lotSize, bid, Double.NaN, Double.NaN, (int) Math.max(1, quantity / lotSize), idem, context));
                long decision = jdbc.queryForObject("select id from exec.signal_decision where idempotency_key = ?", Long.class,
                        idem);
                long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, copy_group_id, signal_decision_id, "
                        + "source, idempotency_key, status) values (?, ?, ?, 'MANUAL', ?, 'FANNED_OUT') returning id",
                        Long.class, a.tenantId(), group, decision, idem);
                jdbc.update("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, broker_type, "
                        + "execution_mode, strategy_id, priority, underlying, action, option_side, instrument_token, symbol, "
                        + "quantity, state, idempotency_key) values (?, ?, ?, ?, 'PAPER', ?, 0, ?, 'EXIT', ?, ?, ?, ?, 'CREATED', "
                        + "?) on conflict do nothing", a.tenantId(), batch, a.id(), a.brokerType().name(), STRATEGY, u, side,
                        token, symbol, quantity, idem + ":" + a.id());
                return null;
            });
            if (said != null) {
                lines.add(said);
                continue;
            }
            kills.audit(a, actor, "DESK_EXIT", Map.of("desk_order", order, "symbol", symbol, "quantity", quantity, "bid", bid,
                    "reason", reason));
            lines.add(name + ": exit sent at bid " + bid);
        }
        return lines;
    }

    private Double latestBid(long token) {
        Instant now = clock.instant();
        return jdbc.queryForList("select bid_px[1] from md.option_tick where instrument_token = ? and recv_ts > ? and recv_ts "
                + "<= ? and bid_px[1] > 0 order by recv_ts desc limit 1", Double.class, token,
                Timestamp.from(now.minus(BID_MAX_AGE)), Timestamp.from(now.plusSeconds(5))).stream().findFirst().orElse(null);
    }

    /**
     * Every few seconds while the worker runs: an open desk order whose contract's bid reached its stop (reference ask x
     * (1 - stop %)) or target (x (1 + target %)) is exited (DESK_STOP / DESK_TARGET); an order whose exit was asked has what
     * is still open sold. Returns the orders acted on.
     */
    public List<Long> watch(LocalDate day, ToLongFunction<BrokerAccount> groupOf) {
        List<Long> acted = new ArrayList<>();
        for (Map<String, Object> o : jdbc.queryForList("select o.id, o.instrument_token, o.reference_ask, o.stop_pct, "
                + "o.target_pct, o.exit_requested_at from exec.desk_order o where o.session_date = ? and exists (select 1 from "
                + "exec.desk_order_target t join exec.position p on p.entry_trade_intent_id = t.trade_intent_id where "
                + "t.desk_order_id = o.id and p.status <> 'CLOSED' and p.quantity_open > 0) order by o.id", Date.valueOf(day))) {
            long id = ((Number) o.get("id")).longValue();
            if (o.get("exit_requested_at") == null) {
                Double bid = latestBid(((Number) o.get("instrument_token")).longValue());
                if (bid == null) {
                    continue;
                }
                double ref = ((Number) o.get("reference_ask")).doubleValue();
                double stop = ref * (1 - ((Number) o.get("stop_pct")).doubleValue() / 100);
                Double target = o.get("target_pct") == null ? null
                        : ref * (1 + ((Number) o.get("target_pct")).doubleValue() / 100);
                String why = bid <= stop ? String.format(Locale.ROOT, "DESK_STOP: bid %.2f at or below the stop %.2f", bid, stop)
                        : target != null && bid >= target ? String.format(Locale.ROOT, "DESK_TARGET: bid %.2f at or above the "
                        + "target %.2f", bid, target) : null;
                if (why == null) {
                    continue;
                }
                jdbc.update("update exec.desk_order set exit_reason = ?, exit_requested_at = now(), exit_requested_by = "
                        + "'desk-watch' where id = ? and exit_requested_at is null", why, id);
            }
            sellWhatIsOpen(id, "desk-watch", day, groupOf);
            acted.add(id);
        }
        return acted;
    }

    // ------------------------------------------------------------------------------------------------- the desk

    /** Today's desk orders, newest first: the order, its latest bid, and per target the intent and the position. */
    public List<Map<String, Object>> orders(LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : jdbc.queryForList("select id, created_by, created_at, underlying, option_side, "
                + "instrument_token, symbol, lot_size, reference_ask, sizing, sizing_value, stop_pct, target_pct, reason, "
                + "exit_reason, exit_requested_at from exec.desk_order where session_date = ? order by id desc limit 50",
                Date.valueOf(day))) {
            long id = ((Number) o.get("id")).longValue();
            Double bid = latestBid(((Number) o.get("instrument_token")).longValue());
            Map<String, Object> row = new LinkedHashMap<>(o);
            row.put("bid", bid);
            double ref = ((Number) o.get("reference_ask")).doubleValue();
            row.put("stopAt", round(ref * (1 - ((Number) o.get("stop_pct")).doubleValue() / 100)));
            row.put("targetAt", o.get("target_pct") == null ? null
                    : round(ref * (1 + ((Number) o.get("target_pct")).doubleValue() / 100)));
            List<Map<String, Object>> targets = jdbc.queryForList("select t.requested_account_id, r.display_name requested, "
                    + "tn.name tenant, t.broker_account_id, a.display_name account, t.lots, t.quantity, t.refusal, "
                    + "i.state intent_state, i.reject_code, i.reject_reason, p.id position_id, p.status position_status, "
                    + "p.quantity_open, p.average_entry, p.exit_reason, p.realised_gross, p.charges from exec.desk_order_target t "
                    + "join acct.broker_account r on r.id = t.requested_account_id join acct.tenant tn on tn.id = r.tenant_id "
                    + "left join acct.broker_account a on a.id = t.broker_account_id left join exec.trade_intent i on i.id = "
                    + "t.trade_intent_id left join exec.position p on p.entry_trade_intent_id = t.trade_intent_id "
                    + "where t.desk_order_id = ? order by t.requested_account_id", id);
            double net = 0;
            int open = 0;
            for (Map<String, Object> t : targets) {
                Object status = t.get("position_status");
                if (status == null) {
                    continue;
                }
                double entry = ((Number) t.get("average_entry")).doubleValue();
                long qty = ((Number) t.get("quantity_open")).longValue();
                double realised = t.get("realised_gross") == null ? 0 : ((Number) t.get("realised_gross")).doubleValue();
                double charges = t.get("charges") == null ? 0 : ((Number) t.get("charges")).doubleValue();
                double unrealised = !"CLOSED".equals(status) && bid != null ? (bid - entry) * qty : 0;
                t.put("unrealised", round(unrealised));
                t.put("net", round(realised - charges + unrealised));
                net += realised - charges + unrealised;
                if (!"CLOSED".equals(status) && qty > 0) {
                    open++;
                }
            }
            boolean anyPosition = targets.stream().anyMatch(t -> t.get("position_status") != null);
            boolean anyWaiting = targets.stream().anyMatch(t -> "CREATED".equals(t.get("intent_state")));
            row.put("targets", targets);
            row.put("net", round(net));
            row.put("open", open);
            // OPEN / EXITING while something is held; WAITING while buys have not run; CLOSED once all bought is sold;
            // NONE when nothing was bought (every buy refused or not filled)
            row.put("state", open > 0 ? (o.get("exit_requested_at") == null ? "OPEN" : "EXITING")
                    : anyWaiting ? "WAITING" : anyPosition ? "CLOSED" : "NONE");
            out.add(row);
        }
        return out;
    }

    private static double round(double v) {
        return Double.isNaN(v) ? v : Math.round(v * 100) / 100.0;
    }
}
