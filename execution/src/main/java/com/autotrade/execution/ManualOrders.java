package com.autotrade.execution;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.autotrade.account.AccountRole;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;

/**
 * The trader's BUY CE / BUY PE (user, 9 Nov 2026: "buy the best ATM PE CE ... and when I click EXIT it should exit the
 * order in LIVE/PAPER"). A buy is not a side path: it is one {@code exec.signal_decision} of strategy {@link #STRATEGY}
 * in a copy group the account follows, one MANUAL {@code exec.trade_batch} and one {@code exec.trade_intent} for that
 * account only, written in one transaction and announced like any decision. The account's next turn (at once, woken by
 * the decision) runs it through {@link FollowerSimulator}: every risk check and kill switch, the venue's order rules
 * (marketable limit at the ask + buffer, the rest cancelled after the timeout), LiveGuard on a LIVE account, then the
 * position's broker stop at {@link #STOP_PCT}. Followers never copy it ({@link FanOut} skips the strategy).
 * <p>
 * Choices (user, 9 Nov 2026): follower accounts only (PAPER, DRY_RUN and LIVE; never the signal source), {@link #LOTS}
 * lot, NIFTY or SENSEX at the nearest expiry, "best ATM" = of the strikes around spot (the nearest and one either side),
 * the one with the tightest bid-ask spread among those with a fresh two-sided quote and at least the order's quantity
 * on the ask; a 25 % broker stop. Buys between {@link #FIRST_BUY} and {@link #LAST_BUY} IST; one manual position per
 * index at a time. EXIT ({@link ExecutionLoop#manualExit}) cancels a manual buy not yet run and sells the index's manual
 * position as the per-position Exit does.
 */
public final class ManualOrders {

    /** The strategy id of manual orders (P&amp;L, reconciliation and the LIVE risk file's strategy list read it). */
    public static final String STRATEGY = "manual";
    public static final int LOTS = 1;
    /** A chart order's largest size (the account's own lot, capital and loss checks still apply on top). */
    public static final int MAX_LOTS = 10;
    public static final double STOP_PCT = 25;
    public static final List<String> UNDERLYINGS = List.of("NIFTY", "SENSEX");
    static final LocalTime FIRST_BUY = LocalTime.of(9, 15);
    /**
     * The manual desk's own last buy (and a chart order's latest "valid until"). Main 16 Nov 2026 moved the LIVE last
     * entry into each broker's rules (BrokerRules: ZERODHA 15:10, Kotak Neo and Groww 15:05), and FollowerSimulator
     * refuses every LIVE entry after its account's broker time, manual ones included; this check only answers early.
     */
    static final LocalTime LAST_BUY = LocalTime.of(15, 10);
    /** A quote older than this does not count (the capture may have stopped). */
    static final Duration QUOTE_MAX_AGE = Duration.ofSeconds(30);
    static final Duration SPOT_MAX_AGE = Duration.ofSeconds(60);
    /** A strike whose spread is wider than this is not bought (the "tightest" of three wide books is still wide). */
    static final double MAX_SPREAD_PCT = 5;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** The advisory lock class of an account's manual buy (one buy written at a time per account). */
    private static final int BUY_LOCK_CLASS = 7103;

    /** One strike looked at; {@code refusal} says why it cannot be bought (null: it can). */
    public record Candidate(long token, String symbol, double strike, LocalDate expiry, int lotSize, double bid, double ask,
                            long askQuantity, double spreadPct, Instant quoteAt, String refusal) {
    }

    /** The contract a buy would take now: {@code chosen} of {@code candidates}. */
    public record Pick(String underlying, String side, double spot, Instant spotAt, Candidate chosen,
                       List<Candidate> candidates) {

        public String instrumentKey() {
            return InstrumentKeys.normalize(null, chosen.token());
        }
    }

    /** A buy written for the account's turn. */
    public record Placed(long decisionId, long intentId, Pick pick, long quantity, String mode) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KillSwitches kills;
    private final TradingDay tradingDay;
    private Clock clock = Clock.systemUTC();

    public ManualOrders(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.kills = new KillSwitches(dataSource);
        this.tradingDay = new TradingDay(dataSource);
    }

