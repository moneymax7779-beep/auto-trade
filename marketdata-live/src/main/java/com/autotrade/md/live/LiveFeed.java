package com.autotrade.md.live;

import java.time.Instant;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;

/**
 * A source of market events for a trading session. {@link #run} blocks, delivering events in
 * arrival order on the calling thread, until the feed ends or {@link #stop()} is called.
 */
public interface LiveFeed {

    String name();

    void run(Consumer<MarketEvent> sink) throws Exception;

    void stop();

    /** Receipt time of the newest event delivered, or null. */
    Instant lastEventTime();

    /** True when events carry historical time and the session clock must follow them. */
    boolean replay();
}
