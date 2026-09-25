package com.autotrade.md.live;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionEventSource;

/** A recorded session delivered through the live interface, as fast as the consumer takes it. */
public final class ReplayAsLiveFeed implements LiveFeed {

    private final SessionEventSource source;
    private final LocalDate session;
    private final List<String> underlyings;
    private volatile boolean stopped;
    private volatile Instant last;

    public ReplayAsLiveFeed(SessionEventSource source, LocalDate session, List<String> underlyings) {
        this.source = source;
        this.session = session;
        this.underlyings = underlyings;
    }

    @Override
    public String name() {
        return "replay-as-live:" + source.name();
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        source.replay(session, underlyings, event -> {
            if (!stopped) {
                last = event.receivedAt();
                sink.accept(event);
            }
        });
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public Instant lastEventTime() {
        return last;
    }

    @Override
    public boolean replay() {
        return true;
    }
}
