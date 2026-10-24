package com.autotrade.trading;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.md.live.LiveFeed;

/**
 * Live execution L1: the signal source's feed, filled by a {@link TeeFeed} from the session's own feed through a
 * bounded queue and drained on the signal session's thread. In a replay a full queue blocks the producer (the replay
 * stays deterministic); live, a full queue fails this feed only, so the signal session stops and the session that owns
 * the real feed never waits.
 */
final class QueueFeed implements LiveFeed {

    enum Overflow { BLOCK, FAIL }

    private static final Object END = new Object();

    private final String name;
    private final Overflow overflow;
    private final boolean replay;
    private final BlockingQueue<Object> queue;
    private volatile boolean stopped;
    private volatile String failure;
    private volatile Instant last;

    QueueFeed(String name, Overflow overflow, int capacity, boolean replay) {
        this.name = name;
        this.overflow = overflow;
        this.replay = replay;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Called on the producer's thread for every event; never blocks in FAIL mode. */
    void offer(MarketEvent event) {
        if (failure != null || stopped) {
            return;
        }
        if (overflow == Overflow.BLOCK) {
            try {
                queue.put(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "interrupted";
            }
        } else if (!queue.offer(event)) {
            failure = "queue full (" + queue.size() + " events): the signal source fell behind";
            queue.clear();                            // the branch fails on its next read; the events are not needed
        }
    }

    /** The producer's feed ended. */
    void end() {
        if (overflow == Overflow.BLOCK) {
            try {
                queue.put(END);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (!queue.offer(END) && failure == null) {
            failure = "queue full at the end of the feed";
        }
    }

    String failure() {
        return failure;
    }

    /** The reader is gone (it ended or failed): stop taking events and drop what is queued, so nothing is held. */
    void release() {
        stopped = true;
        queue.clear();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        while (!stopped) {
            if (failure != null) {
                throw new IllegalStateException("signal feed failed: " + failure);
            }
            Object next = queue.poll(200, TimeUnit.MILLISECONDS);
            if (next == null) {
                continue;
            }
            if (next == END) {
                return;
            }
            MarketEvent event = (MarketEvent) next;
            sink.accept(event);
            last = event.receivedAt();
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
        return replay;
    }
}
