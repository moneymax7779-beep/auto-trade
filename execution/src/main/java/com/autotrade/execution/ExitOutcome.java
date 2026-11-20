package com.autotrade.execution;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one exit of a position did, judged on the account's own lane against the position as read there just before the
 * exit (admin exit desk review, 20 Nov 2026): SOLD, PARTLY, REFUSED (nothing was sent, or what was sent ended with nothing
 * sold), ALREADY_CLOSED (closed before the exit got its turn, e.g. by its strategy) or UNKNOWN (an order may be working or
 * may have filled: never reported as refused).
 *
 * @param before the open quantity on the lane before the exit (null: not read)
 * @param after  the open quantity after it (null: not read)
 */
public record ExitOutcome(String outcome, Long before, Long after, List<String> lines, String detail) {

    public static final String SOLD = "SOLD", PARTLY = "PARTLY", REFUSED = "REFUSED", ALREADY_CLOSED = "ALREADY_CLOSED",
            UNKNOWN = "UNKNOWN";

    /** What an UNKNOWN outcome always says (the reviewer's wording: a hand sale on top would double the sell). */
    public static final String UNKNOWN_ADVICE = "do not sell by hand in the broker app; check the broker's order book first";

    /** Our order states that have ended: anything else may still be working at the broker. */
    static final Set<String> ENDED = Set.of("FILLED", "CANCELLED", "REJECTED");

    /** Codes of a sell line that ended with nothing placed or a plain refusal (anything else with nothing sold: unknown). */
    static final List<String> CLEAN_REFUSALS = List.of("BROKER_REJECTED", "NO_QUOTE", "BROKER_HOLDS_LESS", "NOT_FILLED",
            "EXIT_NOT_FILLED");

    public static ExitOutcome refused(List<String> lines, String detail) {
        return new ExitOutcome(REFUSED, null, null, lines, detail);
    }

    public static ExitOutcome unknown(List<String> lines, String why) {
        return new ExitOutcome(UNKNOWN, null, null, lines, why + ": " + UNKNOWN_ADVICE);
    }

    /**
     * The outcome from the position before and after the exit, the exit's lines, the states of the exit orders it wrote
     * ({@code newOrders}) and the failure it threw (null: none).
     */
    public static ExitOutcome classify(long before, Long after, boolean closedAfter, List<String> lines,
                                       List<String> newOrders, Throwable failure) {
        return classify(before, after, closedAfter, lines, newOrders, failure, false);
    }

    /**
     * As above; {@code broker}: the exit went to a real broker venue. Second review 20 Nov 2026: there the exit first takes
     * the position's broker stop off (a cancel sent to the broker), so a failure thrown on the way, or a line "NOT
     * flattened, stop ... cancel not confirmed / not yet cancelled / order book unreadable", leaves the stop's state at the
     * broker not known even with no exit order written: UNKNOWN, never REFUSED (a hand sale beside a stop that still rests
     * could sell twice). A simulated venue sends nothing anywhere: a failure with no exit order there is REFUSED.
     */
    public static ExitOutcome classify(long before, Long after, boolean closedAfter, List<String> lines,
                                       List<String> newOrders, Throwable failure, boolean broker) {
        boolean unknown = newOrders.stream().anyMatch(s -> !ENDED.contains(s))
                || lines.stream().map(l -> l.toLowerCase(Locale.ROOT)).anyMatch(l -> l.contains("unknown outcome")
                        || l.contains("order_unknown") || l.contains("reconciliation")
                        || broker && l.contains("not flattened"))
                || lines.stream().anyMatch(ExitOutcome::uncleanShortSale)
                || failure != null && (!newOrders.isEmpty() || broker);
        if (after == null) {
            return new ExitOutcome(UNKNOWN, before, null, lines, "the exit ran but the position could not be read after "
                    + "it: " + UNKNOWN_ADVICE);
        }
        long sold = before - after;
        if (unknown) {
            return new ExitOutcome(UNKNOWN, before, after, lines, (sold > 0 ? sold + " of " + before + " sold so far; "
                    : "") + (failure != null && newOrders.isEmpty() ? "the exit failed at the broker; its stop may have been "
                    + "taken off" : "an order's outcome is not known") + (failure == null ? "" : " (" + failure.getMessage()
                    + ")") + ": " + UNKNOWN_ADVICE);
        }
        if (closedAfter || after <= 0) {
            return new ExitOutcome(SOLD, before, after, lines, null);
        }
        if (sold > 0) {
            return new ExitOutcome(PARTLY, before, after, lines, sold + " of " + before + " sold, " + after + " open");
        }
        if (failure != null) {
            return new ExitOutcome(REFUSED, before, after, lines, "the exit failed before any order was sent: "
                    + failure.getMessage());
        }
        return new ExitOutcome(REFUSED, before, after, lines, lines.isEmpty() ? "nothing was sold" : String.join("; ", lines));
    }

    /** "SYMBOL: 0 of 130 sold (CODE reason); ..." with a code that is not a plain refusal: the order may still work. */
    public static boolean uncleanShortSale(String line) {
        int at = line.indexOf(" sold (");
        if (at < 0) {
            return false;
        }
        String head = line.substring(0, at);
        boolean nothing = head.matches(".*: 0 of \\d+$");
        return nothing && CLEAN_REFUSALS.stream().noneMatch(code -> line.startsWith(code + " ", at + 7));
    }
}
