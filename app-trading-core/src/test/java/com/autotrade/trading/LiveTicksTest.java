package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.IndexTick;

/** The charts' live push: the latest price per index with its range since the last push, only for indices that moved. */
class LiveTicksTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    private static IndexTick tick(String u, String utc, double price) {
        return new IndexTick(Instant.parse("2026-11-05T" + utc + "Z"), null, u, 1, price, null, null);
    }

    @Test
    void pushesEachMovedIndexOnceWithItsRangeSinceTheLastPush() {
        LiveTicks hub = new LiveTicks(false);
        try {
            hub.accept(tick("NIFTY", "08:55:00.100", 24_517.30), DAY, "PAPER_LIVE");
            hub.accept(tick("NIFTY", "08:55:00.200", 24_519.00), DAY, "PAPER_LIVE");
            hub.accept(tick("NIFTY", "08:55:00.300", 24_516.10), DAY, "PAPER_LIVE");
            hub.accept(tick("SENSEX", "08:55:00.150", 79_300.00), DAY, "PAPER_LIVE");
            hub.accept(tick("NIFTY", "08:55:00.400", 0), DAY, "PAPER_LIVE");   // no price: ignored
            List<Map<String, Object>> pushed = hub.drain();
            assertThat(pushed).hasSize(2);
            Map<String, Object> nifty = pushed.stream().filter(p -> p.get("u").equals("NIFTY")).findFirst().orElseThrow();
            assertThat(nifty).containsEntry("d", "2026-11-05").containsEntry("t", "14:25:00.300").containsEntry("p", 24_516.10)
                    .containsEntry("h", 24_519.00).containsEntry("l", 24_516.10).containsEntry("m", "PAPER_LIVE");
            assertThat(hub.drain()).isEmpty();                              // nothing moved since
            hub.accept(tick("NIFTY", "08:55:01.000", 24_520.00), DAY, "PAPER_LIVE");
            assertThat(hub.drain()).singleElement().satisfies(p -> {
                assertThat(p).containsEntry("h", 24_520.00).containsEntry("l", 24_520.00);   // the range starts again
            });
        } finally {
            hub.destroy();
        }
    }
}
