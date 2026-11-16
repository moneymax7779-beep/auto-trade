package com.autotrade.broker;

import java.util.List;

/**
 * A live broker session for one account: the order calls of {@link BrokerGateway} plus the read calls the execution
 * workers need (funds before an entry, the executable quote before an order, the order and trade books for
 * recovery and reconciliation) and {@link #poll()}, which pulls the order book and emits an {@link OrderUpdate} for
 * every change since the last pull. The order book is the truth; a stream or postback only makes the pull sooner.
 */
public interface BrokerConnection extends BrokerGateway, AutoCloseable {

    /** Funds available for a new order now (the margin a buy would use). */
    double availableFunds();

    /** The executable top of book for {@code instrumentKey} (ours, e.g. "NSE_FO|47168"). */
    Quote quote(String instrumentKey);

    /** Today's orders, ours and not ours. */
    List<BrokerOrder> orders();

    /** Today's fills. */
    List<BrokerTrade> trades();

    /** Pulls the order book and emits updates for what changed; returns the number emitted. */
    int poll();

    /**
     * The product code our orders are placed with on this connection (as the broker's book shows it, e.g. Kite "MIS"),
     * or null when not known. Checks that count what is held read only orders of this product (16 Nov 2026).
     */
    default String product() {
        return null;
    }

    /**
     * Per contract (our instrument key): the net quantity the account carried into today across every product (overnight
     * positions; a long is positive, a short negative). Today's order book shows only today's orders, so a holding
     * "across all products" read from it alone misses a carried NRML position (review 16 Nov 2026). Empty when nothing
     * is carried or this connection cannot tell; a connection that wraps another must pass it through.
     */
    default java.util.Map<String, Long> carriedIn() {
        return java.util.Map.of();
    }

    /** Our order with this tag in today's book, if the broker has it. */
    default java.util.Optional<BrokerOrder> orderByTag(String clientTag) {
        return orders().stream().filter(o -> clientTag.equals(o.clientTag())).findFirst();
    }

    @Override
    default void close() {
    }
}
