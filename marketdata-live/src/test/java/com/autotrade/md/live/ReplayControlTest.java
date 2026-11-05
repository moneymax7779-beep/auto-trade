package com.autotrade.md.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * A paced replay's controls: pause at a market time, after the next complete entry / closed position (one contract's),
 * resume; a speed change or a stop acts at once, even in the middle of a long wait; nothing passes while paused.
 */
class ReplayControlTest {

    private static final Instant T0 = Instant.parse("2026-11-04T04:00:00Z");   // 09:30 IST

    /** {@code pace} on another thread (the feed's), so the test thread can act on the control meanwhile. */
    private static CompletableFuture<Void> paceAsync(ReplayControl c, Instant market, AtomicBoolean stopped) {
        return CompletableFuture.runAsync(() -> {
            try {
                c.pace(market, stopped::get);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static boolean doneWithin(CompletableFuture<Void> f, long millis) throws Exception {
        try {
            f.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    @Test
    void holdsEventsWhilePausedAtAMarketTimeRunsTheHookOnceAndResumes() throws Exception {
        ReplayControl c = new ReplayControl();
        c.reset(1_000);
        AtomicInteger flushes = new AtomicInteger();
        c.onPause(flushes::incrementAndGet);
        c.pauseAt(T0.plusSeconds(60));
        AtomicBoolean stopped = new AtomicBoolean();
        c.pace(T0, stopped::get);                                               // before the time: runs on
        CompletableFuture<Void> f = paceAsync(c, T0.plusSeconds(60), stopped);
        assertThat(doneWithin(f, 400)).isFalse();                              // held back while paused
        assertThat(c.state()).containsEntry("paused", true).containsEntry("pausedBecause", "time 09:31:00")
                .containsEntry("pauseAt", null);
        assertThat(flushes.get()).isEqualTo(1);                                 // the hook once per pause, not per wake-up
        c.resume();
        assertThat(doneWithin(f, 2_000)).isTrue();
    }

    @Test
    void aSpeedChangeDuringALongWaitTakesEffectAtOnce() throws Exception {
        ReplayControl c = new ReplayControl();
        c.reset(1);                                                             // real time: the next event an hour away
        AtomicBoolean stopped = new AtomicBoolean();
        c.pace(T0, stopped::get);
        CompletableFuture<Void> f = paceAsync(c, T0.plusSeconds(3_600), stopped);
        assertThat(doneWithin(f, 300)).isFalse();
        c.speed(1_000_000);                                                     // the wait is recomputed, not slept out
        assertThat(doneWithin(f, 2_000)).isTrue();
    }

    @Test
    void aStopEndsAWaitAndAPauseAtOnce() throws Exception {
        ReplayControl c = new ReplayControl();
        c.reset(1);
        AtomicBoolean stopped = new AtomicBoolean();
        c.pace(T0, stopped::get);
        CompletableFuture<Void> waiting = paceAsync(c, T0.plusSeconds(3_600), stopped);
        assertThat(doneWithin(waiting, 300)).isFalse();
        stopped.set(true);
        c.resume();                                                             // what a stopping feed does
        assertThat(doneWithin(waiting, 2_000)).isTrue();

        stopped.set(false);
        c.pause();
        CompletableFuture<Void> paused = paceAsync(c, T0.plusSeconds(3_700), stopped);
        assertThat(doneWithin(paused, 300)).isFalse();
        stopped.set(true);
        c.resume();
        assertThat(doneWithin(paused, 2_000)).isTrue();
    }

    @Test
    void pausesOnlyOnTheChosenEntryOrExit() {
        ReplayControl c = new ReplayControl();
        c.reset(60);
        c.pauseOn(ReplayControl.PauseOn.EXIT, "NIFTY 23950 CE");
        c.filled("ENTRY", "NIFTY 23950 CE");                                     // an entry: not what it waits for
        c.filled("EXIT", "SENSEX 77200 CE");                                     // another contract's exit
        assertThat(c.state().get("paused")).isEqualTo(false);
        c.filled("EXIT", "NIFTY 23950 CE");
        assertThat(c.state()).containsEntry("paused", true).containsEntry("pausedBecause", "exit NIFTY 23950 CE")
                .containsEntry("pauseOn", "NONE");
        c.resume();
        c.pauseOn(ReplayControl.PauseOn.TRADE, null);
        c.filled("ENTRY", "SENSEX 77300 PE");
        assertThat(c.state()).containsEntry("paused", true).containsEntry("pausedBecause", "entry SENSEX 77300 PE");
        c.reset(60);
        assertThat(c.state()).containsEntry("paused", false).containsEntry("attached", false);
    }
}
