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
            Child c = work(o, tag, slices.get(i), buy);
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

    /** One child order to its end: resumed by tag when already recorded, else placed; then worked as entry or exit. */
    private Child work(OrderSpec o, String tag, long quantity, boolean entry) {
        Child c = new Child();
        Map<String, Object> row = row(o.account().id(), tag);
        Optional<BrokerOrder> existing = Optional.empty();
        if (row != null) {
            existing = lookUp(tag);
            String status = (String) row.get("status");
            if (existing.isEmpty() && !ACTIVE.contains(status)) {
                c.filled = ((Number) row.get("quantity_filled")).longValue();   // ended before the restart
                Object avg = row.get("average_fill_price");
                c.price = avg == null ? 0 : ((Number) avg).doubleValue();
                c.code = c.filled == quantity ? null : "NOT_FILLED";
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
            if (row == null) {
                insertRow(o, tag, quantity, limit, Double.NaN, o.role());
            }
            try {
                broker.place(new OrderRequest(tag, o.token(), o.instrumentKey(), o.symbol(), exchange(o.instrumentKey()),
                        entry ? OrderSide.BUY : OrderSide.SELL, OrderType.LIMIT, quantity, limit, 0, tag));
                setStatus(o.account().id(), tag, "SUBMITTED", null);
            } catch (BrokerException e) {
                setStatus(o.account().id(), tag, e.kind() == Kind.UNKNOWN ? "UNKNOWN" : "REJECTED", e.getMessage());
                if (e.kind() == Kind.SESSION) {
                    throw e;
                }
                c.code = e.kind() == Kind.UNKNOWN ? "ORDER_UNKNOWN" : "BROKER_REJECTED";
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
            setStatus(o.account().id(), tag, "UNKNOWN", c.reason);
            return c;
        }
        c.filled = last.filledQuantity();
        c.price = last.averagePrice();
        c.at = last.updatedAt();
        if (!last.status().isTerminal()) {
            // the cancel was not confirmed: more may still fill; what filled is kept and the account is reconciled
            setStatus(o.account().id(), tag, "RECONCILIATION_REQUIRED", "cancel not confirmed: " + last.rawStatus());
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
        for (int polls = 0; ; polls++) {
            Optional<BrokerOrder> now = lookUp(tag);              // one order-book read per iteration
            if (now.isPresent()) {
                last = now.get();
                record(o.account().id(), last);
                if (last.status().isTerminal()) {
                    return last;
                }
            } else if (polls > 3 && last == null) {
                return null;
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
            // the position's stops as the broker has them now; a sale not yet booked is returned first (never re-armed
            // over quantity a stop has already sold)
            List<BrokerOrder> book = broker.orders();
            List<Map<String, Object>> active = new ArrayList<>();
            List<Part> sold = new ArrayList<>();
            Instant soldAt = null;
            for (Map<String, Object> s : stops(account.id(), intent)) {
                String tag = (String) s.get("client_tag");
                Optional<BrokerOrder> b = book.stream().filter(o -> tag.equals(o.clientTag())).findFirst();
                if (b.isEmpty()) {
                    if (List.of("CREATED", "UNKNOWN").contains(s.get("status"))) {
                        setStatus(account.id(), tag, "CANCELLED", "never reached the broker");
                    }
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
                    active.add(s);
                }
            }
            if (!sold.isEmpty()) {
                return new Protection(null, sold, soldAt == null ? Instant.now() : soldAt);
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
            if (active.size() == 1 && open <= maxPerOrder) {                     // one stop: re-sized in place
                String tag = (String) active.getFirst().get("client_tag");
                long already = ((Number) active.getFirst().get("quantity_filled")).longValue();
                try {
                    broker.modify(tag, open + already, limit, trigger);
                } catch (BrokerException e) {
                    if (e.kind() == Kind.SESSION) {
                        throw e;
                    }
                    return Protection.failed("STOP_MODIFY_" + e.kind() + ": " + e.getMessage());
                }
                jdbc.update("update exec.order_execution set quantity_requested = ?, limit_price = ?, trigger_price = ?, "
                        + "updated_at = now() where broker_account_id = ? and client_tag = ?", open + already, limit, trigger,
                        account.id(), tag);
                return Protection.ARMED;
            }
            for (Map<String, Object> s : active) {                               // replaced: several, or none yet
                String tag = (String) s.get("client_tag");
                cancel(tag);
                Optional<BrokerOrder> b = lookUp(tag);
                if (b.isEmpty() || !b.get().status().isTerminal()) {
                    return Protection.failed("STOP_CANCEL_UNCONFIRMED: " + tag);
                }
                record(account.id(), b.get());
                long newly = b.get().filledQuantity() - ((Number) s.get("booked_quantity")).longValue();
                if (newly > 0) {                                                  // it sold as it was cancelled
                    markBooked(account.id(), tag, b.get().filledQuantity());
                    return new Protection(null, List.of(new Part(newly, b.get().averagePrice())),
                            b.get().updatedAt() == null ? Instant.now() : b.get().updatedAt());
                }
            }
            int attempt = jdbc.queryForObject("select coalesce(max(attempt_no), 0) + 1 from exec.order_execution "
                    + "where trade_intent_id = ? and role = 'STOP'", Integer.class, intent);
            int n = 0;
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
                    throw e;
                }
                setStatus(account.id(), tag, "SUBMITTED", null);
                Optional<BrokerOrder> placed = lookUp(tag);
                if (placed.isPresent()) {
                    record(account.id(), placed.get());
                    if (placed.get().status() == OrderStatus.REJECTED) {
                        return Protection.failed("STOP_REJECTED: " + placed.get().message());
                    }
                }
            }
            return Protection.ARMED;
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            return Protection.failed("STOP_" + e.kind() + ": " + e.getMessage());
        }
    }

    /** The position's stop rows still working or with a fill not yet booked. */
    private List<Map<String, Object>> stops(long accountId, long intent) {
        return new ArrayList<>(jdbc.queryForList("select client_tag, status, quantity_filled, booked_quantity from "
                + "exec.order_execution where broker_account_id = ? and trade_intent_id = ? and role = 'STOP' "
                + "and (status = any(?) or quantity_filled > booked_quantity) order by id", accountId, intent,
                ACTIVE.toArray(new String[0])));
    }

    @Override
    public Release release(BrokerAccount account, long positionId) {
        long intent = jdbc.queryForObject("select entry_trade_intent_id from exec.position where id = ?", Long.class,
                positionId);
        List<Map<String, Object>> stops = stops(account.id(), intent);
        List<Part> sold = new ArrayList<>();
        Instant at = null;
        for (Map<String, Object> s : stops) {
            String tag = (String) s.get("client_tag");
            Optional<BrokerOrder> before = lookUp(tag);
            if (before.isEmpty()) {
                if (List.of("CREATED", "UNKNOWN").contains(s.get("status"))) {
                    setStatus(account.id(), tag, "CANCELLED", "never reached the broker");
                    continue;
                }
                return new Release(List.of(), null, true, "stop " + tag + " is not in the broker's order book");
            }
            if (!before.get().status().isTerminal()) {
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
            Optional<BrokerOrder> now = lookUp(tag);
            if (now.isEmpty() || !now.get().status().isTerminal()) {
                return new Release(List.of(), null, true, "stop " + tag + " not yet cancelled at the broker");
            }
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

    private void markBooked(long accountId, String tag, long quantity) {
        jdbc.update("update exec.order_execution set booked_quantity = ? where broker_account_id = ? and client_tag = ?",
                quantity, accountId, tag);
    }

    private Optional<BrokerOrder> lookUp(String tag) {
        try {
            return broker.orderByTag(tag);
        } catch (BrokerException e) {
            if (e.kind() == Kind.SESSION) {
                throw e;
            }
            return Optional.empty();
        }
    }

    private double round(double price) {
        return Math.round(price / rules.tick()) * rules.tick();
    }

    private static String exchange(String instrumentKey) {
        return instrumentKey != null && instrumentKey.startsWith("BSE_FO") ? "BSE_FO" : "NSE_FO";
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
