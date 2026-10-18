package com.autotrade.zerodha;

/**
 * At most {@code perSecond} permits in any rolling second (orders, modifies and cancels count; reads do not). Callers
 * wait for a permit; the account's lane is the only caller, so waiting delays only that account.
 */
final class RateLimiter {

    private final long[] stamps;
    private int next;

    RateLimiter(int perSecond) {
        this.stamps = new long[perSecond];
        java.util.Arrays.fill(stamps, Long.MIN_VALUE);
    }

    synchronized void acquire() {
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
