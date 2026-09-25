package com.autotrade.tools.command;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import com.autotrade.core.event.EventKind;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.ReplayReader;

/**
 * Checks a replay stream: it must be non-decreasing in {@link ReplayReader#ORDER}. Also reports how
 * often receipt order and capture sequence disagree within an underlying (informational).
 */
final class ReplayStats implements Consumer<MarketEvent> {

    private final Map<String, Long> counts = new TreeMap<>();
    private final Map<String, Instant> first = new HashMap<>();
    private final Map<String, Instant> last = new HashMap<>();
    private final Map<String, Long> lastSequence = new HashMap<>();
    private final Map<String, Long> sequenceInversions = new TreeMap<>();
    private MarketEvent previous;
    private long orderViolations;

    @Override
    public void accept(MarketEvent event) {
        if (previous != null && ReplayReader.ORDER.compare(previous, event) > 0) {
            orderViolations++;
        }
        previous = event;
        String key = event.underlying() + " " + event.kind();
        counts.merge(key, 1L, Long::sum);
        first.putIfAbsent(event.underlying(), event.receivedAt());
        last.put(event.underlying(), event.receivedAt());
        if (event.kind() != EventKind.SESSION_PHASE) {
            Long before = lastSequence.put(event.underlying(), event.sourceSequence());
            if (before != null && event.sourceSequence() < before) {
                sequenceInversions.merge(event.underlying(), 1L, Long::sum);
            }
        }
    }

    long orderViolations() {
        return orderViolations;
    }

    void print(PrintStream out) {
        counts.forEach((key, count) -> out.printf("  %-24s %,12d%n", key, count));
        for (String underlying : new TreeMap<>(first).keySet()) {
            out.printf("  %-8s first %s  last %s IST  capture-order inversions %d%n", underlying,
                    ist(first.get(underlying)), ist(last.get(underlying)),
                    sequenceInversions.getOrDefault(underlying, 0L));
        }
        out.printf("  replay-order violations: %d%s%n", orderViolations, orderViolations == 0 ? " (in order)" : "");
    }

    private static LocalTime ist(Instant instant) {
        return instant.atZone(MarketTime.IST).toLocalTime();
    }
}
