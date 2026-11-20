package com.autotrade.exec.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Paces order starts per broker app (admin exit desk, user 20 Nov 2026): a bulk exit across several LIVE accounts on the
 * same broker app (credential profile) must not fire its orders at once, or the broker's per-app limit rejects the later
 * ones (Kite documents about 10 order requests a second per app; Kotak's is not published, so the same pace is used).
 * Each {@link #acquire} of a key returns no sooner than {@code 1 / perSecond} after the previous one of that key; keys do
 * not wait for each other. One exit is one place call plus its chase (a re-price every few seconds), so starting at most
 * {@link #DEFAULT_PER_SECOND} a second keeps the app's request rate under its limit.
 */
final class OrderPacer {

    /** Order starts a second per broker app: under Kite's documented ~10/s, with room for the chases' re-prices. */
    static final int DEFAULT_PER_SECOND = 8;

    /** How the pacer waits (tests record instead of sleeping). */
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    /** Per key: the earliest time the next start may go. */
    private final Map<String, Long> next = new ConcurrentHashMap<>();

    OrderPacer(int perSecond) {
        this(perSecond, System::nanoTime, nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos));
    }

    OrderPacer(int perSecond, LongSupplier clock, Sleeper sleeper) {
        if (perSecond <= 0) {
            throw new IllegalArgumentException("perSecond must be above 0");
        }
        this.intervalNanos = 1_000_000_000L / perSecond;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Waits until an order start of {@code key} may go (null key: no pacing, e.g. a paper account with no broker). */
    void acquire(String key) throws InterruptedException {
        if (key == null) {
            return;
        }
        long now = clock.getAsLong();
        long[] slot = new long[1];
        next.compute(key, (k, earliest) -> {
            long at = earliest == null || earliest < now ? now : earliest;
            slot[0] = at;
            return at + intervalNanos;
        });
        long wait = slot[0] - now;
        if (wait > 0) {
            sleeper.sleep(wait);
        }
    }
}
