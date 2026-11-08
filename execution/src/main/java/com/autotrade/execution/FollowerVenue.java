package com.autotrade.execution;

import java.time.Instant;
import java.util.List;

import com.autotrade.account.BrokerAccount;

/**
 * Where a follower's orders go (live plan L2). {@link FollowerSimulator} sizes, checks and books every account the same
 * way; the venue works the orders and records each one in exec.order_execution:
 * {@link SimulatedVenue} fills in place at the given price (PAPER, and a DRY_RUN account with no broker connection);
 * {@link BrokerVenue} sends them to a broker connection (a ZERODHA DRY_RUN account against the local Kite simulator;
 * LIVE only after the user's explicit go-ahead) and keeps a protective stop resting at the broker for every position.
 */
public interface FollowerVenue {

    /**
     * One order of an intent. Role ENTRY (an entry or an add) or EXIT; {@code price} is the simulated fill price (the
     * signal's price moved by the risk file's adverse bps); a broker venue prices from the broker's own quote instead.
     */
    record OrderSpec(BrokerAccount account, long intentId, String mode, String role, String side, String underlying,
                     long token, String instrumentKey, String symbol, int lotSize, long quantity, double price,
                     Instant at, int attempt, Long leaseEpoch) {

        /** The broker tag (the idempotency key), deterministic, <= 20 alphanumerics: F + intent + role + attempt. */
        public String tag() {
            return "F" + intentId + switch (role) {
                case "STOP" -> 'S';
                case "EMERGENCY_EXIT" -> 'X';
                default -> role.charAt(0);
            } + attempt;
        }
    }

    /** One executed child order: charges are per executed order (the broker's brokerage is per order). */
    record Part(long quantity, double price) {
    }

    /**
     * What an order filled ({@code parts}: one per child order that filled); quantity 0 = nothing, and {@code code} /
     * {@code reason} say why.
     */
    record Fill(long quantity, double price, Instant at, String code, String reason, List<Part> parts) {

        static Fill none(String code, String reason, Instant at) {
            return new Fill(0, Double.NaN, at, code, reason, List.of());
        }

        static Fill of(List<Part> parts, Instant at, String code, String reason) {
            long quantity = parts.stream().mapToLong(Part::quantity).sum();
            double value = parts.stream().mapToDouble(x -> x.quantity() * x.price()).sum();
            return quantity == 0 ? none(code, reason, at) : new Fill(quantity, value / quantity, at, code, reason,
                    List.copyOf(parts));
        }

        /** The broker may hold an order whose outcome is not known: the account needs reconciling. */
        boolean unknown() {
            return "ORDER_UNKNOWN".equals(code);
        }
    }

    /** A protective stop (one order) that sold at the broker while its position was held. */
    record StopFill(long positionId, long quantity, double price, Instant at) {
    }

    /** Taking a position's stop off before its exit: what its orders already sold, or try again later (not confirmed). */
    record Release(List<Part> sold, Instant at, boolean retryLater, String reason) {
        static final Release NONE = new Release(List.of(), null, false, null);

        long soldQuantity() {
            return sold.stream().mapToLong(Part::quantity).sum();
        }
    }

    /** True when the orders go to a broker: session errors, protective stops and the broker's funds apply. */
    boolean broker();

    /**
     * True when the orders go to the real broker of a LIVE account (behind its {@link LiveGuard}): such a venue runs
     * LIVE intents only, and every other venue never runs one.
     */
    default boolean live() {
        return false;
    }

    /** Works one order to its end. Throws a SESSION {@link com.autotrade.broker.BrokerException} when no call works. */
    Fill execute(OrderSpec order);

    /** The broker's available funds now; NaN = unknown (a DRY_RUN or LIVE entry is then refused). */
    double funds(BrokerAccount account);

    /**
     * Protecting a position: armed (failure null, nothing sold), or its stop had sold first (book {@code soldQuantity},
     * then protect again), or the stop could not be placed ({@code failure}: the caller exits at once).
     */
    record Protection(String failure, List<Part> sold, Instant at, Later later) {
        static final Protection ARMED = new Protection(null, List.of(), null, Later.NO);

        /**
         * Review 8 Nov 2026: why a stop is not confirmed armed when nothing says the broker refused it; never answered
         * with an emergency sell. NEXT_TURN: looked at again next turn (a failed read, a stop not yet in the book, a sell
         * of unknown outcome). BACK_OFF: a place that failed (not sent, unknown, refused by our guard): tried again after
         * a pause, so no order row is written every turn. BROKER_HOLDS_LESS: the broker's order book holds less of the
         * contract than our book (a hand sale): no stop row is written; reconciliation books or kills it, so it does not
         * count towards STOP_NOT_ARMED.
         */
        enum Later { NO, NEXT_TURN, BACK_OFF, BROKER_HOLDS_LESS }

        static Protection failed(String failure) {
            return new Protection(failure, List.of(), null, Later.NO);
        }

        static Protection later(String why) {
            return new Protection(why, List.of(), null, Later.NEXT_TURN);
        }

        static Protection later(Later kind, String why) {
            return new Protection(why, List.of(), null, kind);
        }

        boolean isLater() {
            return later != Later.NO;
        }

        long soldQuantity() {
            return sold.stream().mapToLong(Part::quantity).sum();
        }
    }

    /** Arms the protective stop of an open position, or re-sizes it to the open quantity after an add or a sale. */
    default Protection protect(BrokerAccount account, long positionId, Long leaseEpoch) {
        return Protection.ARMED;
    }

    /** Takes the position's protective stop off before its exit. */
    default Release release(BrokerAccount account, long positionId) {
        return Release.NONE;
    }

    /** Protective stops that sold at the broker since the last call. */
    default List<StopFill> stopFills(BrokerAccount account) {
        return List.of();
    }

    /**
     * Review 8 Nov 2026: what an entry, exit or emergency exit whose end was not known (its place timed out, it never
     * showed in the book, its cancel was not confirmed) filled at the broker beyond what was booked from it. Each turn;
     * such an order still working is cancelled (its turn had ended).
     */
    record LateFill(long intentId, String role, String tag, long quantity, double price, Instant at) {
    }

    default List<LateFill> lateFills(BrokerAccount account) {
        return List.of();
    }

    /**
     * Review 8 Nov 2026: resumes an intent's orders already recorded (a session lost or a worker stopped mid-order) by
     * their tags, never placing anything new: what they filled.
     */
    default Fill resume(OrderSpec order) {
        return Fill.none("NOT_RESUMABLE", "this venue has no orders to resume", order.at());
    }

    /**
     * Review 8 Nov 2026: the tag of a sell (exit or emergency exit) of the position's contract whose outcome is not known
     * yet (it may have sold), other than {@code exceptExitIntent}'s own exit (resumed by its tag); null when none. No
     * other sell or stop is sent over it.
     */
    default String unresolvedSell(BrokerAccount account, long positionId, Long exceptExitIntent) {
        return null;
    }

    /**
     * Review 8 Nov 2026: what the broker's own order book shows held of a contract (filled buys less filled sells, ours
     * and by hand), on a LIVE venue; null when not known (another venue, or the book could not be read).
     */
    default Long brokerHolds(BrokerAccount account, String instrumentKey) {
        return null;
    }
}
