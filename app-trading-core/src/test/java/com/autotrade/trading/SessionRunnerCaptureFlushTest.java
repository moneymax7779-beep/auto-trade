package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 23 Nov 2026: the live capture is flushed every second on its own thread (LIVE Kotak, Upstox and Groww orders are priced
 * from the captured rows), and the session end lets a flush in progress finish before the recorder's last flush.
 */
class SessionRunnerCaptureFlushTest {

    @Test
    void theCaptureIsWrittenAtLeastEverySecond() {
        assertThat(SessionRunner.CAPTURE_FLUSH).isLessThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void stoppingWaitsForTheFlushInProgressAndStartsNoOther() throws Exception {
        ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();
        flusher.scheduleWithFixedDelay(() -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                Thread.sleep(300);                               // a COPY in progress
                finished.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        SessionRunner.stopCaptureFlush(flusher);

        assertThat(interrupted).isFalse();                       // the batch is not cut mid-way
        assertThat(finished).isTrue();                           // it ended before stop returned
        assertThat(flusher.isTerminated()).isTrue();
        int after = runs.get();
        Thread.sleep(50);
        assertThat(runs.get()).isEqualTo(after);                 // no flush after the stop
    }
}
