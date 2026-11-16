package com.autotrade.md.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionEventSource;

/**
 * A stopped replay ends its source's read at once (15 Nov 2026: the replay bar's Restart waited minutes while a stopped
 * replay's source read the rest of the day, millions of rows, delivering nothing).
 */
class ReplayAsLiveFeedTest {

    private static final Instant START = Instant.parse("2026-11-13T03:45:00Z");

    /** A day of a million index ticks, one a millisecond, counting how many the source produced. */
    private static final class Day implements SessionEventSource {
        final AtomicLong produced = new AtomicLong();

        @Override
        public String name() {
            return "a million ticks";
        }

        @Override
        public ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink) {
            for (long i = 0; i < 1_000_000; i++) {
                produced.incrementAndGet();
                sink.accept(new IndexTick(START.plusMillis(i), null, "NIFTY", i, 23_000, null, null));
            }
            return new ReplayResult(1_000_000, 0);
        }
    }

    @Test
    void aStoppedReplayStopsReadingItsSource() throws Exception {
        Day day = new Day();
        ReplayControl control = new ReplayControl();
        control.reset(1_000_000);
        ReplayAsLiveFeed feed = new ReplayAsLiveFeed(day, LocalDate.of(2026, 11, 13), List.of("NIFTY"), 1_000_000, control);
        AtomicLong delivered = new AtomicLong();
        feed.run(event -> {
            if (delivered.incrementAndGet() == 10) {
                feed.stop();
            }
        });
        assertThat(delivered.get()).isEqualTo(10);
        assertThat(day.produced.get()).as("read on after the stop").isEqualTo(11);
        assertThat(control.attached()).isFalse();
    }

    @Test
    void aPausedReplayThatIsStoppedEndsWithoutReadingOn() throws Exception {
        Day day = new Day();
        ReplayControl control = new ReplayControl();
        control.reset(1_000_000);
        control.pause();
        ReplayAsLiveFeed feed = new ReplayAsLiveFeed(day, LocalDate.of(2026, 11, 13), List.of("NIFTY"), 1_000_000, control);
        Thread stopper = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            feed.stop();
        });
        AtomicLong delivered = new AtomicLong();
        feed.run(event -> delivered.incrementAndGet());
        stopper.join();
        assertThat(delivered.get()).isZero();
        assertThat(day.produced.get()).isEqualTo(1);
    }
}
