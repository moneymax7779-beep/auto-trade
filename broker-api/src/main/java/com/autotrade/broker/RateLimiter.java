package com.autotrade.broker;

/**
 * At most {@code perSecond} permits in any rolling second. Callers wait for a permit, one at a time (a waiting caller
 * holds the lock, so callers sharing a limiter queue): an account's order limiter has one caller, its lane; the quote
 * limiter of an app is shared by every account and route on that app. Shared by every broker adapter (8 Nov 2026).
 */
public final class RateLimiter {

    private final long[] stamps;
    private int next;

    public RateLimiter(int perSecond) {
        this.stamps = new long[perSecond];
        java.util.Arrays.fill(stamps, Long.MIN_VALUE);
    }

    public synchronized void acquire() {
        long now = System.nanoTime();
        long oldest = stamps[next];
        long wait = oldest == Long.MIN_VALUE ? 0 : oldest + 1_000_000_000L - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = System.nanoTime();
        }
        stamps[next] = now;
        next = (next + 1) % stamps.length;
    }
}
