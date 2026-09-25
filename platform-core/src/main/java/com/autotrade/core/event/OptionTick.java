package com.autotrade.core.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Option quote with five-level depth. Greeks and IV come from the broker feed; they are 0 when the
 * feed had none ({@code analyticsComplete} false), and later phases recompute them.
 */
public record OptionTick(
        Instant receivedAt,
        Instant exchangeTime,
        String underlying,
        long sourceSequence,
        long instrumentToken,
        String symbol,
        LocalDate expiry,
        double strike,
        OptionType optionType,
        Integer lotSize,
        double lastPrice,
        Long cumulativeVolume,
        Double openInterest,
        Double totalBuyQuantity,
        Double totalSellQuantity,
        DepthLevels bids,
        DepthLevels asks,
        Double impliedVolatility,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        Double rho,
        Instant analyticsTime,
        boolean analyticsComplete,
        boolean depthComplete) implements MarketEvent {

    public enum OptionType { CE, PE }

    @Override
    public EventKind kind() {
        return EventKind.OPTION;
    }

    /** Best bid/ask spread in rupees, or NaN when either side is empty. */
    public double spread() {
        if (bids.isEmpty() || asks.isEmpty()) {
            return Double.NaN;
        }
        return asks.price(0) - bids.price(0);
    }
}
