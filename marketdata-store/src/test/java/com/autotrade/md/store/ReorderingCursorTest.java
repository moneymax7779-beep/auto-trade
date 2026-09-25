package com.autotrade.md.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;

class ReorderingCursorTest {

    private static final Instant T0 = Instant.parse("2026-09-24T04:00:00Z");

    @Test
    void restoresReceiptOrderWithinTheWindow() throws Exception {
        List<MarketEvent> out = drain(new ReorderingCursor(cursor(tick(1, 0), tick(2, 5), tick(3, 4), tick(4, 9)),
                Duration.ofSeconds(2)));

        assertThat(out).extracting(MarketEvent::sourceSequence).containsExactly(1L, 3L, 2L, 4L);
        assertThat(out).isSortedAccordingTo(ReplayReader.ORDER);
    }

    @Test
    void countsEventsDisplacedBeyondTheWindow() throws Exception {
        ReorderingCursor cursor = new ReorderingCursor(
                cursor(tick(1, 0), tick(2, 5_000), tick(3, 9_000), tick(4, 1_000)), Duration.ofSeconds(2));

        List<MarketEvent> out = drain(cursor);

        assertThat(out).hasSize(4);
        assertThat(cursor.lateEvents()).isEqualTo(1);
    }

    private static IndexTick tick(long sequence, long millis) {
        return new IndexTick(T0.plusMillis(millis), null, "NIFTY", sequence, 100.0, null, null);
    }

    private static List<MarketEvent> drain(EventCursor cursor) throws Exception {
        List<MarketEvent> out = new ArrayList<>();
        while (cursor.advance()) {
            out.add(cursor.head());
        }
        cursor.close();
        return out;
    }

    private static EventCursor cursor(MarketEvent... events) {
        Iterator<MarketEvent> iterator = List.of(events).iterator();
        return new EventCursor() {
            private MarketEvent head;

            @Override
            public boolean advance() {
                head = iterator.hasNext() ? iterator.next() : null;
                return head != null;
            }

            @Override
            public MarketEvent head() {
                return head;
            }

            @Override
            public void close() {
            }
        };
    }
}
