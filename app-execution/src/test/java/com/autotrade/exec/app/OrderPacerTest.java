package com.autotrade.exec.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Admin exit desk (user 20 Nov 2026): a bulk exit's LIVE order starts are paced per broker app, at most 8 a second (Kite
 * documents about 10 order requests a second per app), never fired at once; other apps and paper (no key) never wait.
 */
class OrderPacerTest {

    @Test
    void startsOfOneBrokerAppAreSpacedAndOthersDoNotWait() throws Exception {
        AtomicLong now = new AtomicLong(0);
        List<Long> waits = new ArrayList<>();
        OrderPacer pacer = new OrderPacer(8, now::get, nanos -> {
            waits.add(nanos);
            now.addAndGet(nanos);                                // the sleep moves the clock
        });
        for (int i = 0; i < 4; i++) {
            pacer.acquire("app:1");
        }
        assertThat(waits).containsExactly(125_000_000L, 125_000_000L, 125_000_000L);
        assertThat(now.get()).as("4 starts take 3/8 s, i.e. 8 a second").isEqualTo(375_000_000L);

        waits.clear();
        pacer.acquire("app:2");                                   // another broker app: its own pace
        pacer.acquire(null);                                      // paper: no broker, no pace
        assertThat(waits).isEmpty();

        now.addAndGet(1_000_000_000L);                            // a second later the app's slot is free again
        pacer.acquire("app:1");
        assertThat(waits).isEmpty();
    }

    @Test
    void startsAtTheSameInstantQueueBehindEachOther() throws Exception {
        List<Long> waits = new ArrayList<>();
        OrderPacer pacer = new OrderPacer(8, () -> 0L, waits::add);   // the clock never moves: every wait is the pacer's
        for (int i = 0; i < 3; i++) {
            pacer.acquire("app:1");
        }
        assertThat(waits).containsExactly(125_000_000L, 250_000_000L);
        assertThatThrownBy(() -> new OrderPacer(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
