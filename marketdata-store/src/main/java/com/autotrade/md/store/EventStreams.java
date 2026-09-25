package com.autotrade.md.store;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;

/** Merges cursors that are each ordered by {@link ReplayReader#ORDER} into one ordered stream. */
public final class EventStreams {

    private EventStreams() {
    }

    /** Delivers every event of every cursor; closes all cursors. Returns the number delivered. */
    public static long merge(List<? extends EventCursor> cursors, Consumer<MarketEvent> sink) throws Exception {
        try {
            PriorityQueue<EventCursor> heads =
                    new PriorityQueue<>(Comparator.comparing(EventCursor::head, ReplayReader.ORDER));
            for (EventCursor cursor : cursors) {
                if (cursor.advance()) {
                    heads.add(cursor);
                }
            }
            long delivered = 0;
            while (!heads.isEmpty()) {
                EventCursor cursor = heads.poll();
                sink.accept(cursor.head());
                delivered++;
                if (cursor.advance()) {
                    heads.add(cursor);
                }
            }
            return delivered;
        } finally {
            for (EventCursor cursor : cursors) {
                cursor.close();
            }
        }
    }
}
