package com.autotrade.execution;

import java.time.LocalTime;
import java.util.Locale;

/**
 * What a chart order's three lines mean (plan docs/plans/2026-11-14-chart-line-trading-plan.md, section 2), as pure
 * rules on index prices, so the live engine and its tests read the same thing.
 * <p>
 * Every rule is written once for a call: a put is the same with the prices' signs turned ({@link Lines#dir()}), so for
 * either side the stop is "below" the entry and the target "above" it in the trade's own direction.
 * <ul>
 *   <li><b>Entry kind</b> is fixed when the order is armed, from where the index is then: below the entry line (a call)
 *       is a {@link Kind#BREAKOUT}, fired by the first tick at least the buffer beyond the line; above it is a
 *       {@link Kind#PULLBACK} (buy the dip to support), fired by the first tick that reaches the line.</li>
 *       With the index beyond the stop line too (both lines ahead of it: a call's stop above the index, a put's below)
 *       it is a {@link Kind#FAR_BREAKOUT}: a plain stop-entry, fired like a breakout, its stop counting only once bought
 *       (15 Nov 2026: a put drawn 76 points under the index with its stop 30 above the entry was refused).</li>
 *   <li><b>Void, never late</b>: the stop line reached before the entry voids the order (not a far breakout's, whose
 *       index starts beyond it); so does a first tick past the entry by more than the no-chase limit (a gap, a spike,
 *       or the engine down while the line was crossed).</li>
 *   <li><b>Stop</b>: the first tick at least the buffer beyond the stop line (a one-tick poke does not stop out).</li>
 *   <li><b>Target</b>: the first tick that reaches the target line.</li>
 * </ul>
 */
public final class ChartLines {

    private ChartLines() {
    }

    /**
     * The first index tick the lines are checked on (15 Nov 2026): before 09:15 the index is the pre-open call auction's
     * indicative (09:00–09:08, it moves) and then the frozen auction price (09:08–09:15), while options do not trade, so
     * a line "crossed" then would void an order on a price nobody could trade at or send a buy that fills only at the
     * open, past the line. The first normal tick (a gap past the entry) is the no-chase rule's case.
     */
    public static final LocalTime FIRST_CHECK = LocalTime.of(9, 15);

    /** True when an index tick at {@code time} (IST) counts against the lines. */
    public static boolean checked(LocalTime time) {
        return !time.isBefore(FIRST_CHECK);
    }

    /**
     * BREAKOUT: the index between the stop and the entry, to move through the entry; PULLBACK: the index past the entry,
     * to come back to it; FAR_BREAKOUT: the index beyond the stop, to move through the stop line and then the entry.
     */
    public enum Kind { BREAKOUT, PULLBACK, FAR_BREAKOUT }

    public enum Event { NONE, ENTER, VOID_STOP_FIRST, VOID_CHASE, STOP, TARGET }

    /** One order's lines in index points; {@code side} CE or PE. */
    public record Lines(String side, double entry, double stop, double target, double buffer, double noChase) {

        /** +1 for a call (it gains as the index rises), -1 for a put. */
        public double dir() {
            return "PE".equals(side) ? -1 : 1;
        }
    }

    /** The default buffer beyond a line, in index points: a few ticks of the index's typical one-second noise. */
    public static double defaultBuffer(String underlying) {
        return "SENSEX".equals(underlying) ? 6 : 2;
    }

    /** How far past the entry line the first tick may be and still be bought (a gap or spike beyond is not chased). */
    public static double defaultNoChase(String underlying) {
        return "SENSEX".equals(underlying) ? 25 : 8;
    }

