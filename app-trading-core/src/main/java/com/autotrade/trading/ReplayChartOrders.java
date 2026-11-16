package com.autotrade.trading;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.core.time.MarketTime;
import com.autotrade.execution.ChartLines;
import com.autotrade.execution.ChartLines.Event;
import com.autotrade.execution.ChartLines.Kind;
import com.autotrade.execution.ChartLines.Lines;
import com.autotrade.oms.ManagedPosition;

/**
 * Chart orders in a paced replay (user, 15 Nov 2026: "in the replay i should be able to draw and run the replay"): the
 * same entry, stop and target lines as live (plan docs/plans/2026-11-14-chart-line-trading-plan.md, rules in
 * {@link ChartLines}), checked on every replayed index tick at the replay's own clock, and traded through the replay's
 * own paper account (its OMS, risk and fill model on the replayed option quotes) as strategy {@value #STRATEGY}. So a
 * replay's chart trade is priced like any strategy's replay trade and is stored with the session's positions.
 * <p>
 * Unlike live (an API in another process, the lines polled every second, the spot up to seconds old), the lines and the
 * ticks meet here under the session's lock: a dragged line counts from the next replayed tick, a line is checked against
 * the replay's latest index price, and a stop crossed while the buy is still filling sells what it bought.
 * The orders live in memory for the replay's run; the trades are stored, the lines are not.
 * <p>
 * Not thread-safe: {@link TradingSession} calls every method under its lock.
 */
final class ReplayChartOrders {

    /** The strategy id of every chart trade in a replay (one position per index at a time, as the OMS allows). */
    static final String STRATEGY = "chart";

    static final Set<String> WORKING = Set.of("ARMED", "TRIGGERED", "OPEN", "EXITING");

    /** What the session lends the orders: its clock, prices and paper account. */
    interface Desk {

        /** The replay's clock (its last event). */
        Instant now();

        /** The replay's latest index print, NaN before the first. */
        double spot(String underlying);

        /** Buys the ATM {@code side} for {@link #STRATEGY}; the position (null when refused) and the refusal. */
        Bought buy(String underlying, String side, int lots, Lines lines);

        /** Sells {@link #STRATEGY}'s position in {@code underlying} (cancels a buy still working). */
        void exit(String underlying, String reason);
    }

    record Bought(ManagedPosition position, String refused) {
    }

    /** One line of an order's history. */
    record Note(Instant at, String kind, Double index, String detail) {
    }

    static final class Order {
        final long id;
        final String underlying;
        final String side;
        Kind kind;
        double entry;
        double stop;
        double target;
        final double buffer;
        final double noChase;
        final int lots;
        final LocalTime validUntil;
        String status = "ARMED";
        String reason;
        int version = 1;
        ManagedPosition position;
        final List<Note> notes = new ArrayList<>();

        Order(long id, String underlying, String side, Kind kind, double entry, double stop, double target, double buffer,
              double noChase, int lots, LocalTime validUntil) {
            this.id = id;
            this.underlying = underlying;
            this.side = side;
            this.kind = kind;
            this.entry = entry;
            this.stop = stop;
            this.target = target;
            this.buffer = buffer;
            this.noChase = noChase;
            this.lots = lots;
            this.validUntil = validUntil;
        }

        Lines lines() {
            return new Lines(side, entry, stop, target, buffer, noChase);
        }
    }

    private final Desk desk;
    private final Set<String> tradable;
    private final List<Order> orders = new ArrayList<>();
    private long nextId = 1;

    ReplayChartOrders(Desk desk, Set<String> tradable) {
        this.desk = desk;
        this.tradable = Set.copyOf(tradable);
    }

    /** Arms an order; IllegalArgumentException (the reason) when its lines or fields are wrong. */
    Order arm(String underlying, String side, Double entry, Double stop, Double target, Integer lots, String validUntil) {
        return arm(underlying, side, entry, stop, target, lots, validUntil, null);
    }

