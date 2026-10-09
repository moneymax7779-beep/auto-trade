package com.autotrade.trading;

import java.util.List;

/**
 * Who initiated a minute's futures trades, for the surge panel (display only, never a trading input). Each tick's
 * traded quantity is the rise in cumulative volume since the previous tick; it counts as bought when the traded price
 * is at or above the previous tick's best ask, sold when at or below its best bid, and otherwise by the tick rule
 * (above the previous traded price: bought, below: sold). Unchanged prices inside the spread stay unclassified.
 * A tick can carry several trades, so the split is an estimate.
 */
final class AggressorSplit {

    /** One futures tick: last traded price, cumulative volume, best bid and ask (NaN when absent). */
    record Tick(double price, double volume, double bid, double ask) {
    }

    record Split(long bought, long sold, long unclassified) {
        /** Bought as a percentage of the classified quantity, or null when nothing was classified. */
        Long boughtPct() {
            long classified = bought + sold;
            return classified == 0 ? null : Math.round(100.0 * bought / classified);
        }
    }

    private AggressorSplit() {
    }

    /** Splits the ticks of one minute; {@code previous} is the last tick before the minute (null when none). */
    static Split of(Tick previous, List<Tick> minute) {
        long bought = 0;
        long sold = 0;
        long unclassified = 0;
        Tick last = previous;
        for (Tick tick : minute) {
            if (last == null) {
                last = tick;                                  // no earlier volume to difference against
                continue;
            }
            double traded = tick.volume() - last.volume();
            if (traded > 0) {
                long quantity = Math.round(traded);
                if (last.ask() > 0 && tick.price() >= last.ask()) {
                    bought += quantity;
                } else if (last.bid() > 0 && tick.price() <= last.bid()) {
                    sold += quantity;
                } else if (tick.price() > last.price()) {
                    bought += quantity;
                } else if (tick.price() < last.price()) {
                    sold += quantity;
                } else {
                    unclassified += quantity;
                }
            }
            last = tick;
        }
        return new Split(bought, sold, unclassified);
    }
}