    /** Tests: the clock of the trading hours and of quote freshness. */
    ManualOrders withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    // ------------------------------------------------------------------------------------------------- the contract

    /** The best ATM contract of {@code underlying} / {@code side} now; IllegalArgumentException when none can be bought. */
    public Pick pick(String underlying, String side, LocalDate day) {
        return pick(underlying, side, day, LOTS, Map.of());
    }

    /** The contract a buy on {@code account} would take now: a contract the account already holds is not bought again. */
    public Pick pick(BrokerAccount account, String underlying, String side, LocalDate day) {
        return pick(underlying, side, day, LOTS, held(account.id()));
    }

    /**
     * Review 9 Nov 2026: a manual buy of a contract a strategy of the account already holds would share its token, and a
     * late sell is booked against the token's oldest open position, so one could close the other: such a strike is
     * skipped. {@code held}: token -> the strategy holding it.
     */
    Pick pick(String underlying, String side, LocalDate day, int lots, Map<Long, String> held) {
        String u = checkUnderlying(underlying);
        String s = checkSide(side);
        Instant now = clock.instant();
        Timestamp quoteFrom = Timestamp.from(now.minus(QUOTE_MAX_AGE));
        // review 9 Nov 2026: both time bounds, so a stopped feed never sends the read back through older chunks
        List<Map<String, Object>> spot = jdbc.queryForList("select price, recv_ts from md.index_tick where underlying = ? "
                + "and session_date = ? and recv_ts > ? and recv_ts <= ? order by recv_ts desc limit 1", u, Date.valueOf(day),
                Timestamp.from(now.minus(SPOT_MAX_AGE)), Timestamp.from(now.plusSeconds(5)));
        if (spot.isEmpty()) {
            throw new IllegalArgumentException("no " + u + " spot in the last " + SPOT_MAX_AGE.toSeconds()
                    + " s: the market feed is not running, nothing bought");
        }
        double price = ((Number) spot.getFirst().get("price")).doubleValue();
        Instant spotAt = ((Timestamp) spot.getFirst().get("recv_ts")).toInstant();
        // the strikes quoted lately at the nearest expiry (the capture follows a band around ATM)
        Timestamp listedFrom = Timestamp.from(now.minus(Duration.ofMinutes(5)));
        LocalDate expiry = jdbc.queryForList("select min(expiry) from md.option_tick where underlying = ? and session_date = ? "
                + "and option_type = ? and expiry >= ? and recv_ts > ?", Date.class, u, Date.valueOf(day), s,
                Date.valueOf(day), listedFrom).stream().filter(java.util.Objects::nonNull).map(Date::toLocalDate)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no " + u + " " + s
                        + " quotes in the last 5 minutes: the option capture is not running"));
        List<Double> strikes = jdbc.queryForList("select distinct strike from md.option_tick where underlying = ? "
                + "and session_date = ? and option_type = ? and expiry = ? and recv_ts > ? order by strike", Double.class, u,
                Date.valueOf(day), s, Date.valueOf(expiry), listedFrom);
        List<Candidate> candidates = new ArrayList<>();
        for (double strike : aroundSpot(strikes, price)) {
            candidates.add(candidate(u, s, day, expiry, strike, quoteFrom, listedFrom, lots, held));
        }
        Candidate best = best(candidates, price);
        if (best == null) {
            StringBuilder why = new StringBuilder("no " + u + " " + s + " near spot " + round(price) + " can be bought now:");
            candidates.forEach(c -> why.append(' ').append(c.symbol()).append(" (").append(c.refusal()).append(");"));
            throw new IllegalArgumentException(why.toString());
        }
        return new Pick(u, s, price, spotAt, best, List.copyOf(candidates));
    }

    /** The strike nearest {@code spot} (the lower one on a tie) and one either side of it, ascending. */
    static List<Double> aroundSpot(List<Double> strikes, double spot) {
        List<Double> sorted = strikes.stream().sorted().toList();
        if (sorted.isEmpty()) {
            return List.of();
        }
        int atm = 0;
        for (int i = 1; i < sorted.size(); i++) {
            if (Math.abs(sorted.get(i) - spot) < Math.abs(sorted.get(atm) - spot)) {
                atm = i;
            }
        }
        return sorted.subList(Math.max(0, atm - 1), Math.min(sorted.size(), atm + 2));
    }

    /** The buyable candidate with the tightest spread (the nearer to spot on a tie); null when none is buyable. */
    static Candidate best(List<Candidate> candidates, double spot) {
        return candidates.stream().filter(c -> c.refusal() == null)
                .min(Comparator.comparingDouble(Candidate::spreadPct).thenComparingDouble(c -> Math.abs(c.strike() - spot)))
                .orElse(null);
    }

    private Candidate candidate(String underlying, String side, LocalDate day, LocalDate expiry, double strike,
                                Timestamp quoteFrom, Timestamp listedFrom, int lots, Map<Long, String> held) {
        List<Map<String, Object>> rows = jdbc.queryForList("select instrument_token, symbol, lot_size, bid_px[1] bid, "
                + "ask_px[1] ask, (select coalesce(sum(q), 0) from unnest(ask_qty) q) ask_qty, recv_ts from md.option_tick "
                + "where underlying = ? and session_date = ? and option_type = ? and expiry = ? and strike = ? "
                + "and recv_ts > ? order by recv_ts desc limit 1", underlying, Date.valueOf(day), side, Date.valueOf(expiry),
                strike, listedFrom);
        Map<String, Object> r = rows.getFirst();                        // the strike was listed from these rows
        long token = ((Number) r.get("instrument_token")).longValue();
        String symbol = (String) r.get("symbol");
        int lotSize = r.get("lot_size") == null ? lotSizeOf(token) : ((Number) r.get("lot_size")).intValue();
        double bid = r.get("bid") == null ? 0 : ((Number) r.get("bid")).doubleValue();
        double ask = r.get("ask") == null ? 0 : ((Number) r.get("ask")).doubleValue();
        long askQty = ((Number) r.get("ask_qty")).longValue();
        Instant at = ((Timestamp) r.get("recv_ts")).toInstant();
        double spread = bid > 0 && ask >= bid ? 100 * (ask - bid) / ((ask + bid) / 2) : Double.NaN;   // locked book: 0
        String refusal = null;
        if (held.containsKey(token)) {
            refusal = "held by this account's " + held.get(token);
        } else if (at.isBefore(quoteFrom.toInstant())) {
            refusal = "last quote " + ist(at);
        } else if (!(bid > 0) || !(ask > 0) || ask < bid) {
            refusal = "no two-sided quote";
        } else if (lotSize <= 0) {
            refusal = "lot size unknown";
        } else if (askQty < (long) lots * lotSize) {
            refusal = "only " + askQty + " offered, " + (long) lots * lotSize + " needed";
        } else if (spread > MAX_SPREAD_PCT) {
            refusal = String.format(java.util.Locale.ROOT, "spread %.1f %% over %.0f %%", spread, MAX_SPREAD_PCT);
        }
        return new Candidate(token, symbol, strike, expiry, lotSize, bid, ask, askQty,
                Double.isNaN(spread) ? Double.NaN : round(spread), at, refusal);
    }

    /** The account's open positions' tokens and the strategy of each. */
    private Map<Long, String> held(long accountId) {
        Map<Long, String> held = new java.util.HashMap<>();
        jdbc.query("select instrument_token, strategy_id from exec.position where broker_account_id = ? and status <> "
                + "'CLOSED'", (org.springframework.jdbc.core.RowCallbackHandler) rs -> held.put(rs.getLong(1),
                rs.getString(2)), accountId);
        return held;
    }

    private int lotSizeOf(long token) {
        String key = InstrumentKeys.normalize(null, token);
        return jdbc.queryForList("select i.lot_size from ref.instrument i join ref.instrument_snapshot s on s.id = "
                + "i.snapshot_id where i.instrument_key = ? order by s.snapshot_date desc, s.id desc limit 1", Integer.class,
                key).stream().findFirst().orElse(0);
    }

    // ------------------------------------------------------------------------------------------------- the buy

    /**
     * Writes the buy for the account's turn; IllegalArgumentException when it is refused before anything is written.
     * {@code expectedSymbol} (the contract the trader confirmed; null = any): refused when the best ATM is now another.
     */
    public Placed buy(BrokerAccount account, long copyGroupId, String underlying, String side, String expectedSymbol,
                      String actor, LocalDate day) {
        return buy(account, copyGroupId, underlying, side, expectedSymbol, actor, day, LOTS);
    }

    /** {@code lots}: tests only (a partial fill needs more than one lot); the UI's buy is {@link #LOTS}. */
    Placed buy(BrokerAccount account, long copyGroupId, String underlying, String side, String expectedSymbol, String actor,
               LocalDate day, int lots) {
        String s = side == null ? "" : side.trim().toUpperCase(java.util.Locale.ROOT);
        return buy(account, copyGroupId, underlying, side, expectedSymbol, actor, day, lots, STOP_PCT,
                "manual BUY " + s + " by " + actor, "MANUAL:" + account.id() + ":" + UUID.randomUUID());
    }

    /**
     * The buy, as the panel's buy and a chart order's entry ({@link ChartOrderEngine}, 14 Nov 2026) write it: {@code lots}
     * lots ({@link #MAX_LOTS} at most), the broker stop {@code stopPct} % below the price paid, {@code reason} on the
     * decision (the position's entry note), {@code idempotencyKey} the decision's key (a chart order's entry is written
     * once: a second write of the same key fails and writes nothing).
     */
    public Placed buy(BrokerAccount account, long copyGroupId, String underlying, String side, String expectedSymbol,
                      String actor, LocalDate day, int lots, double stopPct, String reason, String idempotencyKey) {
        if (lots < 1 || lots > MAX_LOTS) {
            throw new IllegalArgumentException("lots: 1 to " + MAX_LOTS);
        }
        if (!(stopPct > 0) || stopPct >= 100) {
            throw new IllegalArgumentException("the broker stop must be between 0 and 100 % below the price paid");
        }
        String u = checkUnderlying(underlying);
        String s = checkSide(side);
        if (account.role() != AccountRole.FOLLOWER_TRADE) {
            throw new IllegalArgumentException(account.displayName() + " is a signal source: manual orders are for "
                    + "follower accounts");
        }
        if (!account.enabled()) {
            throw new IllegalArgumentException(account.displayName() + " is disabled");
        }
        if (account.tradeMode() == TradeMode.LIVE && !account.liveTradingEnabled()) {
            throw new IllegalArgumentException(account.displayName() + " is LIVE without its live switch: nothing sent");
        }
        LocalTime time = LocalTime.ofInstant(clock.instant(), IST);
        if (day.equals(LocalDate.ofInstant(clock.instant(), IST))
                && (time.isBefore(FIRST_BUY) || !time.isBefore(LAST_BUY))) {
            throw new IllegalArgumentException("manual buys only from " + FIRST_BUY + " to " + LAST_BUY
                    + " IST (square-off 15:20)");
        }
        String notTarget = notEntryTarget(account, day);
        if (notTarget != null) {
            throw new IllegalArgumentException(notTarget);
        }
        if (expectedSymbol == null || expectedSymbol.isBlank()) {
            // review 9 Nov 2026: never "whatever is best now" unseen; the trader confirms the contract
            throw new IllegalArgumentException("confirm the contract to buy (symbol) first");
        }
        refuseIfBusy(account, u, day);
        Pick pick = pick(u, s, day, lots, held(account.id()));
        Candidate c = pick.chosen();
        if (!expectedSymbol.equals(c.symbol())) {
            throw new IllegalArgumentException("the best ATM is now " + c.symbol() + " (you confirmed " + expectedSymbol
                    + "): confirm again");
        }
        String key = pick.instrumentKey();
        String killed = kills.engaged(account, STRATEGY, key);
        if (killed != null) {
            kills.audit(account, actor, "MANUAL_BUY_REFUSED", Map.of("symbol", c.symbol(), "kill_switch", killed));
            throw new IllegalArgumentException("kill switch engaged (" + killed + "): nothing sent");
        }
        long quantity = (long) lots * c.lotSize();
        String idempotency = idempotencyKey;
        Instant now = clock.instant();
        Map<String, Double> context = new LinkedHashMap<>();
        context.put("fixed_lots", (double) lots);
        context.put("strategy_lots", (double) lots);
        context.put("intended_lots", (double) lots);
        context.put("stop_pct", stopPct);
        long[] ids = transactions.execute(tx -> {
            // review 9 Nov 2026: two clicks (two tabs) both passed the checks above and wrote two buys; one account's buys
            // are written one at a time and checked again under the lock
            jdbc.query("select pg_advisory_xact_lock(?, ?)", (org.springframework.jdbc.core.RowCallbackHandler) rs -> { },
                    BUY_LOCK_CLASS, (int) account.id());
            refuseIfBusy(account, u, day);
            // the decision's notification is delivered at commit, with its batch and intent already there
            new SignalDecisionStore(dataSource).insert(new SignalDecision(copyGroupId, 0, day, now, STRATEGY, 0, u,
                    SignalDecision.Action.ENTER, s, null, "MANUAL", reason, c.token(), key,
                    c.symbol(), c.lotSize(), c.ask(), quantity * c.ask(), Double.NaN, lots, idempotency, context));
            long decision = jdbc.queryForObject("select id from exec.signal_decision where idempotency_key = ?", Long.class,
                    idempotency);
            long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, copy_group_id, signal_decision_id, "
                    + "source, idempotency_key, status) values (?, ?, ?, 'MANUAL', ?, 'FANNED_OUT') returning id", Long.class,
                    account.tenantId(), copyGroupId, decision, idempotency);
            long intent = jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                    + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, instrument_token, "
                    + "symbol, quantity, state, idempotency_key) values (?, ?, ?, ?, ?, ?, 0, ?, 'ENTER', ?, ?, ?, ?, "
                    + "'CREATED', ?) returning id", Long.class, account.tenantId(), batch, account.id(),
                    account.brokerType().name(), account.tradeMode().name(), STRATEGY, u, s, c.token(), c.symbol(), quantity,
                    idempotency + ":" + account.id());
            return new long[] {decision, intent};
        });
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("intent", ids[1]);
        audit.put("symbol", c.symbol());
        audit.put("quantity", quantity);
        audit.put("mode", account.tradeMode().name());
        audit.put("bid", c.bid());
        audit.put("ask", c.ask());
        audit.put("spread_pct", c.spreadPct());
        audit.put("spot", round(pick.spot()));
        kills.audit(account, actor, "MANUAL_BUY", audit);
        return new Placed(ids[0], ids[1], pick, quantity, account.tradeMode().name());
    }

    /**
     * A DAILY_SWITCH workspace takes entries only on its entry target now (the chosen paper account, or the chosen
     * broker's paper twin unless the day is LIVE there): any other of its accounts is refused here, before anything is
     * written (code review 9 Nov 2026: {@code idle} let a twin on a LIVE day, or a real account still holding a position
     * on a PAPER day, through, and the turn then rejected the written buy as TARGET_CHANGED). Null = may take a buy.
     * Replayed days (another date than today) are left alone, as {@link FollowerSimulator} does.
     */
    public String notEntryTarget(BrokerAccount account, LocalDate day) {
        if (!day.equals(TradingDay.today()) || !tradingDay.dailySwitch(account.tenantId())) {
            return null;
        }
        Long target = tradingDay.entryTargetNow(account.tenantId());
        if (Long.valueOf(account.id()).equals(target)) {
            return null;
        }
        String name = target == null ? null : jdbc.queryForList("select display_name from acct.broker_account where id = ?",
                String.class, target).stream().findFirst().orElse(String.valueOf(target));
        return account.displayName() + " is not its workspace's entry account now (" + (name == null
                ? "trading is Off today" : "entries go to " + name) + "): nothing sent";
    }

    /**
     * Refused while the account holds a manual position of the index or has today's manual buy of it not run to its end
     * (CREATED: not yet taken, or its broker orders waiting for a session). A buy whose outcome is unknown at the broker
     * engages the account's kill switch, which refuses the next one by itself.
     */
    private void refuseIfBusy(BrokerAccount account, String underlying, LocalDate day) {
        List<String> held = jdbc.queryForList("select symbol from exec.position where broker_account_id = ? and strategy_id = ? "
                + "and underlying = ? and status <> 'CLOSED'", String.class, account.id(), STRATEGY, underlying);
        if (!held.isEmpty()) {
            throw new IllegalArgumentException(account.displayName() + " already holds manual " + held.getFirst()
                    + ": EXIT it first");
        }
        List<String> working = jdbc.queryForList("select symbol from exec.trade_intent where broker_account_id = ? "
                + "and strategy_id = ? and underlying = ? and action = 'ENTER' and state = 'CREATED' "
                + "and (created_at at time zone 'Asia/Kolkata')::date = ?", String.class, account.id(), STRATEGY, underlying,
                Date.valueOf(day));
        if (!working.isEmpty()) {
            throw new IllegalArgumentException("a manual " + underlying + " buy is still working (" + working.getFirst()
                    + ")");
        }
    }

    // ------------------------------------------------------------------------------------------------- EXIT

    /**
     * Manual buys of the account that sent nothing yet (CREATED without an order: the turn has not taken them) are
     * cancelled; {@code underlying} null = every index. Run on the account's lane, so no turn is taking them at the same
     * moment. Review 9 Nov 2026: an intent stays CREATED while its orders wait at the broker for a session (or after a
     * worker stopped mid-entry); it is never cancelled here (its broker order would live on, untracked): it is reported,
     * and the next turn resumes it by its tag, books what filled with its stop, and EXIT then sells that. Returns the lines.
     */
    public List<String> cancelNotRun(BrokerAccount account, String underlying, String actor) {
        List<String> cancelled = jdbc.queryForList("update exec.trade_intent i set state = 'CANCELLED', reject_code = "
                + "'MANUAL_EXIT', reject_reason = 'EXIT before the buy ran', updated_at = now() where i.broker_account_id = ? "
                + "and i.strategy_id = ? and i.action = 'ENTER' and i.state = 'CREATED' and (? or i.underlying = ?) "
                + "and not exists (select 1 from exec.order_execution e where e.trade_intent_id = i.id) returning i.symbol",
                String.class, account.id(), STRATEGY, underlying == null, underlying == null ? "" : underlying);
        if (!cancelled.isEmpty()) {
            kills.audit(account, actor, "MANUAL_BUY_CANCELLED", Map.of("symbols", cancelled));
        }
        List<String> lines = new ArrayList<>();
        cancelled.forEach(symbol -> lines.add(symbol + ": buy cancelled before it was sent"));
        jdbc.queryForList("select i.symbol from exec.trade_intent i where i.broker_account_id = ? and i.strategy_id = ? "
                + "and i.action = 'ENTER' and i.state = 'CREATED' and (? or i.underlying = ?) and exists (select 1 from "
                + "exec.order_execution e where e.trade_intent_id = i.id)", String.class, account.id(), STRATEGY,
                underlying == null, underlying == null ? "" : underlying)
                .forEach(symbol -> lines.add(symbol + ": the buy is at the broker and its outcome is not known yet (no "
                        + "broker session?); once it shows filled, EXIT again, or cancel it in the broker's app"));
        return lines;
    }

    /** The account's open manual positions ({@code underlying} null = every index), oldest first. */
    public List<Long> openPositions(long accountId, String underlying) {
        return jdbc.queryForList("select id from exec.position where broker_account_id = ? and strategy_id = ? "
                + "and status <> 'CLOSED' and quantity_open > 0 and (? or underlying = ?) order by opened_at, id", Long.class,
                accountId, STRATEGY, underlying == null, underlying == null ? "" : underlying);
    }

    /**
     * A chart order's exit (14 Nov 2026): one EXIT decision of {@link #STRATEGY} and one EXIT intent for this account only,
     * for its open manual position of {@code underlying}, written in one transaction and announced like any decision. The
     * account's turn sells it as it sells any strategy's exit: the broker stop released first (what it sold is booked,
     * never sold twice), then a limit at the bid, chased and retried. {@code reason} becomes the position's exit reason.
     * The decision's price is the contract's latest bid in our capture (a PAPER account fills at it); IllegalArgumentException
     * when the account holds no manual position of the index. Returns the intent id.
     */
    public long exit(BrokerAccount account, long copyGroupId, String underlying, String actor, LocalDate day, String reason,
                     String idempotencyKey) {
        return exit(account, copyGroupId, underlying, actor, day, reason, idempotencyKey, null);
    }

    /**
     * As {@link #exit}, selling exactly {@code positionId} when given (a chart order's own position; review 16 Nov 2026,
     * D9: the oldest manual position of the index was sold, which a panel buy opened in the second after the chart
     * order's position closed could be); refused when that position is not open.
     */
    public long exit(BrokerAccount account, long copyGroupId, String underlying, String actor, LocalDate day, String reason,
                     String idempotencyKey, Long positionId) {
        String u = checkUnderlying(underlying);
        List<Map<String, Object>> held = jdbc.queryForList("select p.id, p.option_side, p.instrument_token, p.instrument_key, "
                + "p.symbol, p.quantity_open, d.lot_size from exec.position p join exec.trade_intent i on i.id = "
                + "p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d on "
                + "d.id = b.signal_decision_id where p.broker_account_id = ? and p.strategy_id = ? and p.underlying = ? "
                + "and p.status <> 'CLOSED' and p.quantity_open > 0 and (?::bigint is null or p.id = ?) "
                + "order by p.opened_at, p.id limit 1", account.id(), STRATEGY, u, positionId, positionId);
        if (held.isEmpty()) {
            throw new IllegalArgumentException(account.displayName() + (positionId == null ? " holds no manual " + u
                    + " position" : "'s position #" + positionId + " is not open"));
        }
        Map<String, Object> p = held.getFirst();
        long token = ((Number) p.get("instrument_token")).longValue();
        String key = InstrumentKeys.normalize((String) p.get("instrument_key"), token);
        String symbol = (String) p.get("symbol");
        String side = (String) p.get("option_side");
        long quantity = ((Number) p.get("quantity_open")).longValue();
        int lotSize = p.get("lot_size") == null ? lotSizeOf(token) : ((Number) p.get("lot_size")).intValue();
        Instant now = clock.instant();
        double bid = jdbc.queryForList("select bid_px[1] from md.option_tick where instrument_token = ? and session_date = ? "
                + "and recv_ts > ? and recv_ts <= ? order by recv_ts desc limit 1", Double.class, token, Date.valueOf(day),
                Timestamp.from(now.minus(QUOTE_MAX_AGE)), Timestamp.from(now.plusSeconds(5))).stream()
                .filter(java.util.Objects::nonNull).findFirst().orElse(Double.NaN);
        if (Double.isNaN(bid) && account.tradeMode() == TradeMode.PAPER) {
            // code review 15 Nov 2026: a PAPER account fills at the decision's price; without one the turn rejects the exit
            // (NO_EXIT_PRICE), so nothing is written and the caller tries again
            throw new IllegalArgumentException("no bid for " + symbol + " in the last " + QUOTE_MAX_AGE.toSeconds()
                    + " s: a PAPER exit needs one");
        }
        int lots = lotSize > 0 ? (int) Math.max(1, quantity / lotSize) : 1;
        Map<String, Double> context = new LinkedHashMap<>();
        context.put("quantity", (double) quantity);
        long intent = transactions.execute(tx -> {
            new SignalDecisionStore(dataSource).insert(new SignalDecision(copyGroupId, 0, day, now, STRATEGY, 0, u,
                    SignalDecision.Action.EXIT, side, null, "MANUAL", reason, token, key, symbol, lotSize, bid,
                    Double.NaN, Double.NaN, lots, idempotencyKey, context));
            long decision = jdbc.queryForObject("select id from exec.signal_decision where idempotency_key = ?", Long.class,
                    idempotencyKey);
            long batch = jdbc.queryForObject("insert into exec.trade_batch (tenant_id, copy_group_id, signal_decision_id, "
                    + "source, idempotency_key, status) values (?, ?, ?, 'MANUAL', ?, 'FANNED_OUT') returning id", Long.class,
                    account.tenantId(), copyGroupId, decision, idempotencyKey);
            return jdbc.queryForObject("insert into exec.trade_intent (tenant_id, trade_batch_id, broker_account_id, "
                    + "broker_type, execution_mode, strategy_id, priority, underlying, action, option_side, instrument_token, "
                    + "symbol, quantity, state, idempotency_key) values (?, ?, ?, ?, ?, ?, 0, ?, 'EXIT', ?, ?, ?, ?, "
                    + "'CREATED', ?) returning id", Long.class, account.tenantId(), batch, account.id(),
                    account.brokerType().name(), account.tradeMode().name(), STRATEGY, u, side, token, symbol, quantity,
                    idempotencyKey + ":" + account.id());
        });
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("intent", intent);
        audit.put("symbol", symbol);
        audit.put("quantity", quantity);
        audit.put("bid", Double.isNaN(bid) ? null : bid);
        audit.put("reason", reason);
        kills.audit(account, actor, "MANUAL_EXIT", audit);
        return intent;
    }

    // ------------------------------------------------------------------------------------------------- the panel

    /**
     * Today's manual buys of the account, newest first: the intent's state (and why it was refused), its orders (role,
     * state, filled of requested, average price) and its position (open quantity, entry, exit reason).
     */
    public List<Map<String, Object>> today(long accountId, LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> i : jdbc.queryForList("select i.id, i.underlying, i.option_side, i.symbol, i.quantity, "
                + "i.execution_mode, i.state, i.reject_code, i.reject_reason, i.created_at, d.reason from exec.trade_intent i "
                + "join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d on d.id = "
                + "b.signal_decision_id where i.broker_account_id = ? and i.strategy_id = ? and d.session_date = ? "
                + "order by i.id desc limit 20", accountId, STRATEGY, Date.valueOf(day))) {
            long intent = ((Number) i.get("id")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intentId", intent);
            row.put("underlying", i.get("underlying"));
            row.put("side", i.get("option_side"));
            row.put("symbol", i.get("symbol"));
            row.put("quantity", i.get("quantity"));
            row.put("mode", i.get("execution_mode"));
            row.put("state", i.get("state"));
            row.put("rejectCode", i.get("reject_code"));
            row.put("rejectReason", i.get("reject_reason"));
            row.put("requested", ist(((Timestamp) i.get("created_at")).toInstant()));
            row.put("by", i.get("reason"));
            List<Map<String, Object>> orders = new ArrayList<>();
            for (Map<String, Object> o : jdbc.queryForList("select role, order_side, status, quantity_requested, "
                    + "quantity_filled, limit_price, trigger_price, average_fill_price, raw_error, updated_at from "
                    + "exec.order_execution where trade_intent_id = ? and broker_account_id = ? order by id", intent,
                    accountId)) {
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("role", o.get("role"));
                order.put("side", o.get("order_side"));
                order.put("status", o.get("status"));
                order.put("requested", o.get("quantity_requested"));
                order.put("filled", o.get("quantity_filled"));
                order.put("limit", o.get("limit_price"));
                order.put("trigger", o.get("trigger_price"));
                order.put("average", o.get("average_fill_price"));
                order.put("error", o.get("raw_error"));
                order.put("at", ist(((Timestamp) o.get("updated_at")).toInstant()));
                orders.add(order);
            }
            row.put("orders", orders);
            jdbc.queryForList("select id, quantity_open, average_entry, status, exit_reason, realised_gross, charges "
                    + "from exec.position where entry_trade_intent_id = ? and broker_account_id = ?", intent, accountId)
                    .stream().findFirst().ifPresent(p -> {
                        Map<String, Object> position = new LinkedHashMap<>();
                        position.put("id", p.get("id"));
                        position.put("open", p.get("quantity_open"));
                        position.put("entry", p.get("average_entry"));
                        position.put("status", p.get("status"));
                        position.put("exitReason", p.get("exit_reason"));
                        position.put("gross", p.get("realised_gross"));
                        position.put("charges", p.get("charges"));
                        row.put("position", position);
                    });
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static String checkUnderlying(String underlying) {
        String u = underlying == null ? "" : underlying.trim().toUpperCase(java.util.Locale.ROOT);
        if (!UNDERLYINGS.contains(u)) {
            throw new IllegalArgumentException("index: NIFTY or SENSEX");
        }
        return u;
    }

    private static String checkSide(String side) {
        String s = side == null ? "" : side.trim().toUpperCase(java.util.Locale.ROOT);
        if (!s.equals("CE") && !s.equals("PE")) {
            throw new IllegalArgumentException("side: CE or PE");
        }
        return s;
    }

    private static String ist(Instant at) {
        return at.atZone(IST).toLocalDateTime().toString();
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
