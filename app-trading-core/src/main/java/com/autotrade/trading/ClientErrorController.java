package com.autotrade.trading;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mobile phase 5 (17 Nov 2026): the app's own error reports (a script error, a page that failed to load, an unhandled
 * promise) into this server's log, with who saw it; no third-party tracker. Bounded: each field is cut, and at most
 * {@link #PER_MINUTE} reports a minute are logged (the rest are counted), so a broken page in a loop cannot flood the log.
 */
@RestController
class ClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);
    static final int PER_MINUTE = 30;

    record Report(String kind, String message, String stack, String url, String userAgent) {
    }

    private final AtomicLong minute = new AtomicLong();
    private final java.util.function.LongSupplier clock;

    ClientErrorController() {
        this(() -> Instant.now().getEpochSecond() / 60);
    }

    /** {@code minuteNow}: the current minute number (tests pin it). */
    ClientErrorController(java.util.function.LongSupplier minuteNow) {
        this.clock = minuteNow;
    }
    private final AtomicInteger inMinute = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();

    @PostMapping("/api/client-errors")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@RequestBody Report r, Principal principal) {
        long now = clock.getAsLong();
        if (minute.getAndSet(now) != now) {
            int lost = dropped.getAndSet(0);
            inMinute.set(0);
            if (lost > 0) {
                log.warn("client errors: {} more in the last minute were not logged", lost);
            }
        }
        if (inMinute.incrementAndGet() > PER_MINUTE) {
            dropped.incrementAndGet();
            return;
        }
        log.warn("client error [{}] {} at {} ({}; {}): {}", cut(r.kind(), 20), principal == null ? "-" : principal.getName(),
                cut(r.url(), 200), cut(r.userAgent(), 160), cut(r.message(), 300), cut(r.stack(), 1500));
    }

    /** At most {@code max} characters, on one line (a log line per report). */
    static String cut(String s, int max) {
        if (s == null) {
            return "-";
        }
        String one = s.replaceAll("[\\r\\n\\t]+", " ⏎ ");
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    Map<String, Integer> counts() {
        return Map.of("inMinute", inMinute.get(), "dropped", dropped.get());
    }
}