    /** As {@link #arm}, refused when the lines arm as another kind than {@code expectKind} (the ticket's; D7). */
    Order arm(String underlying, String side, Double entry, Double stop, Double target, Integer lots, String validUntil,
              String expectKind) {
        if (underlying == null || !tradable.contains(underlying)) {
            throw new IllegalArgumentException("chart orders trade " + String.join(" and ", tradable.stream().sorted().toList())
                    + " in this replay");
        }
        if (entry == null || stop == null || target == null) {
            throw new IllegalArgumentException("entry, stop and target are required");
        }
        if (lots == null || lots < 1 || lots > 10) {
            throw new IllegalArgumentException("lots: 1 to 10");
        }
        LocalTime until = time(validUntil);
        LocalTime now = clock();
        if (!until.isAfter(now)) {
            throw new IllegalArgumentException("valid until " + until + " is not after the replay's time " + now.withNano(0));
        }
        if (orders.stream().anyMatch(o -> o.underlying.equals(underlying) && WORKING.contains(o.status))) {
            throw new IllegalArgumentException("one chart order at a time on " + underlying
                    + " in a replay: cancel or exit the working one first");
        }
        double spot = spot(underlying);
        Lines lines = new Lines(side, entry, stop, target, ChartLines.defaultBuffer(underlying),
                ChartLines.defaultNoChase(underlying));
        Kind kind = ChartLines.validate(lines, spot);
        ChartLines.requireKind(kind, expectKind, spot);
        Order o = new Order(nextId++, underlying, side, kind, entry, stop, target, lines.buffer(), lines.noChase(), lots, until);
        note(o, "ARMED", spot, kind.name().toLowerCase(java.util.Locale.ROOT) + ", " + lots + " lot(s), until " + until);
        orders.add(o);
        return o;
    }

    /**
     * Moves a working order's lines: an armed order's three (a moved entry is checked anew, ChartLines#validateArmedMove),
     * a bought one's stop and target (ChartLines#validateOpen). IllegalStateException when the order changed meanwhile.
     */
    Order move(long id, Integer version, Double entry, Double stop, Double target) {
        Order o = order(id);
        if (version != null && version != o.version) {
            throw new IllegalStateException("order #" + id + " changed meanwhile (version " + o.version + "): look again");
        }
        double spot = spot(o.underlying);
        double e = entry == null ? o.entry : entry, s = stop == null ? o.stop : stop, t = target == null ? o.target : target;
        Lines lines = new Lines(o.side, e, s, t, o.buffer, o.noChase);
        switch (o.status) {
            case "ARMED" -> o.kind = ChartLines.validateArmedMove(lines, o.kind, spot, e != o.entry);
            case "TRIGGERED", "OPEN" -> {
                if (e != o.entry) {
                    throw new IllegalArgumentException("the entry has fired: only the stop and target move now");
                }
                ChartLines.validateOpen(lines, spot);
            }
            default -> throw new IllegalStateException("chart order #" + id + " is " + o.status + ": its lines no longer move");
        }
        o.entry = e;
        o.stop = s;
        o.target = t;
        o.version++;
        note(o, "MOVED", spot, "entry " + e + ", stop " + s + ", target " + t);
        return o;
    }

    Order cancel(long id) {
        Order o = order(id);
        if (!"ARMED".equals(o.status)) {
            throw new IllegalStateException("chart order #" + id + " is " + o.status + ": only a waiting order is cancelled"
                    + ("TRIGGERED".equals(o.status) || "OPEN".equals(o.status) ? " (use Exit now)" : ""));
        }
        end(o, "CANCELLED", "cancelled by the trader", spot(o.underlying));
        return o;
    }

    /** Exit now: a waiting order is cancelled, a bought one sold at once. */
    Order exitNow(long id) {
        Order o = order(id);
        switch (o.status) {
            case "ARMED" -> end(o, "CANCELLED", "Exit now before the entry", spot(o.underlying));
            case "TRIGGERED", "OPEN" -> sell(o, "TRADER_EXIT", spot(o.underlying));
            case "EXITING" -> { }                                              // already selling
            default -> throw new IllegalStateException("chart order #" + id + " is " + o.status + ": nothing to exit");
        }
        return o;
    }

    /** Every working order on {@code underlying} against this replayed index print. */
    void onIndexTick(String underlying, double price) {
        if (!(price > 0) || !ChartLines.checked(clock())) {
            return;                                           // the pre-open auction's index (ChartLines#FIRST_CHECK)
        }
        for (Order o : orders) {
            if (!o.underlying.equals(underlying) || !WORKING.contains(o.status)) {
                continue;
            }
            switch (o.status) {
                case "ARMED" -> armed(o, price);
                case "TRIGGERED", "OPEN" -> held(o, price);
                default -> follow(o, price);                                  // EXITING: until the position is flat
            }
        }
    }

    /**
     * Brings every order up to its position's state between ticks (a square-off, kill switch, safety stop or the session's
     * end closes a position on an option quote or at the end, not on an index tick); {@code ended}: the replay is over,
     * so a waiting order expires.
     */
    void refresh(boolean ended) {
        for (Order o : orders) {
            if ("ARMED".equals(o.status)) {
                if (ended) {
                    end(o, "EXPIRED", "the replay ended", Double.NaN);
                }
            } else if (WORKING.contains(o.status)) {
                follow(o, Double.NaN);
            }
        }
    }

