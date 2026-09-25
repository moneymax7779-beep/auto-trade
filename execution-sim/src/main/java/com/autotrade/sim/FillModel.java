package com.autotrade.sim;

import java.time.Duration;
import java.time.Instant;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.OptionTick;

/**
 * How an order fills against the captured book: it reaches the market {@code latency} after the
 * decision, takes the first quote received at or after that, walks the visible depth (asks for a
 * buy, bids for a sell), then moves the average price {@code adverseBps} against the trader. Size
 * beyond the visible five levels fills {@code depthExhaustedExtraTicks} ticks past the last level.
 */
public record FillModel(String name, Duration latency, double adverseBps, int depthExhaustedExtraTicks,
                        double tickSize) {

    public static FillModel from(ThresholdConfig config, String name) {
        return new FillModel(name,
                Duration.ofMillis(config.getInt("fills." + name + ".latency_ms")),
                config.getDouble("fills." + name + ".adverse_bps"),
                config.getInt("fills.depth_exhausted_extra_ticks"),
                config.getDouble("fills.tick_size"));
    }

    public record Fill(Instant time, double price, long quantity, int levelsUsed, boolean depthExhausted,
                       double touch) {
    }

    /** Fills {@code quantity} against {@code quote}; null when that side of the book is empty. */
    public Fill fill(Side side, long quantity, OptionTick quote) {
        DepthLevels book = side == Side.BUY ? quote.asks() : quote.bids();
        if (book.isEmpty() || book.price(0) <= 0) {
            return null;
        }
        long remaining = quantity;
        double notional = 0;
        int levels = 0;
        double lastPrice = book.price(0);
        for (int i = 0; i < book.size() && remaining > 0; i++) {
            if (book.price(i) <= 0 || book.quantity(i) <= 0) {
                continue;
            }
            long take = Math.min(remaining, book.quantity(i));
            notional += take * book.price(i);
            remaining -= take;
            lastPrice = book.price(i);
            levels++;
        }
        boolean exhausted = remaining > 0;
        if (exhausted) {
            double worse = side == Side.BUY ? lastPrice + depthExhaustedExtraTicks * tickSize
                    : Math.max(tickSize, lastPrice - depthExhaustedExtraTicks * tickSize);
            notional += remaining * worse;
        }
        double average = notional / quantity;
        double adverse = average * adverseBps / 10_000.0;
        double price = side == Side.BUY ? average + adverse : Math.max(tickSize, average - adverse);
        return new Fill(quote.receivedAt(), price, quantity, levels, exhausted, book.price(0));
    }
}
