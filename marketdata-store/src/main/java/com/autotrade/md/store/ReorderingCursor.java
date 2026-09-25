package com.autotrade.md.store;

import java.time.Duration;
import java.time.Instant;
import java.util.PriorityQueue;

import com.autotrade.core.event.MarketEvent;

/**
 * Wraps a cursor that is ordered by capture sequence and nearly ordered by receipt time, and
 * restores exact {@link ReplayReader#ORDER} for events displaced by less than {@code window}.
 * Events displaced further are delivered as soon as seen and counted in {@link #lateEvents()}.
 */
public final class ReorderingCursor implements EventCursor {

    private final EventCursor inner;
    private final Duration window;
    private final PriorityQueue<MarketEvent> buffer = new PriorityQueue<>(ReplayReader.ORDER);
    private Instant newestSeen;
    private MarketEvent lastEmitted;
    private MarketEvent head;
    private boolean innerDone;
    private long lateEvents;

    public ReorderingCursor(EventCursor inner, Duration window) {
        this.inner = inner;
        this.window = window;
    }

    @Override
    public boolean advance() throws Exception {
        while (!innerDone && (buffer.isEmpty() || !oldestIsSettled())) {
            if (inner.advance()) {
                MarketEvent event = inner.head();
                if (newestSeen == null || event.receivedAt().isAfter(newestSeen)) {
                    newestSeen = event.receivedAt();
                }
                buffer.add(event);
            } else {
                innerDone = true;
            }
        }
        head = buffer.poll();
        if (head == null) {
            return false;
        }
        if (lastEmitted != null && ReplayReader.ORDER.compare(lastEmitted, head) > 0) {
            lateEvents++;
        } else {
            lastEmitted = head;
        }
        return true;
    }

    private boolean oldestIsSettled() {
        return !buffer.peek().receivedAt().plus(window).isAfter(newestSeen);
    }

    @Override
    public MarketEvent head() {
        return head;
    }

    public long lateEvents() {
        return lateEvents;
    }

    @Override
    public void close() {
        inner.close();
    }
}
