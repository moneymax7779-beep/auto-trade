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

    /** Works one order to its end. Throws a SESSION {@link com.autotrade.broker.BrokerException} when no call works. */
    Fill execute(OrderSpec order);

    /** The broker's available funds now; NaN = unknown (a DRY_RUN or LIVE entry is then refused). */
    double funds(BrokerAccount account);

    /**
     * Protecting a position: armed (failure null, nothing sold), or its stop had sold first (book {@code soldQuantity},
     * then protect again), or the stop could not be placed ({@code failure}: the caller exits at once).
     */
    record Protection(String failure, List<Part> sold, Instant at) {
        static final Protection ARMED = new Protection(null, List.of(), null);

        static Protection failed(String failure) {
            return new Protection(failure, List.of(), null);
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
}