    private void armed(Order o, double price) {
        if (clock().isAfter(o.validUntil)) {
            end(o, "EXPIRED", "not triggered by " + o.validUntil, price);
            return;
        }
        Event event = ChartLines.onArmed(o.lines(), o.kind, price);
        switch (event) {
            case VOID_STOP_FIRST -> end(o, "VOID", "the index reached the stop line before the entry", price);
            case VOID_CHASE -> end(o, "VOID", "the first tick past the entry was more than " + fmt(o.noChase)
                    + " pts beyond it, or at the target (not chased)", price);
            case ENTER -> {
                Bought bought = desk.buy(o.underlying, o.side, o.lots, o.lines());
                if (bought.position() == null) {
                    end(o, "REFUSED", bought.refused(), price);
                    return;
                }
                o.position = bought.position();
                o.status = "TRIGGERED";
                o.reason = "buying " + bought.position().contract().symbol();
                o.version++;
                note(o, "BUY_SENT", price, o.lots + " lot(s) " + bought.position().contract().symbol());
            }
            default -> { }
        }
    }

    private void held(Order o, double price) {
        if (follow(o, price)) {
            return;
        }
        Event event = ChartLines.onOpen(o.lines(), price);
        if (event == Event.STOP || event == Event.TARGET) {
            sell(o, "CHART_" + event.name(), price);
        }
    }

    /** Moves the order on with its position; true when it ended (or is no longer to be checked against the lines). */
    private boolean follow(Order o, double price) {
        ManagedPosition p = o.position;
        if (p == null) {
            return false;
        }
        if (!p.isLive()) {
            if (p.boughtQuantity() == 0) {
                end(o, "CANCELLED", "the buy did not fill" + (p.exitReason() == null ? "" : " (" + p.exitReason() + ")"), price);
            } else {
                end(o, "CLOSED", p.exitReason(), price);
            }
            return true;
        }
        if ("TRIGGERED".equals(o.status) && p.quantity() > 0 && p.state() == ManagedPosition.State.OPEN) {
            o.status = "OPEN";
            o.reason = null;
            o.version++;
            note(o, "FILLED", price, p.quantity() + " @ " + fmt(p.averageCost()));
        }
        return "EXITING".equals(o.status);
    }

    private void sell(Order o, String reason, double price) {
        desk.exit(o.underlying, reason);
        o.status = "EXITING";
        o.reason = "selling: " + reason;
        o.version++;
        note(o, reason, price, null);
        follow(o, price);                     // a buy cancelled before any fill ends here at once
    }

    private void end(Order o, String status, String reason, double price) {
        o.status = status;
        o.reason = reason;
        o.version++;
        note(o, status, price, reason);
    }

    private void note(Order o, String kind, double index, String detail) {
        o.notes.add(new Note(desk.now(), kind, Double.isFinite(index) ? index : null, detail));
    }

    private Order order(long id) {
        return orders.stream().filter(o -> o.id == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no chart order #" + id + " in this replay"));
    }

    private double spot(String underlying) {
        double spot = desk.spot(underlying);
        if (!(spot > 0)) {
            throw new IllegalArgumentException("no " + underlying + " price in the replay yet: let it run to the open");
        }
        return spot;
    }

    private LocalTime clock() {
        return desk.now().atZone(MarketTime.IST).toLocalTime();
    }

    private static LocalTime time(String text) {
        if (text == null || text.isBlank()) {
            return LocalTime.of(15, 15);
        }
        try {
            return LocalTime.parse(text.length() == 5 ? text + ":00" : text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("valid until: HH:MM");
        }
    }

    List<Order> orders() {
        return List.copyOf(orders);
    }

    /** One order as the chart-order panel reads it (the live API's shape). */
    static Map<String, Object> view(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id);
        m.put("underlying", o.underlying);
        m.put("side", o.side);
        m.put("kind", o.kind.name());
        m.put("entry", o.entry);
        m.put("stop", o.stop);
        m.put("target", o.target);
        m.put("buffer", o.buffer);
        m.put("noChase", o.noChase);
        m.put("lots", o.lots);
        m.put("validUntil", o.validUntil.toString());
        m.put("status", o.status);
        m.put("reason", o.reason);
        m.put("version", o.version);
        ManagedPosition p = o.position;
        m.put("symbol", p == null ? null : p.contract().symbol());
        List<Map<String, Object>> events = new ArrayList<>();
        for (Note n : o.notes) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at", n.at().atZone(MarketTime.IST).toLocalTime().withNano(0).toString());
            e.put("kind", n.kind());
            e.put("actor", "replay");
            e.put("index", n.index());
            e.put("detail", n.detail() == null ? "" : n.detail());
            events.add(e);
        }
        m.put("events", events);
        if (p != null && p.boughtQuantity() > 0) {
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("open", p.quantity());
            position.put("entry", p.averageCost());
            position.put("status", p.state().name());
            position.put("exit_reason", p.exitReason());
            position.put("gross", finite(p.net() + p.costs()));
            position.put("charges", finite(p.costs()));
            m.put("position", position);
        }
        return m;
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
    }
}
