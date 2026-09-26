package com.autotrade.trading;

import java.time.Instant;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.md.live.LiveFeed;
import com.autotrade.md.store.LiveCapture;

/** A live feed whose every event is also recorded into auto-trade's own md.* tables. */
final class CapturingFeed implements LiveFeed {

    private final LiveFeed feed;
    private final LiveCapture capture;

    CapturingFeed(LiveFeed feed, LiveCapture capture) {
        this.feed = feed;
        this.capture = capture;
    }

    LiveFeed inner() {
        return feed;
    }

    @Override
    public String name() {
        return feed.name() + "+capture";
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        feed.run(event -> {
            capture.accept(event);
            sink.accept(event);
        });
    }

    @Override
    public void stop() {
        feed.stop();
    }

    @Override
    public Instant lastEventTime() {
        return feed.lastEventTime();
    }

    @Override
    public boolean replay() {
        return feed.replay();
    }
}
