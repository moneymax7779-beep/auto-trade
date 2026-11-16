package com.autotrade.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.AccountStore;
import com.autotrade.account.BrokerAccount;
import com.autotrade.account.TradeMode;

/**
 * Watches the day's chart orders ({@link ChartOrders}) on every index tick and acts on them (plan
 * docs/plans/2026-11-14-chart-line-trading-plan.md, section 3.2). The live session hands it each index tick in memory
 * ({@link #onIndexTick}: a few comparisons, never a database read); an order whose line is crossed is acted on by one
 * background thread, so the feed thread never waits:
 * <ul>
 *   <li>entry crossed: ARMED → TRIGGERED, then the best ATM of the side is bought through {@link ManualOrders} (one
 *       decision and one intent for the order's account, the account's turn runs at once, with every risk check and
 *       kill switch), its broker stop at {@link ChartLines#safetyStopPct}; a refusal → REFUSED with the reason;</li>
 *   <li>the stop line reached first, or the first tick past the entry beyond the no-chase limit → VOID;</li>
 *   <li>stop or target crossed on an open position: OPEN → EXITING, and one EXIT intent sells it ({@link
 *       ManualOrders#exit}), its exit reason CHART_STOP or CHART_TARGET.</li>
 * </ul>
 * Each change is a compare-and-set on the order's status and version: a line moved by the trader a moment earlier
 * wins, and the engine acts on the new lines from the next tick; two engines (a restart overlap) cannot both act.
 * <p>
 * {@link #sync} (every second) follows what the account's turn did: TRIGGERED → OPEN once the position exists, REFUSED
 * when the buy was refused or did not fill; OPEN / EXITING → CLOSED once the position is closed (by this engine, the
 * trader's Exit, the broker stop or the end-of-day square-off); EXITING → OPEN when the exit was not done (it fires
 * again on a tick past the line {@link #EXIT_RETRY} later); a buy or sell begun but not recorded (a crash, a failed
 * write) is found by its idempotency key, or repaired after {@link #QUIET_SECONDS}; ARMED → EXPIRED after its
 * valid-until. It then reloads the orders it watches.
 * After a restart nothing is entered late: an entry line crossed while the engine was down is entered only if the
 * first tick seen is within the no-chase limit of it, and a stop crossed meanwhile sells at the first tick.
 */