    /**
     * Checks an order's lines against the index now and returns its entry kind; IllegalArgumentException (the reason,
     * for the trader) when it cannot be armed: lines in the wrong order, a stop or target too close, or the entry line so
     * near the index that it would fire at once.
     */
    /**
     * Review 16 Nov 2026 (D7): the trader confirmed the kind the ticket showed, worked out from a quote up to seconds
     * old; the server works it out again from the index now, and an index that moved through the entry meanwhile turns
     * a breakout into a pullback (or back): a different trade from the one confirmed. Refused when {@code expected}
     * (the ticket's; null or blank: not given, as before) is not {@code kind}.
     */
    public static void requireKind(Kind kind, String expected, double spot) {
        if (expected == null || expected.isBlank()) {
            return;
        }
        Kind shown;
        try {
            shown = Kind.valueOf(expected.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown entry kind " + expected);
        }
        if (shown != kind) {
            throw new IllegalArgumentException("the index is at " + price(spot) + " now: these lines arm as a "
                    + name(kind) + ", not the " + name(shown) + " the ticket showed: nothing armed; check the ticket again");
        }
    }

    private static String name(Kind k) {
        return k.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    public static Kind validate(Lines l, double spot) {
        shape(l, spot);
        double d = l.dir(), e = d * l.entry(), x = d * spot;
        if (Math.abs(x - e) < 2 * l.buffer()) {
            throw new IllegalArgumentException("the entry line is " + pts(Math.abs(x - e)) + " from the index (" + price(spot)
                    + "): it would fire at once; move it at least " + pts(2 * l.buffer()) + " away, or use Buy now");
        }
        return x < e ? breakout(l, spot) : Kind.PULLBACK;
    }

    /** A breakout's kind from where the index is: beyond the stop line (by less than the buffer counts) a far one. */
    private static Kind breakout(Lines l, double spot) {
        return l.dir() * spot <= l.dir() * l.stop() + l.buffer() ? Kind.FAR_BREAKOUT : Kind.BREAKOUT;
    }

    /**
     * A move of a waiting order's lines (code review 15 Nov 2026). A moved entry is checked as a new order ({@link
     * #validate}: its kind may change). A stop or target moved with the entry where it was keeps the order's kind and is
     * not refused for the index being near the entry: that is when a trader adjusts the stop, and the entry has not
     * fired, so it cannot "fire at once" from this move.
     */
    public static Kind validateArmedMove(Lines l, Kind kind, double spot, boolean entryMoved) {
        if (entryMoved) {
            return validate(l, spot);
        }
        shape(l, spot);
        double d = l.dir(), x = d * spot;
        if (kind == Kind.PULLBACK) {
            if (x <= d * l.stop() + l.buffer()) {
                throw new IllegalArgumentException("the index (" + price(spot) + ") is already at or beyond the stop line");
            }
            return kind;
        }
        // a breakout's stop moved past the index makes it a far one, and back (where the index is now)
        return x < d * l.entry() ? breakout(l, spot) : kind;
    }

    /** The checks every armed order passes: lines in order, distances. */
    private static void shape(Lines l, double spot) {
        if (!"CE".equals(l.side()) && !"PE".equals(l.side())) {
            throw new IllegalArgumentException("side: CE or PE");
        }
        for (double v : new double[] {l.entry(), l.stop(), l.target(), spot}) {
            if (!(v > 0) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("entry, stop and target must be index prices above 0");
            }
        }
        if (!(l.buffer() >= 0) || !(l.noChase() > 0)) {
            throw new IllegalArgumentException("the buffer must be 0 or more and the no-chase limit above 0");
        }
        double d = l.dir(), e = d * l.entry(), s = d * l.stop(), t = d * l.target();
        boolean call = "CE".equals(l.side());
        if (!(s < e && e < t)) {
            throw new IllegalArgumentException(call ? "a CE's stop must be below its entry and its target above it"
                    : "a PE's stop must be above its entry and its target below it");
        }
        if (e - s < 2 * l.buffer()) {
            throw new IllegalArgumentException("the stop is " + pts(e - s) + " from the entry: at least " + pts(2 * l.buffer())
                    + " (twice the buffer), or every tick of noise stops it");
        }
        if (t - e <= l.buffer()) {
            // second review: one buffer exactly was accepted, and every entry tick (at the buffer past the line) was
            // already at the target, so the order could only be voided
            throw new IllegalArgumentException("the target is " + pts(t - e) + " from the entry: more than "
                    + pts(l.buffer()));
        }
    }

    /**
     * A move of an open position's stop or target: both must stay on their side of the index now (a stop dragged past
     * the price would sell at once: that is Exit now), the stop below the target. A stop may move past the entry (a
     * trail that locks in profit). IllegalArgumentException with the reason.
     */
    public static void validateOpen(Lines l, double spot) {
        double d = l.dir(), s = d * l.stop(), t = d * l.target(), x = d * spot;
        if (!(l.stop() > 0) || !(l.target() > 0) || Double.isInfinite(l.stop()) || Double.isInfinite(l.target())) {
            throw new IllegalArgumentException("stop and target must be index prices above 0");
        }
        if (x <= s + l.buffer()) {
            throw new IllegalArgumentException("that stop is at or beyond the index (" + price(spot)
                    + "): it would sell at once; use Exit now");
        }
        if (x >= t) {
            throw new IllegalArgumentException("that target is at or beyond the index (" + price(spot)
                    + "): it would sell at once; use Exit now");
        }
    }

    /** A waiting order on an index tick at {@code price}. */
    public static Event onArmed(Lines l, Kind kind, double price) {
        double d = l.dir(), e = d * l.entry(), s = d * l.stop(), t = d * l.target(), x = d * price;
        if (x <= s && kind != Kind.FAR_BREAKOUT) {
            return Event.VOID_STOP_FIRST;
        }
        if (kind == Kind.BREAKOUT || kind == Kind.FAR_BREAKOUT) {
            if (x >= e + l.buffer()) {
                // review 16 Nov 2026: a target inside the no-chase band (NIFTY: from 2 points) could be passed by the
                // very tick that enters: bought, then sold on the next tick at the target. Not chased either
                return x - e > l.noChase() || x >= t ? Event.VOID_CHASE : Event.ENTER;
            }
        } else if (x <= e) {
            return e - x > l.noChase() ? Event.VOID_CHASE : Event.ENTER;
        }
        return Event.NONE;
    }

    /** An open position on an index tick at {@code price}. */
    public static Event onOpen(Lines l, double price) {
        double d = l.dir(), s = d * l.stop(), t = d * l.target(), x = d * price;
        if (x <= s - l.buffer()) {
            return Event.STOP;
        }
        if (x >= t) {
            return Event.TARGET;
        }
        return Event.NONE;
    }

    /**
     * The premium stop that rests at the broker under our own index stop (plan 2.3), as a percentage below the price
     * paid: where an at-the-money option (delta about 0.5) should be when the index reaches the stop, from an entry
     * fired at the buffer past the line, less a further 15 %; never closer than 15 % nor wider than 60 %. It fires only if
     * our own stop did not act first (the server, the feed or the network down, or a move too fast to chase).
     */
    public static double safetyStopPct(Lines l, double ask) {
        if (!(ask > 0)) {
            return 60;
        }
        double move = l.dir() * (l.entry() - l.stop()) + 2 * l.buffer();
        double atStop = ask - 0.5 * move;
        if (!(atStop > 0)) {
            return 60;
        }
        double pct = 100 * (1 - 0.85 * atStop / ask);
        return Math.round(Math.max(15, Math.min(60, pct)) * 10) / 10.0;
    }

    private static String pts(double v) {
        return price(v) + " pts";
    }

    private static String price(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.endsWith(".00") ? s.substring(0, s.length() - 3) : s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }
}
