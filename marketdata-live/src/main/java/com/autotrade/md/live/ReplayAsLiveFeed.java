package com.autotrade.md.live;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionEventSource;

/**
 * A recorded session delivered through the live interface: as fast as the consumer takes it, or at
 * {@code speed} market seconds per wall-clock second (e.g. 60 plays an hour in a minute) so the
 * operator UI can be watched during a replay. Pacing only delays delivery; events and order are the same.
 */
public final class ReplayAsLiveFeed implements LiveFeed {

    private final SessionEventSource source;
    private final LocalDate session;
    private final List<String> underlyings;
    private final double speed;
    private volatile boolean stopped;
    private volatile Instant last;
    private Instant firstMarket;
    private long firstWallNanos;

    public ReplayAsLiveFeed(SessionEventSource source, LocalDate session, List<String> underlyings) {
        this(source, session, underlyings, 0);
    }

    /** @param speed market seconds per wall second; 0 or less = unpaced */
    public ReplayAsLiveFeed(SessionEventSource source, LocalDate session, List<String> underlyings, double speed) {
        this.source = source;
        this.session = session;
        this.underlyings = underlyings;
        this.speed = speed;
    }

    @Override
    public String name() {
        return "replay-as-live:" + source.name();
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        source.replay(session, underlyings, event -> {
            if (!stopped) {
                pace(event.receivedAt());
                last = event.receivedAt();
                sink.accept(event);
            }
        });
    }

    private void pace(Instant market) {
        if (!(speed > 0) || market == null) {
            return;
        }
        if (firstMarket == null) {
            firstMarket = market;
            firstWallNanos = System.nanoTime();
            return;
        }
        long dueNanos = firstWallNanos + (long) ((market.toEpochMilli() - firstMarket.toEpochMilli()) * 1_000_000 / speed);
        long wait = dueNanos - System.nanoTime();
        if (wait > 1_000_000) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            }
        }
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
