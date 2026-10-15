package com.autotrade.features.preopen;

import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.snapshot.PreOpenFeatures;

/**
 * Features v14: the constituents' pre-open auction. Keeps each stock's latest auction quote received before the normal
 * open (09:15 IST); weights and previous closes come from the constituent feed (the breadth state's view).
 * <p>
 * The cut-off is the time the tick was received, so a replay keeps exactly what the live engine had at 09:15 (the
 * auction's final quotes arrive between 09:08 and 09:15). The quotes are final at 09:15, but a stock's weight and
 * previous close arrive with its first constituent tick, so the value is recomputed until {@link #FREEZE} and then
 * kept unchanged for the rest of the session: every entry after 09:20 sees the same value.
 */
public final class PreOpenState {

    static final LocalTime OPEN = LocalTime.of(9, 15);
    static final LocalTime FREEZE = LocalTime.of(9, 20);

    private record Quote(double iep, long imbalance) {
    }

    private final Map<String, Quote> quotes = new HashMap<>();
    private PreOpenFeatures frozen;

    /** A pre-open auction tick (ticks at or after 09:15 IST, e.g. the closing auction's, are ignored). */
    public void onAuction(AuctionTick tick) {
        if (!tick.receivedAt().atZone(MarketTime.IST).toLocalTime().isBefore(OPEN) || !(tick.indicativePrice() > 0)) {
            return;
        }
        quotes.put(tick.symbol(), new Quote(tick.indicativePrice(), tick.imbalanceTotal()));
    }

    /**
     * The features at snapshot {@code time}: computed from the breadth state's current view before {@link #FREEZE},
     * the value computed at the first snapshot at or after it from then on (the suppliers are not called again).
     */
    public PreOpenFeatures features(Instant time, Supplier<Map<String, Double>> weights,
                                    Supplier<Map<String, Double>> previousCloses) {
        if (frozen != null) {
            return frozen;
        }
        PreOpenFeatures now = features(weights.get(), previousCloses.get());
        if (!time.atZone(MarketTime.IST).toLocalTime().isBefore(FREEZE)) {
            frozen = now;
        }
        return now;
    }

    /** {@code weights} (percent) and {@code previousCloses} per constituent symbol, as known now. */
    PreOpenFeatures features(Map<String, Double> weights, Map<String, Double> previousCloses) {
        double total = 0, covered = 0, ret = 0, breadth = 0, imbalance = 0;
        for (Map.Entry<String, Double> w : weights.entrySet()) {
            if (w.getValue() == null || !(w.getValue() > 0)) {
                continue;
            }
            total += w.getValue();
            Quote q = quotes.get(w.getKey());
            Double pc = previousCloses.get(w.getKey());
            if (q == null || pc == null || !(pc > 0)) {
                continue;
            }
            double weight = w.getValue();
            covered += weight;
            ret += weight * (q.iep() / pc - 1);
            breadth += weight * Math.signum(q.iep() - pc);
            imbalance += weight * Math.signum(q.imbalance());
        }
        if (!(covered > 0)) {
            return PreOpenFeatures.EMPTY;
        }
        return new PreOpenFeatures(100 * ret / covered, 100 * breadth / covered, 100 * imbalance / covered,
                100 * covered / total);
    }
}