public final class ChartOrderEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChartOrderEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Who the engine's events and audits name. */
    public static final String ACTOR = "chart-engine";
    static final long SYNC_MS = 1_000;
    /**
     * A TRIGGERED order with no buy, or an EXITING one with no sell, written and unchanged this long (and not being
     * written by this engine now): the engine stopped, or a write failed, in between; it is repaired (code review 15 Nov
     * 2026: an EXITING order was left so for good, its position no longer watched).
     */
    static final int QUIET_SECONDS = 15;
    /** After an exit that was not written or not done, the same order's line fires again no sooner than this. */
    static final Duration EXIT_RETRY = Duration.ofSeconds(5);
    private static final List<String> ENDED = List.of("REJECTED", "SKIPPED", "FAILED", "CANCELLED", "EXECUTED");

    private final JdbcTemplate jdbc;
    private final ChartOrders orders;
    private final ManualOrders manual;
    private final AccountStore accounts;
    private final LocalDate day;
    private Clock clock = Clock.systemUTC();
    /** The ARMED and OPEN orders by index, as of the last {@link #sync}. */
    private volatile Map<String, List<ChartOrders.Order>> watched = Map.of();
    /** Order id → the version acted on: not acted on again until a reload brings a newer version. */
    private final Map<Long, Integer> acted = new ConcurrentHashMap<>();
    /** Orders whose buy or sell this engine is writing now (the sync's repairs leave them alone). */
    private final java.util.Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** Order id → the time its exit may fire again (code review 15 Nov 2026: a failing exit was retried on every tick). */
    private final Map<Long, Instant> retryAfter = new ConcurrentHashMap<>();
    /** An Exit now whose engine sale failed: when to try it again, and how many times it was tried (capped). */
    private final Map<Long, Instant> traderExitRetry = new ConcurrentHashMap<>();
    private final Map<Long, Integer> traderExitTries = new ConcurrentHashMap<>();
    static final int TRADER_EXIT_TRIES = 20;
    private ExecutorService actions;
    private ScheduledExecutorService timer;
    private boolean inline;

    public ChartOrderEngine(DataSource dataSource, LocalDate day) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.orders = new ChartOrders(dataSource);
        this.manual = new ManualOrders(dataSource);
        this.accounts = new AccountStore(dataSource);
        this.day = day;
    }

    /** Tests: the clock of trading hours, quotes and valid-until. */
    public ChartOrderEngine withClock(Clock clock) {
        this.clock = clock;
        orders.withClock(clock);
        manual.withClock(clock);
        return this;
    }

    /** Tests: act on the calling thread (no background threads; call {@link #sync} by hand). */
    public ChartOrderEngine inline() {
        this.inline = true;
        sync();
        return this;
    }

    /** Starts the action thread and the one-second sync. */
    public ChartOrderEngine start() {
        actions = Executors.newSingleThreadExecutor(r -> daemon(r, "chart-orders"));
        timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "chart-orders-sync"));
        timer.scheduleWithFixedDelay(this::syncSafely, 0, SYNC_MS, TimeUnit.MILLISECONDS);
        log.info("chart orders: watching {}'s orders", day);
        return this;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ------------------------------------------------------------------------------------------------- the tick

    /** One index tick (from the live session's feed thread). Cheap: comparisons only; actions run elsewhere. */
    public void onIndexTick(String underlying, double price, Instant at) {
        if (!(price > 0)) {
            return;
        }
        List<ChartOrders.Order> list = watched.get(underlying);
        if (list == null || list.isEmpty()) {
            return;
        }
        Instant when = at == null ? clock.instant() : at;
        LocalTime time = LocalTime.ofInstant(when, IST);
        if (!ChartLines.checked(time)) {
            return;                                                // the pre-open auction's index (ChartLines#FIRST_CHECK)
        }
        for (ChartOrders.Order o : list) {
            Integer done = acted.get(o.id());
            if (done != null && done >= o.version()) {
                continue;
            }
            Instant retry = retryAfter.get(o.id());
            if ("OPEN".equals(o.status()) && retry != null && when.isBefore(retry)) {
                continue;
            }
            ChartLines.Event event = switch (o.status()) {
                case "ARMED" -> time.isBefore(o.validUntil()) ? ChartLines.onArmed(o.lines(), o.kind(), price)
                        : ChartLines.Event.NONE;
                case "OPEN" -> ChartLines.onOpen(o.lines(), price);
                default -> ChartLines.Event.NONE;
            };
            if (event == ChartLines.Event.NONE) {
                continue;
            }
            acted.put(o.id(), o.version());
            Runnable work = () -> actSafely(o, event, price, when);
            if (inline) {
                work.run();
            } else {
                actions.execute(work);
            }
        }
    }

    private void actSafely(ChartOrders.Order o, ChartLines.Event event, double price, Instant at) {
        try {
            act(o, event, price, at);
        } catch (RuntimeException e) {
            log.warn("chart order #{}: {} failed: {}", o.id(), event, e.toString());
            if (event == ChartLines.Event.STOP || event == ChartLines.Event.TARGET) {
                // review 16 Nov 2026: marked acted before it ran, a failed exit (a database error on OPEN -> EXITING) was
                // never tried again at that version, so the chart stop was off for the day; tried again EXIT_RETRY later.
                // An entry is not: a missed entry is the safe side
                retryAfter.put(o.id(), at.plus(EXIT_RETRY));
                acted.remove(o.id(), o.version());
            }
        }
    }

    private void act(ChartOrders.Order o, ChartLines.Event event, double price, Instant at) {
        switch (event) {
            case ENTER -> enter(o, price, at);
            case VOID_STOP_FIRST -> end(o, "VOID", "the index reached the stop line (" + price + ") before the entry");
            case VOID_CHASE -> end(o, "VOID", "the first tick past the entry was " + price + ", beyond the no-chase limit of "
                    + o.noChase() + " points or at the target: not chased");
            case STOP -> exit(o, "STOP", price, at);
            case TARGET -> exit(o, "TARGET", price, at);
            default -> { }
        }
    }

    private void end(ChartOrders.Order o, String status, String reason) {
        if (orders.transition(o.id(), o.version(), o.status(), status, reason, Map.of())) {
            orders.event(o.id(), status, ACTOR, null, Map.of("reason", reason));
            log.info("chart order #{} {}: {}", o.id(), status, reason);
        }
    }

    /** A chart order's entry decision key; its intent's key is this + ":" + the account. */
    static String enterKey(long orderId) {
        return "CHART:" + orderId + ":ENTER";
    }

    /** A chart order's exit decision key at the version its EXITING has. */
    static String exitKey(long orderId, int version) {
        return "CHART:" + orderId + ":EXIT:" + version;
    }

    private void enter(ChartOrders.Order o, double price, Instant at) {
        inFlight.add(o.id());
        try {
            Map<String, Object> sets = new LinkedHashMap<>();
            sets.put("triggered_at", at);
            sets.put("trigger_index", price);
            if (!orders.transition(o.id(), o.version(), "ARMED", "TRIGGERED", null, sets)) {
                return;                                            // moved or cancelled a moment ago
            }
            int version = o.version() + 1;
            orders.event(o.id(), "TRIGGERED", ACTOR, price, Map.of("entry", o.entry(), "kind", o.kind().name()));
            String key = enterKey(o.id());
            ManualOrders.Pick pick;
            double stopPct;
            ManualOrders.Placed placed;
            try {
                BrokerAccount account = accounts.byId(o.accountId())
                        .orElseThrow(() -> new IllegalArgumentException("account " + o.accountId() + " is gone"));
                if (account.tradeMode() != TradeMode.PAPER) {
                    throw new IllegalArgumentException("chart orders are PAPER only in this first version; "
                            + account.displayName() + " is " + account.tradeMode());
                }
                if ("TRADER_EXIT".equals(orders.byId(o.id()).map(ChartOrders.Order::exitTrigger).orElse(null))) {
                    // the trader's Exit now came between the trigger and the buy: nothing is bought
                    String why = "Exit now before the buy was written";
                    if (orders.transition(o.id(), version, "TRIGGERED", "CANCELLED", why, Map.of())) {
                        orders.event(o.id(), "CANCELLED", ACTOR, price, Map.of("reason", why));
                    }
                    return;
                }
                pick = manual.pick(account, o.underlying(), o.side(), day);
                stopPct = ChartLines.safetyStopPct(o.lines(), pick.chosen().ask());
                placed = manual.buy(account, o.copyGroupId(), o.underlying(), o.side(), pick.chosen().symbol(),
                        "chart #" + o.id() + " (" + o.createdBy() + ")", day, o.lots(), stopPct, "chart order #" + o.id()
                                + ": " + o.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + o.side() + " at index "
                                + price, key);
            } catch (RuntimeException e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                // code review 15 Nov 2026: the buy may be written although the call failed after its commit (its audit):
                // refusing the order then left a position that no line watched
                Long written = orders.intentByKey(key + ":" + o.accountId());
                if (written != null) {
                    orders.set(o.id(), "entry_intent_id", written);
                    orders.event(o.id(), "BUY_SENT", ACTOR, price, Map.of("intent", written, "note",
                            "written; the call failed after it: " + why));
                    log.warn("chart order #{}: buy {} written, then: {}", o.id(), written, why);
                    return;
                }
                orders.transition(o.id(), version, "TRIGGERED", "REFUSED", why, Map.of());
                orders.event(o.id(), "REFUSED", ACTOR, price, Map.of("reason", why));
                log.info("chart order #{} refused: {}", o.id(), why);
                return;
            }
            try {
                orders.set(o.id(), "entry_intent_id", placed.intentId());
                orders.set(o.id(), "symbol", pick.chosen().symbol());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("symbol", pick.chosen().symbol());
                detail.put("quantity", placed.quantity());
                detail.put("ask", pick.chosen().ask());
                detail.put("broker_stop_pct", stopPct);
                detail.put("intent", placed.intentId());
                orders.event(o.id(), "BUY_SENT", ACTOR, price, detail);
            } catch (RuntimeException e) {
                log.warn("chart order #{}: buy {} written, its record failed ({}): the sync finds it by its key", o.id(),
                        placed.intentId(), e.toString());
            }
            log.info("chart order #{}: {} {} at index {}, intent {}", o.id(), placed.quantity(), pick.chosen().symbol(), price,
                    placed.intentId());
        } finally {
            inFlight.remove(o.id());
        }
    }

    /**
     * Sells an open order's position: STOP or TARGET (its line crossed), or TRADER_EXIT (Exit now came before the buy
     * filled). {@code price}: the index then, NaN when no tick caused it.
     */
    private void exit(ChartOrders.Order o, String trigger, double price, Instant at) {
        inFlight.add(o.id());
        try {
            Map<String, Object> sets = new LinkedHashMap<>();
            sets.put("exit_trigger", trigger);
            sets.put("exit_index", Double.isNaN(price) ? null : price);
            if (!orders.transition(o.id(), o.version(), "OPEN", "EXITING", null, sets)) {
                return;
            }
            int version = o.version() + 1;
            String key = exitKey(o.id(), version);
            long intent;
            try {
                BrokerAccount account = accounts.byId(o.accountId())
                        .orElseThrow(() -> new IllegalArgumentException("account " + o.accountId() + " is gone"));
                intent = manual.exit(account, o.copyGroupId(), o.underlying(), "chart #" + o.id() + " (" + o.createdBy()
                        + ")", day, "TRADER_EXIT".equals(trigger) ? trigger : "CHART_" + trigger, key, o.positionId());
            } catch (RuntimeException e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                Long written = orders.intentByKey(key + ":" + o.accountId());
                if (written == null) {
                    retryAfter.put(o.id(), at.plus(EXIT_RETRY));
                    if ("TRADER_EXIT".equals(trigger)) {
                        traderExitRetry.putIfAbsent(o.id(), at.plus(EXIT_RETRY));
                    }
                    orders.transition(o.id(), version, "EXITING", "OPEN", "exit not written: " + why, Map.of());
                    orders.event(o.id(), "EXIT_NOT_WRITTEN", ACTOR, Double.isNaN(price) ? null : price,
                            Map.of("trigger", trigger, "reason", why));
                    log.warn("chart order #{}: {} exit not written: {}", o.id(), trigger, why);
                    return;
                }
                intent = written;
            }
            try {
                orders.set(o.id(), "exit_intent_id", intent);
                Map<String, Object> detail = new LinkedHashMap<>();
                Double line = "STOP".equals(trigger) ? Double.valueOf(o.stop())
                        : "TARGET".equals(trigger) ? Double.valueOf(o.target()) : null;
                detail.put("line", line);
                detail.put("intent", intent);
                orders.event(o.id(), trigger, ACTOR, Double.isNaN(price) ? null : price, detail);
            } catch (RuntimeException e) {
                log.warn("chart order #{}: exit {} written, its record failed ({}): the sync finds it by its key", o.id(),
                        intent, e.toString());
            }
            log.info("chart order #{}: {} at index {}, exit intent {}", o.id(), trigger, price, intent);
        } finally {
            inFlight.remove(o.id());
        }
    }

    // ------------------------------------------------------------------------------------------------- the sync

    private void syncSafely() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("chart orders: sync failed: {}", e.toString());
        }
    }

    /** Follows the account turns' outcomes, expires waiting orders, and reloads the watched orders. */
    public synchronized void sync() {
        // review 16 Nov 2026 (D6): the heartbeat arm and move check (the database's clock, as theirs); first, so a sync
        // that fails further on still says the engine runs, and one that cannot write this says nothing
        jdbc.update("insert into exec.chart_engine_beat (session_date, beat_at) values (?, now()) on conflict (session_date) "
                + "do update set beat_at = now()", java.sql.Date.valueOf(day));
        Instant now = clock.instant();
        LocalTime time = LocalTime.ofInstant(now, IST);
        List<ChartOrders.Order> working = new ArrayList<>(orders.workingBefore(day));
        working.addAll(orders.working(day));
        for (ChartOrders.Order o : working) {
            try {
                follow(o, now, time);
            } catch (RuntimeException e) {
                log.warn("chart order #{}: sync failed: {}", o.id(), e.toString());
            }
        }
        // expire the waiting orders of earlier days, too (a day with no session leaves them ARMED)
        for (Long id : jdbc.queryForList("update exec.chart_order set status = 'EXPIRED', status_reason = 'the day ended', "
                + "version = version + 1, updated_at = now() where status = 'ARMED' and session_date < ? returning id",
                Long.class, java.sql.Date.valueOf(day))) {
            orders.event(id, "EXPIRED", ACTOR, null, Map.of("reason", "the day ended"));
        }
        Map<String, List<ChartOrders.Order>> next = new HashMap<>();
        for (ChartOrders.Order o : orders.working(day)) {
            if ("ARMED".equals(o.status()) || "OPEN".equals(o.status())) {
                next.computeIfAbsent(o.underlying(), u -> new ArrayList<>()).add(o);
            }
        }
        java.util.Set<Long> live = new java.util.HashSet<>();
        next.values().forEach(list -> list.forEach(o -> live.add(o.id())));
        acted.keySet().retainAll(live);
        retryAfter.keySet().retainAll(live);
        traderExitRetry.keySet().retainAll(live);
        traderExitTries.keySet().retainAll(live);
        watched = Map.copyOf(next);
    }

    private void follow(ChartOrders.Order o, Instant now, LocalTime time) {
        switch (o.status()) {
            case "ARMED" -> {
                if (!time.isBefore(o.validUntil())) {
                    String why = "valid until " + o.validUntil() + " passed";
                    if (orders.transition(o.id(), o.version(), "ARMED", "EXPIRED", why, Map.of())) {
                        orders.event(o.id(), "EXPIRED", ACTOR, null, Map.of("reason", why));
                    }
                }
            }
            case "TRIGGERED" -> followEntry(o, now);
            case "OPEN", "EXITING" -> followPosition(o);
            default -> { }
        }
    }

    private void followEntry(ChartOrders.Order o, Instant now) {
        Long entryIntent = o.entryIntentId();
        if (entryIntent == null) {
            if (inFlight.contains(o.id())) {
                return;                                            // being written now
            }
            // code review 15 Nov 2026: the buy may be written with its record lost (a crash, a failed write): its key finds it
            entryIntent = orders.intentByKey(enterKey(o.id()) + ":" + o.accountId());
            if (entryIntent == null) {
                String why = "the buy was not written after the trigger (the engine stopped in between)";
                if (orders.repair(o.id(), o.version(), "TRIGGERED", "REFUSED", why, "entry_intent_id", QUIET_SECONDS)) {
                    orders.event(o.id(), "REFUSED", ACTOR, null, Map.of("reason", why));
                }
                return;
            }
            orders.set(o.id(), "entry_intent_id", entryIntent);
        }
        boolean traderExit = "TRADER_EXIT".equals(o.exitTrigger());
        Map<String, Object> position = position(entryIntent);
        Map<String, Object> intent = intent(entryIntent);
        String state = (String) intent.get("state");
        if (position != null) {
            boolean open = "OPEN".equals(position.get("status")) && ((Number) position.get("quantity_open")).longValue() > 0;
            Map<String, Object> sets = Map.of("position_id", ((Number) position.get("id")).longValue());
            if (open && orders.transition(o.id(), o.version(), "TRIGGERED", "OPEN", null, sets)) {
                orders.event(o.id(), "FILLED", ACTOR, null, Map.of("quantity", position.get("quantity_open"),
                        "average", position.get("average_entry")));
                if (traderExit) {
                    // Exit now came while the buy was being written: what it bought is sold at once
                    orders.byId(o.id()).ifPresent(filled -> exit(filled, "TRADER_EXIT", Double.NaN, now));
                }
            } else if (!open && orders.transition(o.id(), o.version(), "TRIGGERED", "CLOSED",
                    "closed as " + position.get("exit_reason"), sets)) {
                orders.event(o.id(), "CLOSED", ACTOR, null, closed(position));
            }
        } else if (ENDED.contains(state)) {
            boolean cancelled = traderExit && "CANCELLED".equals(state);
            String why = cancelled ? "Exit now before the buy ran" : "EXECUTED".equals(state) ? "nothing filled"
                    : state + (intent.get("reject_code") == null ? "" : " " + intent.get("reject_code"))
                            + (intent.get("reject_reason") == null ? "" : ": " + intent.get("reject_reason"));
            String to = cancelled ? "CANCELLED" : "REFUSED";
            if (orders.transition(o.id(), o.version(), "TRIGGERED", to, why, Map.of())) {
                orders.event(o.id(), to, ACTOR, null, Map.of("reason", why));
            }
        }
    }

    private void followPosition(ChartOrders.Order o) {
        Map<String, Object> position = o.positionId() != null ? positionById(o.positionId())
                : o.entryIntentId() != null ? position(o.entryIntentId()) : null;
        if (position == null) {
            // no position from the buy (none filled): ended once the buy has
            Map<String, Object> intent = o.entryIntentId() == null ? null : intent(o.entryIntentId());
            if (intent == null || ENDED.contains((String) intent.get("state"))) {
                if (orders.transition(o.id(), o.version(), o.status(), "CLOSED", "nothing was bought", Map.of())) {
                    orders.event(o.id(), "CLOSED", ACTOR, null, Map.of("reason", "nothing was bought"));
                }
            }
            return;
        }
        if (o.positionId() == null) {
            orders.set(o.id(), "position_id", ((Number) position.get("id")).longValue());
        }
        boolean open = "OPEN".equals(position.get("status")) && ((Number) position.get("quantity_open")).longValue() > 0;
        if (!open) {
            if (orders.transition(o.id(), o.version(), o.status(), "CLOSED", "closed as " + position.get("exit_reason"),
                    Map.of())) {
                orders.event(o.id(), "CLOSED", ACTOR, null, closed(position));
            }
            return;
        }
        if ("OPEN".equals(o.status()) && "TRADER_EXIT".equals(o.exitTrigger())) {
            // review 16 Nov 2026 (D9): the engine's own sale for an Exit now failed (not written, or refused): it went back
            // to OPEN and only its lines were watched, the trader's Exit forgotten. Tried again after the pause. Only after
            // a failed try of the engine's (its pause set): the web's Exit now runs its own sale
            // second review: its own schedule (a failed STOP left retryAfter set, and a later web Exit now was then sold
            // by the engine too), the next try set before this one (a try that throws is tried again), capped
            Instant retry = traderExitRetry.get(o.id());
            Instant now = clock.instant();
            if (retry != null && !now.isBefore(retry) && !inFlight.contains(o.id())) {
                int tries = traderExitTries.merge(o.id(), 1, Integer::sum);
                if (tries > TRADER_EXIT_TRIES) {
                    traderExitRetry.remove(o.id());
                    log.error("chart order #{}: Exit now not done after {} tries: its lines are watched, sell it by hand",
                            o.id(), TRADER_EXIT_TRIES);
                    orders.event(o.id(), "EXIT_GAVE_UP", ACTOR, null, Map.of("tries", TRADER_EXIT_TRIES));
                    return;
                }
                traderExitRetry.put(o.id(), now.plus(EXIT_RETRY));
                exit(o, "TRADER_EXIT", Double.NaN, now);
            }
            return;
        }
        if (!"EXITING".equals(o.status())) {
            return;
        }
        Long exitIntent = o.exitIntentId();
        if (exitIntent == null) {
            if (inFlight.contains(o.id())) {
                return;                                            // being written now
            }
            exitIntent = orders.intentByKey(exitKey(o.id(), o.version()) + ":" + o.accountId());
            if (exitIntent == null) {
                // code review 15 Nov 2026: EXITING with no sell written (a crash in between) was never watched again
                String why = "the exit was not written (the engine stopped in between): its lines are watched again";
                if (orders.repair(o.id(), o.version(), "EXITING", "OPEN", why, "exit_intent_id", QUIET_SECONDS)) {
                    orders.event(o.id(), "EXIT_NOT_WRITTEN", ACTOR, null, Map.of("reason", why));
                }
                return;
            }
            orders.set(o.id(), "exit_intent_id", exitIntent);
        }
        Map<String, Object> intent = intent(exitIntent);
        String state = (String) intent.get("state");
        if (ENDED.contains(state) && !"EXECUTED".equals(state)) {
            String why = "exit not done: " + state + (intent.get("reject_reason") == null ? ""
                    : " (" + intent.get("reject_reason") + ")");
            retryAfter.put(o.id(), clock.instant().plus(EXIT_RETRY));
            if ("TRADER_EXIT".equals(o.exitTrigger())) {
                traderExitRetry.putIfAbsent(o.id(), clock.instant().plus(EXIT_RETRY));
            }
            if (orders.transition(o.id(), o.version(), "EXITING", "OPEN", why, Map.of())) {
                orders.event(o.id(), "EXIT_NOT_DONE", ACTOR, null, Map.of("reason", why));
            }
        }
    }

    private Map<String, Object> position(long entryIntentId) {
        return jdbc.queryForList("select id, status, quantity_open, average_entry, exit_reason, realised_gross, charges from "
                + "exec.position where entry_trade_intent_id = ? order by id limit 1", entryIntentId).stream().findFirst()
                .orElse(null);
    }

    private Map<String, Object> positionById(long id) {
        return jdbc.queryForList("select id, status, quantity_open, average_entry, exit_reason, realised_gross, charges from "
                + "exec.position where id = ?", id).stream().findFirst().orElse(null);
    }

    private Map<String, Object> intent(long id) {
        return jdbc.queryForMap("select state, reject_code, reject_reason from exec.trade_intent where id = ?", id);
    }

    private static Map<String, Object> closed(Map<String, Object> position) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exit_reason", position.get("exit_reason"));
        m.put("gross", position.get("realised_gross"));
        m.put("charges", position.get("charges"));
        return m;
    }

    /** What the engine watches now (tests and the status line). */
    public Map<String, Integer> watching() {
        Map<String, Integer> m = new LinkedHashMap<>();
        watched.forEach((u, list) -> m.put(u, list.size()));
        return m;
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
        try {                                                     // second review: no heartbeat left for a closed engine
            jdbc.update("delete from exec.chart_engine_beat where session_date = ?", java.sql.Date.valueOf(day));
        } catch (RuntimeException e) {
            log.warn("chart engine heartbeat not cleared: {}", e.getMessage());
        }
        if (actions != null) {
            actions.shutdown();
            try {
                actions.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
