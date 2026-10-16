package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.md.live.LiveFeed;

/** Live execution L1: the tee never changes what the session sees and, live, never makes it wait. */
class TeeFeedTest {

    private static final class ListFeed implements LiveFeed {
        private final int count;

        ListFeed(int count) {
            this.count = count;
        }

        @Override
        public String name() {
            return "list";
        }

        @Override
        public void run(Consumer<MarketEvent> sink) {
            for (int i = 0; i < count; i++) {
                sink.accept(new IndexTick(Instant.ofEpochSecond(1_800_000_000L + i), null, "NIFTY", i, 23_000 + i, 23_000.0,
                        null));
            }
        }

        @Override
        public void stop() {
        }

        @Override
        public Instant lastEventTime() {
            return null;
        }

        @Override
        public boolean replay() {
            return true;
        }
    }

    @Test
    void liveTheSessionGetsEveryEventInOrderAndAFullBranchFailsAloneWithoutBlocking() throws Exception {
        QueueFeed branch = new QueueFeed("list+signal", QueueFeed.Overflow.FAIL, 10, false);
        TeeFeed tee = new TeeFeed(new ListFeed(1_000), branch);
        List<Long> seen = new ArrayList<>();
        long started = System.nanoTime();
        tee.run(event -> seen.add(event.sourceSequence()));          // nobody drains the branch
        assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
        assertThat(seen).hasSize(1_000);
        assertThat(seen.getFirst()).isZero();
        assertThat(seen.getLast()).isEqualTo(999);
        assertThat(tee.name()).isEqualTo("list");                   // the session's records keep the feed's name
        assertThat(branch.failure()).contains("queue full");
        assertThatThrownBy(() -> branch.run(event -> { })).hasMessageContaining("signal feed failed");
    }

    @Test
    void inAReplayTheBranchGetsEveryEventAndEndsWithTheFeed() throws Exception {
        QueueFeed branch = new QueueFeed("list+signal", QueueFeed.Overflow.BLOCK, 10, true);
        List<Long> drained = new ArrayList<>();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                branch.run(event -> drained.add(event.sourceSequence()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        List<Long> seen = new ArrayList<>();
        new TeeFeed(new ListFeed(500), branch).run(event -> seen.add(event.sourceSequence()));
        consumer.join(5_000);
        assertThat(consumer.isAlive()).isFalse();                     // ended with the feed
        assertThat(drained).isEqualTo(seen).hasSize(500);
        assertThat(branch.failure()).isNull();
    }
}
