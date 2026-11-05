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
 * operator UI can be watched during a replay. Pacing only delays delivery; events and order are the same. With a
 * {@link ReplayControl} the control paces every event (its speed, pauses and stops act at once, even mid-wait), and its
 * clock starts again from the next event after a pause or a speed change, so a pause is never caught up.
 */
public final class ReplayAsLiveFeed implements LiveFeed {

    private final SessionEventSource source;
    private final LocalDate session;
    private final List<String> underlyings;
    private final double speed;
    private final ReplayControl control;
    private volatile boolean stopped;
    private volatile Instant last;
    private Instant firstMarket;
    private long firstWallNanos;

    public ReplayAsLiveFeed(SessionEventSource source, LocalDate session, List<String> underlyings) {
        this(source, session, underlyings, 0);
    }

    /** @param speed market seconds per wall second; 0 or less = unpaced */
    public ReplayAsLiveFeed(SessionEventSource source, LocalDate session, List<String> underlyings, double speed) {
        this(source, session, underlyings, speed, null);
    }

    /** @param control the operator's pause / speed controls (null: none); its speed is used from the start */
    public ReplayAsLiveFeed(SessionEventSource source, LocalDate session, List<String> underlyings, double speed,
                            ReplayControl control) {
        this.source = source;
        this.session = session;
        this.underlyings = underlyings;
        this.speed = speed;
        this.control = control;
    }

    @Override
    public String name() {
        return "replay-as-live:" + source.name();
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        if (control != null) {
            control.attached(true);
        }
        try {
            source.replay(session, underlyings, event -> {
                if (!stopped) {
                    pace(event.receivedAt());
                }
                if (!stopped) {                                // a stop during the wait delivers nothing more
                    last = event.receivedAt();
                    sink.accept(event);
                }
            });
        } finally {
            if (control != null) {
                control.attached(false);
            }
        }
    }

    private void pace(Instant market) {
        if (control != null) {
            try {
                control.pace(market, () -> stopped);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            }
            return;
        }
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
        if (control != null) {
            control.resume();                                  // a paused replay that is stopped ends at once
        }
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
