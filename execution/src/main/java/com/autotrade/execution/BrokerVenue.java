package com.autotrade.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.ToLongBiFunction;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.account.BrokerAccount;
import com.autotrade.broker.BrokerConnection;
import com.autotrade.broker.BrokerException;
import com.autotrade.broker.BrokerException.Kind;
import com.autotrade.broker.BrokerOrder;
import com.autotrade.broker.OrderRequest;
import com.autotrade.broker.OrderSide;
import com.autotrade.broker.OrderStatus;
import com.autotrade.broker.OrderType;
import com.autotrade.broker.Quote;

/**
 * A follower's orders sent to its broker (live plan sections 3 and 4), one account per venue, run on the account's own
 * lane:
 * <ul>
 *   <li><b>Entry / add</b>: a marketable limit at the broker's ask + {@code entryBufferTicks}, sliced to the contract's
 *       freeze quantity (each child its own tag); what is unfilled after {@code entryTimeoutSec} is cancelled and the
 *       position is what filled. No ask = no order.</li>
 *   <li><b>Exit</b>: a limit at the bid − max(buffer ticks, buffer %), re-priced at the new touch every
 *       {@code exitChaseSec} up to {@code exitChaseMax} times, then {@code exitCrossPct} through the touch; what is still
 *       unfilled is returned as such (the caller re-arms the stop, kills the account's entries and alerts).</li>
 *   <li><b>Protective stop</b>: after every fill a resting stop-limit sell for the whole open quantity at the premium
 *       stop (re-sized after an add, in place when it fits one order); before an exit it is cancelled first, and what it
 *       already sold is booked instead of sending an exit (no double sell). Stops that sell while held are found every
 *       turn ({@link #stopFills}).</li>
 *   <li><b>Idempotent and restart-safe</b>: every child order is a row of exec.order_execution, written before the
 *       place call, keyed by its deterministic tag. A turn that meets an existing row (a restart mid-order, a retried
 *       intent) looks the tag up at the broker and resumes it; an order whose outcome stays unknown is reported as
 *       such (the account is then killed and reconciled), never placed twice.</li>
 * </ul>
 * {@code beforeOrder} lets a DRY_RUN replay set the local simulator's clock and book first; it is a no-op for a broker.
 */
public final class BrokerVenue implements FollowerVenue {

    private static final Logger log = LoggerFactory.getLogger(BrokerVenue.class);
    /** How long a placed order may be missing from the broker's book before it is cancelled and reported unknown. */
    static final double NOT_SEEN_SEC = 5;
    /**
     * How long an exit waits in the same turn for its stops' cancels to show as ended in the broker's book (read every
     * poll interval) before it is left for the next turn. 4 Nov 2026, first LIVE day: one read straight after the cancel
     * still showed the stop working, so both exits went out a turn (about 1.15 s) later.
     */
    static final double STOP_RELEASE_WAIT_SEC = 2;
    /**
     * Review 8 Nov 2026: how long the order book may stay unreadable while a placed order has never been seen before the
     * order is cancelled and reported unknown (a failed read is not "absent": Kite's read timeout is itself 5 s).
     */
    static final double UNREADABLE_SEC = 30;
    /**
     * Review 8 Nov 2026: an order of unknown outcome that has no broker order id and has not been in any readable book
     * this long after its last update never reached the broker (the gateway already looked it up by tag).
     */
    static final double NEVER_REACHED_SEC = 60;
    /** Today's start in IST, for the rows of today's orders (Kite's book is the day's). */
    private static final String TODAY_IST = "((now() at time zone 'Asia/Kolkata')::date::timestamp at time zone 'Asia/Kolkata')";
    private static final List<String> ACTIVE = List.of("CREATED", "SUBMITTED", "ACKNOWLEDGED", "OPEN", "PARTIALLY_FILLED",
            "CANCEL_PENDING", "UNKNOWN");

    private final JdbcTemplate jdbc;
    private final BrokerConnection broker;
    private final OrderRules rules;
    private final ToLongBiFunction<String, String> maxQuantityPerOrder;
    private final Consumer<OrderSpec> beforeOrder;

