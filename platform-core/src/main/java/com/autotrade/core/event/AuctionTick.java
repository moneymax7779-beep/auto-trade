package com.autotrade.core.event;

import java.time.Instant;

/**
 * Closing-auction (or pre-open) state of one constituent stock, from the broker's full feed:
 * indicative equilibrium price and quantity, the auction reference price, and the unmatched
 * quantity at the equilibrium price (positive = buyers in excess, negative = sellers), in total and
 * from market orders alone.
 */
public record AuctionTick(
        Instant receivedAt,
        Instant exchangeTime,
        String underlying,
        long instrumentToken,
        String symbol,
        double indicativePrice,
        double referencePrice,
        long equilibriumQuantity,
        long imbalanceTotal,
        long imbalanceMarket,
        boolean casEligible) implements MarketEvent {

    @Override
    public long sourceSequence() {
        return 0L;
    }

    @Override
    public EventKind kind() {
        return EventKind.AUCTION;
    }
}
