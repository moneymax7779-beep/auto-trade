package com.autotrade.trading;

import java.time.Instant;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.md.live.LiveFeed;

/**
 * Live execution L1: hands every event of the session's feed to the session first, then offers it to the signal
 * source's {@link QueueFeed}. The session sees exactly the events and order it would see without the tee; the branch
 * costs it one queue offer per event and, live, can never make it wait. When the feed ends the branch ends too.
 */
final class TeeFeed implements LiveFeed {

    private final LiveFeed primary;
    private final QueueFeed branch;

    TeeFeed(LiveFeed primary, QueueFeed branch) {
        this.primary = primary;
        this.branch = branch;
    }

    @Override
    public String name() {
        return primary.name();
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        try {
            primary.run(event -> {
                sink.accept(event);
                branch.offer(event);
            });
        } finally {
            branch.end();
        }
    }

    @Override
    public void stop() {
        primary.stop();
    }

    @Override
    public Instant lastEventTime() {
        return primary.lastEventTime();
    }

    @Override
    public boolean replay() {
        return primary.replay();
    }
}