    /**
     * @param maxQuantityPerOrder the contract's freeze quantity by (our instrument key, underlying); 0 = unknown: an entry
     *                            is refused, an exit goes as one order ({@link FreezeQuantities})
     */
    public BrokerVenue(DataSource dataSource, BrokerConnection broker, OrderRules rules,
                       ToLongBiFunction<String, String> maxQuantityPerOrder, Consumer<OrderSpec> beforeOrder) {
        if (rules == null) {
            throw new IllegalArgumentException("a broker-routed account needs a risk file with order rules (v3)");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.broker = broker;
        this.rules = rules;
        this.maxQuantityPerOrder = maxQuantityPerOrder;
        this.beforeOrder = beforeOrder == null ? o -> { } : beforeOrder;
    }

    @Override
    public boolean broker() {
        return true;
    }

    @Override
    public boolean live() {
        return broker instanceof LiveGuard;
    }

    @Override
    public double funds(BrokerAccount account) {
        try {
            return broker.availableFunds();
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            log.warn("account {}: funds unknown ({}); the entry is refused", account.id(), e.getMessage());
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------ entries, adds and exits

    @Override
    public Fill execute(OrderSpec o) {
        beforeOrder.accept(o);
        boolean buy = o.side().equals("BUY");
        long perOrder = maxQuantityPerOrder.applyAsLong(o.instrumentKey(), o.underlying());
        if (buy && (perOrder <= 0 || o.instrumentKey() == null)) {
            return Fill.none("NO_CONTRACT", "the contract's freeze quantity or key is unknown: no entry", o.at());
        }
        long maxLots = perOrder <= 0 ? Long.MAX_VALUE : Math.max(1, perOrder / o.lotSize());
        List<Long> slices = new ArrayList<>();
        for (long left = o.quantity(); left > 0; ) {
            long slice = Math.min(left, maxLots == Long.MAX_VALUE ? left : maxLots * o.lotSize());
            slices.add(slice);
            left -= slice;
        }
        List<Part> parts = new ArrayList<>();
        Instant at = o.at();
        String code = "FILLED", reason = null;
        for (int i = 0; i < slices.size(); i++) {
            String tag = slices.size() == 1 ? o.tag() : o.tag() + "N" + (i + 1);
            Child c = work(o, tag, slices.get(i), buy, true);
            if (c.filled > 0) {
                parts.add(new Part(c.filled, c.price));
            }
            at = c.at != null ? c.at : at;
            if (c.code != null) {
                code = c.code;
                reason = c.reason;
            }
            if (c.code != null && !c.code.equals("PARTIAL")) {
                break;                                  // refused, unknown or not filled: no further children
            }
        }
        long filled = parts.stream().mapToLong(Part::quantity).sum();
        if (filled == 0) {
            return Fill.none(code.equals("FILLED") ? "NOT_FILLED" : code, reason, at);
        }
        return Fill.of(parts, at, filled == o.quantity() ? "FILLED" : code, reason);
    }

    private static final class Child {
        long filled;
        double price;
        Instant at;
        String code;
        String reason;
    }

    @Override
    public Fill resume(OrderSpec o) {
        beforeOrder.accept(o);
        List<Part> parts = new ArrayList<>();
        Instant at = o.at();
        String code = null, reason = null;
        for (Map<String, Object> r : jdbc.queryForList("select client_tag, quantity_requested from exec.order_execution "
                + "where broker_account_id = ? and trade_intent_id = ? and role = ? order by id", o.account().id(),
                o.intentId(), o.role())) {
            Child c = work(o, (String) r.get("client_tag"), ((Number) r.get("quantity_requested")).longValue(),
                    o.side().equals("BUY"), false);
            if (c.filled > 0) {
                parts.add(new Part(c.filled, c.price));
            }
            at = c.at != null ? c.at : at;
            if (c.code != null && (code == null || c.code.equals("ORDER_UNKNOWN"))) {
                code = c.code;                                 // an unknown child decides: the account is reconciled
                reason = c.reason;
            }
        }
        long filled = parts.stream().mapToLong(Part::quantity).sum();
        if (filled == 0) {
            return Fill.none(code == null ? "NOT_FILLED" : code, reason == null ? "nothing filled" : reason, at);
        }
        return Fill.of(parts, at, code == null ? "FILLED" : code, reason);
    }

    /**
     * One child order to its end: resumed by tag when already recorded, else placed (only when {@code mayPlace}); then
     * worked as entry or exit.
     */
    private Child work(OrderSpec o, String tag, long quantity, boolean entry, boolean mayPlace) {
        Child c = new Child();
        Map<String, Object> row = row(o.account().id(), tag);
        Optional<BrokerOrder> existing = Optional.empty();
        if (row != null) {
            existing = read(tag);
            String status = (String) row.get("status");
            if (existing == null && ACTIVE.contains(status)) {
                // review 8 Nov 2026: an unreadable book is not "absent": the order may be working, never placed again;
                // UNKNOWN, with nothing booked from it, so lateFills watches it (a fill recorded before a lost session
                // was never booked)
                c.code = "ORDER_UNKNOWN";
                c.reason = "order " + tag + " (" + status + "): the broker's order book could not be read";
                unresolved(o.account().id(), tag, "UNKNOWN", c.reason, 0);
                return c;
            }
            existing = existing == null ? Optional.empty() : existing;
            if (existing.isEmpty() && !ACTIVE.contains(status)) {
                c.filled = ((Number) row.get("quantity_filled")).longValue();   // ended before the restart
                Object avg = row.get("average_fill_price");
                c.price = avg == null ? 0 : ((Number) avg).doubleValue();
                c.code = c.filled == quantity ? null : "NOT_FILLED";
                return c;
            }
            if (existing.isEmpty() && !mayPlace) {
                // review 8 Nov 2026: resumed, never placed again: left for lateFills to watch (and to give up on once it
                // has not reached the broker)
                unresolved(o.account().id(), tag, "UNKNOWN", "not in the broker's book when resumed", 0);
                c.code = "ORDER_UNKNOWN";
                c.reason = "order " + tag + " (" + status + ") not in the broker's book when resumed; not placed again";
                return c;
            }
        }
        double limit;
        if (existing.isEmpty()) {
            Quote q;
            try {
                q = broker.quote(o.instrumentKey());
            } catch (BrokerException e) {
                if (e.kind() == Kind.SESSION) {
                    throw e;
                }
                c.code = "NO_QUOTE";
                c.reason = e.getMessage();
                return c;
            }
            if (entry ? !q.hasAsk() : !q.hasBid()) {
                c.code = "NO_QUOTE";
                c.reason = "no " + (entry ? "ask" : "bid") + " at the broker";
                return c;
            }
            limit = entry ? q.ask() + rules.entryBufferTicks() * rules.tick() : exitPrice(q.bid());
            if (!entry && row == null && live()) {
                Long held = brokerHolds(o.account(), o.instrumentKey());
                if (held != null && held < quantity) {
                    // review 8 Nov 2026: the broker's book holds less than this sell (a hand sale in Kite): our guard would
                    // refuse it; nothing is written or sent (no refused row, no alert) until reconciliation books it
                    c.code = "BROKER_HOLDS_LESS";
                    c.reason = "the broker holds " + held + " of " + o.symbol() + ", this sell is " + quantity;
                    return c;
                }
            }
            if (row == null) {
                insertRow(o, tag, quantity, limit, Double.NaN, o.role());
            }
            try {
                broker.place(new OrderRequest(tag, o.token(), o.instrumentKey(), o.symbol(), exchange(o.instrumentKey()),
                        entry ? OrderSide.BUY : OrderSide.SELL, OrderType.LIMIT, quantity, limit, 0, tag));
                setStatus(o.account().id(), tag, "SUBMITTED", null);
            } catch (BrokerException e) {
                // review 8 Nov 2026: a row placed again (it was recorded, not in the book) may still have an earlier order
                // live: whatever the failure, its outcome is unknown, never REJECTED
                boolean unknown = e.kind() == Kind.UNKNOWN || row != null;
                if (unknown) {
                    unresolved(o.account().id(), tag, "UNKNOWN", e.getMessage(), 0);
                } else {
                    setStatus(o.account().id(), tag, "REJECTED", e.getMessage());
                }
                BrokerException session = sessionLost(e);
                if (session != null) {
                    throw session;                     // the account's session-lost handling (the intent waits, resumed by tag)
                }
                c.code = unknown ? "ORDER_UNKNOWN" : "BROKER_REJECTED";
                c.reason = e.getMessage();
                return c;
            }
        } else {
            limit = existing.get().limitPrice();
            log.warn("order {} resumed from the broker ({})", tag, existing.get().status());
        }
        BrokerOrder last = awaitEnd(o, tag, entry, limit);
        if (last == null) {
            c.code = "ORDER_UNKNOWN";
            c.reason = "order " + tag + " not found at the broker after placing";
            unresolved(o.account().id(), tag, "UNKNOWN", c.reason, 0);
            return c;
        }
        c.filled = last.filledQuantity();
        c.price = last.averagePrice();
        c.at = last.updatedAt();
        if (!last.status().isTerminal()) {
            // the cancel was not confirmed: more may still fill; what filled is kept and the account is reconciled
            unresolved(o.account().id(), tag, "RECONCILIATION_REQUIRED", "cancel not confirmed: " + last.rawStatus(),
                    c.filled);
            c.code = "ORDER_UNKNOWN";
            c.reason = "order " + tag + " still " + last.rawStatus() + " after its cancel";
        } else if (last.status() == OrderStatus.REJECTED) {
            c.code = "BROKER_REJECTED";
            c.reason = last.message();
        } else if (c.filled < quantity) {
            c.code = c.filled > 0 ? "PARTIAL" : entry ? "NOT_FILLED" : "EXIT_NOT_FILLED";
            c.reason = (entry ? "entry timeout" : "exit chase ran out") + ": " + c.filled + " of " + quantity + " filled";
        }
        return c;
    }

    /**
     * Works the placed order until it ends. An entry is cancelled after the entry timeout. An exit is re-priced at the
     * touch every chase interval, then crosses through the touch once, then is cancelled. Returns the last state (null
     * when the broker never shows the tag).
     */
    private BrokerOrder awaitEnd(OrderSpec o, String tag, boolean entry, double limit) {
        long start = System.nanoTime();
        double window = entry ? rules.entryTimeoutSec() : rules.exitChaseSec();
        int chases = 0;
        boolean cancelled = false;
        BrokerOrder last = null;
        long absentSince = -1;                                    // the first readable book without the tag
        long lastRead = start;                                    // the last readable book
        for (int polls = 0; ; polls++) {
            Optional<BrokerOrder> now = read(tag);                // one order-book read per iteration; null: unreadable
            long t = System.nanoTime();
            if (now != null) {
                lastRead = t;
            }
            if (now != null && now.isPresent()) {
                last = now.get();
                record(o.account().id(), last);
                if (last.status().isTerminal()) {
                    return last;
                }
            } else if (last == null) {
                // code review 3 Nov 2026: was 4 polls (~1 s at 250 ms), too short for Kite's book at the open. Still not
                // in the book: cancelled by the order id the place returned, so a late fill cannot become a position
                // nobody manages; then reported unknown (the account is reconciled). Review 8 Nov 2026: only readable
                // books count (a read that timed out took 5 s and said nothing, and a filled entry was cancelled and
                // left without a position or stop); a book unreadable for UNREADABLE_SEC ends the wait the same way
                if (now != null && absentSince < 0) {
                    absentSince = t;
                }
                if ((absentSince >= 0 && (t - absentSince) / 1e9 >= NOT_SEEN_SEC)
                        || (t - lastRead) / 1e9 >= UNREADABLE_SEC) {
                    cancel(tag);
                    return null;
                }
            }
            double elapsed = (System.nanoTime() - start) / 1e9;
            if (elapsed >= window && last != null) {
                if (entry || chases > rules.exitChaseMax()) {
                    if (!cancelled) {
                        cancel(tag);
                        cancelled = true;
                        window = elapsed + Math.max(2, rules.exitChaseSec());     // time for the cancel to land
                    } else {
                        return last;                                          // cancel not confirmed: report what is known
                    }
                } else {
                    chases++;
                    double price = limit;
                    try {
                        Quote q = broker.quote(o.instrumentKey());
                        if (q.hasBid()) {
                            price = chases > rules.exitChaseMax() ? q.bid() * (1 - rules.exitCrossPct() / 100)
                                    : exitPrice(q.bid());
                        }
                    } catch (BrokerException e) {
                        if (e.kind() == Kind.SESSION) {
                            throw e;
                        }
                    }
                    try {
                        broker.modify(tag, 0, Math.max(rules.tick(), price), 0);
                    } catch (BrokerException e) {
                        if (e.kind() == Kind.SESSION) {
                            throw e;
                        }
                        log.warn("exit {} re-price failed: {}", tag, e.getMessage());
                    }
                    window = elapsed + rules.exitChaseSec();
                }
            }
            sleep((long) rules.pollMs());
        }
    }

    private void cancel(String tag) {
        try {
            broker.cancel(tag);
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            log.info("cancel {}: {} (it may have just filled)", tag, e.getMessage());
        }
    }

    private double exitPrice(double bid) {
        return Math.max(rules.tick(), bid - Math.max(rules.exitBufferTicks() * rules.tick(), bid * rules.exitBufferPct() / 100));
    }

    // ------------------------------------------------------------------ protective stops

    @Override
    public Protection protect(BrokerAccount account, long positionId, Long leaseEpoch) {
        Map<String, Object> pos = jdbc.queryForMap("select p.entry_trade_intent_id, p.instrument_key, p.symbol, p.underlying, "
                + "p.instrument_token, p.quantity_open, p.average_entry, p.stop_pct, p.status, d.lot_size from exec.position p "
                + "join exec.trade_intent i on i.id = p.entry_trade_intent_id join exec.trade_batch b on b.id = i.trade_batch_id "
                + "join exec.signal_decision d on d.id = b.signal_decision_id where p.id = ?", positionId);
        long intent = ((Number) pos.get("entry_trade_intent_id")).longValue();
        String key = (String) pos.get("instrument_key");
        try {
            String unresolved = unresolvedSell(account, positionId, null);
            if (unresolved != null) {
                // review 8 Nov 2026: a sell of this contract of unknown outcome may already have sold what a stop would
                // cover; nothing is placed or re-sized over it until its end is known (lateFills)
                return Protection.later("sell " + unresolved + " of this contract has an unknown outcome");
            }
            // the position's stops as the broker has them now; a sale not yet booked is returned first (never re-armed
            // over quantity a stop has already sold)
            List<BrokerOrder> book;
            try {
                book = broker.orders();
            } catch (BrokerException e) {
                if (e.kind() == Kind.SESSION) {
                    throw e;
                }
                return Protection.later("order book unreadable: " + e.getMessage());     // review 8 Nov 2026
            }
            List<Map<String, Object>> active = new ArrayList<>();
            List<Part> sold = new ArrayList<>();
            Instant soldAt = null;
            for (Map<String, Object> s : stops(account.id(), intent)) {
                String tag = (String) s.get("client_tag");
                Optional<BrokerOrder> b = book.stream().filter(o -> tag.equals(o.clientTag())).findFirst();
                if (b.isEmpty()) {
                    if (mayStillBeWorking(s)) {
                        // review 8 Nov 2026: one LIVE read without it is not "not working": never re-armed over it
                        return Protection.later("stop " + tag + " (" + s.get("status") + ") not in this order book read");
                    }
                    // not in today's order book (never placed, a previous day's, or a lost simulator): not working
                    setStatus(account.id(), tag, "CANCELLED", "not in the broker's order book");
                    continue;
                }
                record(account.id(), b.get());
                long newly = b.get().filledQuantity() - ((Number) s.get("booked_quantity")).longValue();
                if (newly > 0) {
                    sold.add(new Part(newly, b.get().averagePrice()));
                    soldAt = b.get().updatedAt();
                    markBooked(account.id(), tag, b.get().filledQuantity());
                }
                if (!b.get().status().isTerminal()) {
                    s.put("quantity_filled", b.get().filledQuantity());
                    s.put("remaining", b.get().quantity() - b.get().filledQuantity());
                    s.put("broker_trigger", b.get().triggerPrice());
                    active.add(s);
                }
            }
            if (!sold.isEmpty()) {
                return new Protection(null, sold, soldAt == null ? Instant.now() : soldAt, Protection.Later.NO);
            }
            long open = ((Number) pos.get("quantity_open")).longValue();
            if (open <= 0) {
                active.forEach(s -> cancel((String) s.get("client_tag")));
                return Protection.ARMED;
            }
            double average = ((Number) pos.get("average_entry")).doubleValue();
            Number pct = (Number) pos.get("stop_pct");
            double stopPct = pct == null ? rules.defaultStopPct() : pct.doubleValue();
            double trigger = round(average * (1 - stopPct / 100));
            double limit = Math.max(rules.tick(), round(trigger * (1 - rules.stopLimitOffsetPct() / 100)));
            int lotSize = ((Number) pos.get("lot_size")).intValue();
            long perOrder = maxQuantityPerOrder.applyAsLong(key, (String) pos.get("underlying"));
            long maxPerOrder = perOrder <= 0 ? open : Math.max(lotSize, perOrder / lotSize * lotSize);
            long covered = active.stream().mapToLong(x -> ((Number) x.get("remaining")).longValue()).sum();
            boolean sameTrigger = active.stream().allMatch(x -> Math.abs(((Number) x.get("broker_trigger")).doubleValue()
                    - trigger) < rules.tick() / 2);
            if (!active.isEmpty() && covered == open && sameTrigger) {
                return Protection.ARMED;                                           // already right: nothing to send
            }
            if (live()) {
                // review 8 Nov 2026: what the broker's book holds and is not already being sold by others, plus what our
                // own stops cover (they are re-sized or replaced). Less than the position (a hand sale in Kite): our guard
                // would refuse the stop, so no row is written and nothing sent (no refused order every turn, no alert)
                long free = LiveGuard.held(book, key) - LiveGuard.workingSells(book, key, "") + covered;   // "": no tag
                if (free < open) {
                    return Protection.later(Protection.Later.BROKER_HOLDS_LESS, "the broker holds " + free + " free of "
                            + pos.get("symbol") + ", our position " + open);
                }
            }
            boolean modified = false;
            if (active.size() == 1 && open <= maxPerOrder) {                     // one stop: re-sized in place
                String tag = (String) active.getFirst().get("client_tag");
                long already = ((Number) active.getFirst().get("quantity_filled")).longValue();
                try {
                    broker.modify(tag, open + already, limit, trigger);
                    modified = true;
                } catch (BrokerException e) {
                    if (e.kind() == Kind.SESSION) {
                        throw e;
                    }
                    if (e.kind() != Kind.TERMINAL || e instanceof LiveGuard.Refusal) {
                        // review 8 Nov 2026: the stop still rests as it was (or has just ended: the next look books it),
                        // so a re-size that failed is tried again, never answered with an emergency sell
                        return Protection.later(e instanceof LiveGuard.Refusal ? Protection.Later.BACK_OFF
                                : Protection.Later.NEXT_TURN, "STOP_MODIFY_" + e.kind() + ": " + e.getMessage());
                    }
                    // the broker (or the adapter) refuses this change, e.g. a partly filled stop is not re-sized until the
                    // canary proves whether its quantity is the total or the rest (code review 8 Nov 2026): replaced below
                    log.warn("stop {} not modified ({}); cancelled and placed again", tag, e.getMessage());
                }
                if (modified) {
                    jdbc.update("update exec.order_execution set quantity_requested = ?, limit_price = ?, "
                            + "trigger_price = ?, updated_at = now() where broker_account_id = ? and client_tag = ?",
                            open + already, limit, trigger, account.id(), tag);
                    return Protection.ARMED;
                }
            }
            for (Map<String, Object> s : active) {                               // replaced: several, or none yet
                String tag = (String) s.get("client_tag");
                cancel(tag);
                BrokerOrder b = awaitEnded(tag);       // review 8 Nov 2026: as release() waits, not one read
                if (b == null) {
                    return Protection.later("STOP_CANCEL_UNCONFIRMED: " + tag);
                }
                record(account.id(), b);
                long newly = b.filledQuantity() - ((Number) s.get("booked_quantity")).longValue();
                if (newly > 0) {                                                  // it sold as it was cancelled
                    markBooked(account.id(), tag, b.filledQuantity());
                    return new Protection(null, List.of(new Part(newly, b.averagePrice())),
                            b.updatedAt() == null ? Instant.now() : b.updatedAt(), Protection.Later.NO);
                }
            }
            int attempt = jdbc.queryForObject("select coalesce(max(attempt_no), 0) + 1 from exec.order_execution "
                    + "where trade_intent_id = ? and role = 'STOP'", Integer.class, intent);
            int n = 0;
            String unseen = null;
            for (long left = open; left > 0; left -= Math.min(left, maxPerOrder)) {
                long quantity = Math.min(left, maxPerOrder);
                String tag = "F" + intent + "S" + attempt + (open > maxPerOrder ? "N" + (++n) : "");
                OrderSpec spec = new OrderSpec(account, intent, account.tradeMode().name(), "STOP", "SELL",
                        (String) pos.get("underlying"),
                        ((Number) pos.get("instrument_token")).longValue(), key, (String) pos.get("symbol"), lotSize, quantity,
                        limit, Instant.now(), attempt, leaseEpoch);
                insertRow(spec, tag, quantity, limit, trigger, "STOP");
                try {
                    broker.place(new OrderRequest(tag, spec.token(), key, spec.symbol(), exchange(key), OrderSide.SELL,
                            OrderType.STOP_LIMIT, quantity, limit, trigger, tag));
                } catch (BrokerException e) {
                    setStatus(account.id(), tag, e.kind() == Kind.UNKNOWN ? "UNKNOWN" : "REJECTED", e.getMessage());
                    BrokerException session = sessionLost(e);
                    if (session != null) {
                        throw session;
                    }
                    if (e.kind() == Kind.TERMINAL && !(e instanceof LiveGuard.Refusal)) {
                        return Protection.failed("STOP_" + e.kind() + ": " + e.getMessage());   // the broker refused it
                    }
                    // review 8 Nov 2026: not sent (a failed read, a rate limit), of unknown outcome (looked at again, never
                    // placed twice), or refused by our own guard (the broker holds less than our book: an emergency
                    // sell would be refused too): tried again later, no emergency exit
                    return Protection.later(Protection.Later.BACK_OFF, "STOP_" + e.kind() + ": " + e.getMessage());
                }
                setStatus(account.id(), tag, "SUBMITTED", null);
                Optional<BrokerOrder> placed = read(tag);
                if (placed != null && placed.isPresent()) {
                    record(account.id(), placed.get());
                    if (placed.get().status() == OrderStatus.REJECTED) {
                        return Protection.failed("STOP_REJECTED: " + placed.get().message());
                    }
                } else {
                    unseen = tag;           // review 8 Nov 2026: accepted, not yet seen working: confirmed next turn
                }
            }
            return unseen == null ? Protection.ARMED : Protection.later("stop " + unseen + " placed, not yet in the book");
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            return Protection.later("STOP_" + e.kind() + ": " + e.getMessage());
        }
    }

    /** Reads the order book until the order shows ended, up to {@link #STOP_RELEASE_WAIT_SEC}; null when it does not. */
    private BrokerOrder awaitEnded(String tag) {
        long deadline = System.nanoTime() + (long) (STOP_RELEASE_WAIT_SEC * 1e9);
        while (true) {
            Optional<BrokerOrder> b = read(tag);
            if (b != null && b.isPresent() && b.get().status().isTerminal()) {
                return b.get();
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            sleep((long) rules.pollMs());
        }
    }

    /**
     * Review 8 Nov 2026: whether an order row missing from one readable book may still be working at the broker. Kite's
     * day book never drops an order, so on a LIVE venue only a previous day's row, or one that never reached the broker
     * (no broker order id, its place never confirmed, quiet for {@link #NEVER_REACHED_SEC}) is taken as gone. A DRY_RUN
     * venue's local simulator loses its book when the worker restarts: absent there is gone (as before).
     */
    private boolean mayStillBeWorking(Map<String, Object> row) {
        if (!live() || Boolean.TRUE.equals(row.get("earlier_day"))) {
            return false;
        }
        return row.get("broker_order_id") != null || !List.of("CREATED", "UNKNOWN").contains(row.get("status"))
                || !Boolean.TRUE.equals(row.get("quiet"));
    }

    @Override
    public String unresolvedSell(BrokerAccount account, long positionId, Long exceptExitIntent) {
        List<String> tags = jdbc.queryForList("select e.client_tag from exec.order_execution e join exec.trade_intent i "
                + "on i.id = e.trade_intent_id join exec.position p on p.id = ? where e.broker_account_id = p.broker_account_id "
                + "and e.order_side = 'SELL' and e.role in ('EXIT', 'EMERGENCY_EXIT') "
                + "and e.status in ('UNKNOWN', 'RECONCILIATION_REQUIRED') and e.created_at >= " + TODAY_IST + " "
                + "and (e.trade_intent_id = p.entry_trade_intent_id or i.instrument_token = p.instrument_token) "
                + "and not (e.role = 'EXIT' and e.trade_intent_id = coalesce(?, -1)) order by e.id limit 1", String.class,
                positionId, exceptExitIntent);
        return tags.isEmpty() ? null : tags.getFirst();
    }

    private static boolean allEnded(List<Map<String, Object>> stops, List<BrokerOrder> book) {
        return stops.stream().allMatch(s -> book.stream().anyMatch(o -> s.get("client_tag").equals(o.clientTag())
                && o.status().isTerminal()));
    }

    /** The position's stop rows still working or with a fill not yet booked. */
    private List<Map<String, Object>> stops(long accountId, long intent) {
        return new ArrayList<>(jdbc.queryForList("select client_tag, status, quantity_filled, booked_quantity, broker_order_id, "
                + "created_at < " + TODAY_IST + " earlier_day, updated_at < now() - make_interval(secs => "
                + NEVER_REACHED_SEC + ") quiet from "
                + "exec.order_execution where broker_account_id = ? and trade_intent_id = ? and role = 'STOP' "
                + "and (status = any(?) or quantity_filled > booked_quantity) order by id", accountId, intent,
                ACTIVE.toArray(new String[0])));
    }

    @Override
    public Release release(BrokerAccount account, long positionId) {
        long intent = jdbc.queryForObject("select entry_trade_intent_id from exec.position where id = ?", Long.class,
                positionId);
        List<Map<String, Object>> stops = stops(account.id(), intent);
        if (stops.isEmpty()) {
            return Release.NONE;
        }
        // the order book is read, not looked up per tag: an unreadable book means "not confirmed", never "absent"
        List<BrokerOrder> book;
        try {
            book = broker.orders();
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            return new Release(List.of(), null, true, "order book unreadable: " + e.getMessage());
        }
        List<Map<String, Object>> working = new ArrayList<>();
        for (Map<String, Object> s : stops) {
            String tag = (String) s.get("client_tag");
            Optional<BrokerOrder> b = book.stream().filter(o -> tag.equals(o.clientTag())).findFirst();
            if (b.isEmpty()) {
                if (mayStillBeWorking(s)) {
                    // review 8 Nov 2026: one LIVE read without it is not "cancelled": no exit beside a stop that may rest
                    return new Release(List.of(), null, true, "stop " + tag + " (" + s.get("status")
                            + ") not in this order book read");
                }
                setStatus(account.id(), tag, "CANCELLED", "not in the broker's order book");
                continue;
            }
            if (!b.get().status().isTerminal()) {
                try {
                    broker.cancel(tag);
                } catch (BrokerException e) {
                    if (e.kind() == Kind.SESSION) {
                        throw e;
                    }
                    if (e.kind() != Kind.TERMINAL) {
                        return new Release(List.of(), null, true, "stop " + tag + " cancel not confirmed: "
                                + e.getMessage());
                    }                                                 // TERMINAL: it ended meanwhile (filled?) - look
                }
            }
            working.add(s);
        }
        List<Part> sold = new ArrayList<>();
        Instant at = null;
        long deadline = System.nanoTime() + (long) (STOP_RELEASE_WAIT_SEC * 1e9);
        while (!working.isEmpty()) {
            try {
                book = broker.orders();
            } catch (BrokerException e) {
                if (e.kind() == Kind.SESSION) {
                    throw e;
                }
                return new Release(List.of(), null, true, "order book unreadable after the cancel: " + e.getMessage());
            }
            if (allEnded(working, book) || System.nanoTime() >= deadline) {
                break;
            }
            sleep((long) rules.pollMs());
        }
        for (Map<String, Object> s : working) {                  // every stop must have ended before anything is booked
            String tag = (String) s.get("client_tag");
            Optional<BrokerOrder> now = book.stream().filter(o -> tag.equals(o.clientTag())).findFirst();
            if (now.isEmpty() || !now.get().status().isTerminal()) {
                return new Release(List.of(), null, true, "stop " + tag + " not yet cancelled at the broker");
            }
        }
        for (Map<String, Object> s : working) {
            String tag = (String) s.get("client_tag");
            Optional<BrokerOrder> now = book.stream().filter(o -> tag.equals(o.clientTag())).findFirst();
            record(account.id(), now.get());
            long newly = now.get().filledQuantity() - ((Number) s.get("booked_quantity")).longValue();
            if (newly > 0) {
                sold.add(new Part(newly, now.get().averagePrice()));
                at = now.get().updatedAt();
                markBooked(account.id(), tag, now.get().filledQuantity());
            }
        }
        return sold.isEmpty() ? Release.NONE : new Release(sold, at == null ? Instant.now() : at, false,
                "the broker stop sold " + sold.stream().mapToLong(Part::quantity).sum());
    }

    @Override
    public List<StopFill> stopFills(BrokerAccount account) {
        List<Map<String, Object>> stops = jdbc.queryForList("select e.client_tag, e.booked_quantity, p.id position_id "
                + "from exec.order_execution e join exec.position p on p.entry_trade_intent_id = e.trade_intent_id "
                + "and p.broker_account_id = e.broker_account_id where e.broker_account_id = ? and e.role = 'STOP' "
                + "and p.status = 'OPEN' and (e.status = any(?) or e.quantity_filled > e.booked_quantity)", account.id(),
                ACTIVE.toArray(new String[0]));
        if (stops.isEmpty()) {
            return List.of();
        }
        List<BrokerOrder> book = broker.orders();
        List<StopFill> fills = new ArrayList<>();
        for (Map<String, Object> s : stops) {
            String tag = (String) s.get("client_tag");
            Optional<BrokerOrder> now = book.stream().filter(b -> tag.equals(b.clientTag())).findFirst();
            if (now.isEmpty()) {
                continue;
            }
            record(account.id(), now.get());
            long booked = ((Number) s.get("booked_quantity")).longValue();
            long newly = now.get().filledQuantity() - booked;
            if (newly > 0) {
                fills.add(new StopFill(((Number) s.get("position_id")).longValue(), newly, now.get().averagePrice(),
                        now.get().updatedAt() == null ? Instant.now() : now.get().updatedAt()));
                markBooked(account.id(), tag, now.get().filledQuantity());
            }
        }
        return fills;
    }

    @Override
    public List<LateFill> lateFills(BrokerAccount account) {
        // review 8 Nov 2026: entries, exits and emergency exits whose end was not known. What such an order filled
        // beyond what was booked from it (booked_quantity, set when its end became unknown) is returned to be booked;
        // an ENTRY or EXIT of an intent still CREATED is resumed by its own tag instead (FollowerSimulator)
        List<Map<String, Object>> rows = jdbc.queryForList("select e.client_tag, e.role, e.trade_intent_id, e.status, "
                + "e.booked_quantity, e.average_fill_price, e.broker_order_id, e.updated_at < now() - make_interval(secs => "
                + NEVER_REACHED_SEC + ") quiet from exec.order_execution e join exec.trade_intent i on i.id = e.trade_intent_id "
                + "where e.broker_account_id = ? and e.role in ('ENTRY', 'EXIT', 'EMERGENCY_EXIT') "
                + "and e.status in ('UNKNOWN', 'RECONCILIATION_REQUIRED') and e.created_at >= " + TODAY_IST + " "
                + "and (e.role = 'EMERGENCY_EXIT' or i.state <> 'CREATED') order by e.id", account.id());
        if (rows.isEmpty()) {
            return List.of();
        }
        List<BrokerOrder> book;
        try {
            book = broker.orders();
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            log.warn("account {}: orders of unknown outcome not looked at this turn: {}", account.id(), e.getMessage());
            return List.of();
        }
        List<LateFill> fills = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String tag = (String) r.get("client_tag");
            Optional<BrokerOrder> b = book.stream().filter(o -> tag.equals(o.clientTag())).findFirst();
            if (b.isEmpty()) {
                if (!live()) {
                    // review 8 Nov 2026: a DRY_RUN simulator's book is lost when the worker restarts: absent is ended
                    // (else its exits, stops and flatten would wait on it all day)
                    setStatus(account.id(), tag, "CANCELLED", "not in the local simulator's book (restarted?)");
                } else if (r.get("broker_order_id") == null && Boolean.TRUE.equals(r.get("quiet"))) {
                    setStatus(account.id(), tag, "CANCELLED", "never in the broker's book " + NEVER_REACHED_SEC
                            + " s after its outcome was unknown: never reached the broker");
                }
                continue;
            }
            BrokerOrder o = b.get();
            if (!o.status().isTerminal()) {
                cancel(tag);                                   // its turn had ended: it should not be working
            }
            long booked = ((Number) r.get("booked_quantity")).longValue();
            long newly = o.filledQuantity() - booked;
            if (newly > 0) {
                Object avg = r.get("average_fill_price");      // the average of what was booked, when anything was
                double bookedValue = avg == null || booked == 0 ? 0 : booked * ((Number) avg).doubleValue();
                double price = (o.averagePrice() * o.filledQuantity() - bookedValue) / newly;
                fills.add(new LateFill(((Number) r.get("trade_intent_id")).longValue(), (String) r.get("role"), tag, newly,
                        price, o.updatedAt() == null ? Instant.now() : o.updatedAt()));
                log.warn("account {}: order {} filled {} more after its turn ended", account.id(), tag, newly);
            }
            if (newly > 0) {
                markBooked(account.id(), tag, o.filledQuantity());
            }
            if (o.status().isTerminal()) {
                record(account.id(), o);                       // ended: no longer watched
            } else if (newly > 0) {
                jdbc.update("update exec.order_execution set quantity_filled = ?, average_fill_price = ?, broker_order_id = ?, "
                        + "updated_at = now() where broker_account_id = ? and client_tag = ?", o.filledQuantity(),
                        o.averagePrice(), o.brokerOrderId(), account.id(), tag);   // booked: still watched till it ends
            }
        }
        return fills;
    }

    // ------------------------------------------------------------------ rows

    private Map<String, Object> row(long accountId, String tag) {
        List<Map<String, Object>> rows = jdbc.queryForList("select status, quantity_filled, average_fill_price from "
                + "exec.order_execution where broker_account_id = ? and client_tag = ?", accountId, tag);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertRow(OrderSpec o, String tag, long quantity, double limit, double trigger, String role) {
        jdbc.update("insert into exec.order_execution (tenant_id, trade_intent_id, broker_account_id, broker_type, role, "
                        + "client_tag, attempt_no, order_side, order_type, quantity_requested, quantity_filled, limit_price, "
                        + "trigger_price, status, lease_epoch) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'CREATED', ?) "
                        + "on conflict (broker_account_id, client_tag) do nothing", o.account().tenantId(), o.intentId(),
                o.account().id(), o.account().brokerType().name(), role, tag, o.attempt(), o.side(),
                role.equals("STOP") ? "STOP_LIMIT" : "LIMIT", quantity, limit, Double.isNaN(trigger) ? null : trigger,
                o.leaseEpoch());
    }

    private void setStatus(long accountId, String tag, String status, String error) {
        jdbc.update("update exec.order_execution set status = ?, raw_error = coalesce(?, raw_error), updated_at = now() "
                + "where broker_account_id = ? and client_tag = ?", status, error, accountId, tag);
    }

    /** The broker's state onto the row (status, fills, broker id). */
    private void record(long accountId, BrokerOrder b) {
        String status = switch (b.status()) {
            case PENDING -> "SUBMITTED";
            case OPEN, TRIGGER_PENDING -> "OPEN";
            case PARTIALLY_FILLED -> "PARTIALLY_FILLED";
            case FILLED -> "FILLED";
            case CANCELLED -> "CANCELLED";
            case REJECTED -> "REJECTED";
        };
        jdbc.update("update exec.order_execution set status = ?, raw_status = ?, broker_order_id = ?, quantity_filled = ?, "
                        + "average_fill_price = ?, raw_error = coalesce(?, raw_error), updated_at = now() "
                        + "where broker_account_id = ? and client_tag = ?", status, b.rawStatus(), b.brokerOrderId(),
                b.filledQuantity(), b.filledQuantity() > 0 ? b.averagePrice() : null,
                b.status() == OrderStatus.REJECTED ? b.message() : null, accountId, b.clientTag());
    }

    /**
     * Review 8 Nov 2026: an order whose end is not known, and what its caller books from it now ({@code booked}: what it
     * reports filled). lateFills books only what fills beyond that, so a fill recorded before a lost session and never
     * booked is not taken as booked.
     */
    private void unresolved(long accountId, String tag, String status, String error, long booked) {
        jdbc.update("update exec.order_execution set status = ?, raw_error = coalesce(?, raw_error), booked_quantity = ?, "
                + "updated_at = now() where broker_account_id = ? and client_tag = ?", status, error, booked, accountId, tag);
    }

    @Override
    public Long brokerHolds(BrokerAccount account, String instrumentKey) {
        if (!live() || instrumentKey == null) {
            return null;
        }
        try {
            return LiveGuard.held(broker.orders(), instrumentKey);
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            return null;                                   // not known: the guard reads the book again before any sell
        }
    }

    private void markBooked(long accountId, String tag, long quantity) {
        jdbc.update("update exec.order_execution set booked_quantity = ? where broker_account_id = ? and client_tag = ?",
                quantity, accountId, tag);
    }

    /**
     * The order with this tag in the broker's book now; null (not empty) when the book could not be read (review 8 Nov
     * 2026: a failed read was taken as "absent").
     */
    private Optional<BrokerOrder> read(String tag) {
        try {
            return broker.orderByTag(tag);
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            log.warn("order book unreadable looking up {}: {}", tag, e.getMessage());
            return null;
        }
    }

    private double round(double price) {
        return Math.round(price / rules.tick()) * rules.tick();
    }

    private static String exchange(String instrumentKey) {
        return instrumentKey != null && instrumentKey.startsWith("BSE_FO") ? "BSE_FO" : "NSE_FO";
    }

    /**
     * Review 8 Nov 2026: the lost session behind a failed place: the failure itself, or the cause of a place of unknown
     * outcome (the session went while the gateway looked it up by tag); null when the session is not lost.
     */
    private static BrokerException sessionLost(BrokerException e) {
        if (e.kind() == Kind.SESSION) {
            return e;
        }
        return e.kind() == Kind.UNKNOWN && e.getCause() instanceof BrokerException c && c.kind() == Kind.SESSION ? c : null;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
