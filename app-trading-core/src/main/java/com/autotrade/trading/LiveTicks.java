package com.autotrade.trading;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.autotrade.core.event.IndexTick;
import com.autotrade.core.time.MarketTime;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The index price pushed to the charts as it moves (user, 5 Nov 2026: "build the full V2-style push for our chart"; as
 * zt-tiger-v2 does): the running session hands every index tick here ({@link #accept}, a map update, nothing more), and
 * every {@value #PUSH_MS} ms the indices that moved go to each open page over server-sent events ({@code
 * /api/live/ticks}): price, the high and low since the last push, the time. From memory only, never the database. A
 * page shows them at most twice a second and extends the chart's last minute; the 30-second chart refresh stays as the
 * backstop. Pages get the latest of each index on connecting, and a comment every {@value #HEARTBEAT_S} s keeps proxies
 * (nginx's 60-second read timeout) from closing an idle stream.
 */
@RestController
class LiveTicks implements org.springframework.beans.factory.DisposableBean {

    static final long PUSH_MS = 250;
    static final long HEARTBEAT_S = 15;
    /** At most this many open streams (a page each): more is refused (503) rather than slowing the session. */
    static final int MAX_STREAMS = 100;
    /** A stream ends after this long and the browser reconnects (EventSource does it by itself). */
    static final long STREAM_MS = 10 * 60_000L;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** One index's latest price and its range since the last push. */
    static final class Latest {
        final String underlying;
        LocalDate date;
        String mode;
        Instant at;
        double price, high, low;
        boolean moved;

        Latest(String underlying) {
            this.underlying = underlying;
        }
    }

    private final Map<String, Latest> latest = new ConcurrentHashMap<>();
    /**
     * One open page. Each send runs on its own virtual thread, so a stalled page never holds up the others; while its
     * last send is still going, a push is skipped for it and the next one carries every index's latest ({@code behind}).
     */
    private static final class Stream {
        final SseEmitter emitter;
        final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean behind;

        Stream(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }

    private final List<Stream> streams = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("live-ticks").daemon(true).factory());
    private long beats;

    @org.springframework.beans.factory.annotation.Autowired
    LiveTicks() {
        this(true);
    }

    /** @param push whether the timer pushes (false in tests, which drain by hand) */
    LiveTicks(boolean push) {
        if (push) {
            pusher.scheduleWithFixedDelay(this::pushSafely, PUSH_MS, PUSH_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** From the session's feed thread: one index tick of {@code date}'s session in {@code mode} (PAPER_LIVE, PAPER_REPLAY). */
    void accept(IndexTick tick, LocalDate date, String mode) {
        if (!(tick.price() > 0)) {
            return;
        }
        Latest l = latest.computeIfAbsent(tick.underlying(), Latest::new);
        synchronized (l) {
            Instant at = tick.exchangeTime() != null ? tick.exchangeTime() : tick.receivedAt();
            if (l.moved && date.equals(l.date)) {
                l.high = Math.max(l.high, tick.price());
                l.low = Math.min(l.low, tick.price());
            } else {
                l.high = tick.price();
                l.low = tick.price();
            }
            l.date = date;
            l.mode = mode;
            l.at = at;
            l.price = tick.price();
            l.moved = true;
        }
    }

    @GetMapping(path = "/api/live/ticks", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(HttpServletResponse response) throws IOException {
        if (streams.size() >= MAX_STREAMS) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "too many live streams");
        }
        response.setHeader("X-Accel-Buffering", "no");       // nginx: pass each event on at once
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(STREAM_MS);
        Stream stream = new Stream(emitter);
        emitter.onCompletion(() -> streams.remove(stream));
        // a stream that reached its time ends normally (the browser reconnects), not as an error Spring then logs
        emitter.onTimeout(() -> {
            streams.remove(stream);
            emitter.complete();
        });
        emitter.onError(e -> streams.remove(stream));
        // the latest of each index first ("snapshot": a page does not count it as live), and only then the pushes, so no
        // push can overtake it with an older price
        emitter.send(SseEmitter.event().name("snapshot").data(snapshot(), MediaType.APPLICATION_JSON));
        streams.add(stream);
        return emitter;
    }

    /** Every index's latest. */
    private List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> now = new ArrayList<>();
        latest.values().forEach(l -> {
            synchronized (l) {
                if (l.at != null) {
                    now.add(payload(l));
                }
            }
        });
        return now;
    }

    /** A session has ended: its prices are no longer pushed, nor handed to pages that open afterwards. */
    void clear() {
        latest.clear();
    }

    /** The indices that moved since the last push; their ranges start again. */
    List<Map<String, Object>> drain() {
        List<Map<String, Object>> out = new ArrayList<>();
        latest.values().forEach(l -> {
            synchronized (l) {
                if (l.moved) {
                    out.add(payload(l));
                    l.moved = false;
                }
            }
        });
        return out;
    }

    private static Map<String, Object> payload(Latest l) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("u", l.underlying);
        p.put("d", l.date.toString());
        p.put("t", HMS.format(l.at.atZone(MarketTime.IST)));
        p.put("p", l.price);
        p.put("h", l.high);
        p.put("l", l.low);
        p.put("m", l.mode);
        return p;
    }

    private void pushSafely() {
        try {
            List<Map<String, Object>> moved = drain();
            boolean beat = ++beats % (HEARTBEAT_S * 1000 / PUSH_MS) == 0;
            if (streams.isEmpty() || (moved.isEmpty() && !beat)) {
                return;
            }
            for (Stream s : streams) {
                if (!s.busy.compareAndSet(false, true)) {
                    s.behind = true;                            // still sending the last push: catch up next time
                    continue;
                }
                List<Map<String, Object>> data = s.behind ? snapshot() : moved;
                s.behind = false;
                Thread.ofVirtual().start(() -> {
                    try {
                        if (!data.isEmpty()) {
                            s.emitter.send(SseEmitter.event().name("ticks").data(data, MediaType.APPLICATION_JSON));
                        } else {
                            s.emitter.send(SseEmitter.event().comment("alive"));
                        }
                    } catch (IOException | IllegalStateException e) {
                        streams.remove(s);                      // the page went away (the container reports it)
                    } finally {
                        s.busy.set(false);
                    }
                });
            }
        } catch (Throwable e) {                                 // never let one failure stop the pushes
            org.slf4j.LoggerFactory.getLogger(LiveTicks.class).warn("live ticks: {}", e.toString());
        }
    }

    int streams() {
        return streams.size();
    }

    @Override
    public void destroy() {
        pusher.shutdownNow();
        for (Stream s : streams) {
            try {
                s.emitter.complete();
            } catch (RuntimeException e) {               // a stream whose response is already gone: nothing to close
                // ignored at shutdown
            }
        }
        streams.clear();
    }
}
