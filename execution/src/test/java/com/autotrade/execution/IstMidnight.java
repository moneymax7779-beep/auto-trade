package com.autotrade.execution;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * For integration tests that need the database's own clock: {@code acct.entry_target} and the follower's last check read
 * {@code clock_timestamp()} (code review 7 Nov 2026), which no test can inject. Such tests stamp the day's login and LIVE
 * switch a few minutes before the wall clock, and a LIVE switch counts only within its IST day, so they must not run
 * across IST midnight. Called once as such a class loads: between 23:57 and 00:12 IST it waits until 00:12 (the
 * fixtures reach 10 minutes back, a class runs well under a minute). 25 Nov 2026: the tests had pinned the day's login
 * (and LiveIT its LIVE switch) to 08:00/09:00 IST of today, after the wall clock before then, and failed from midnight.
 */
final class IstMidnight {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime CLEAR_FROM = LocalTime.of(0, 12);
    static final LocalTime CLEAR_UNTIL = LocalTime.of(23, 57);

    private IstMidnight() {
    }

    /** How long to wait at {@code now} (IST) before the day is clear of midnight; zero when it already is. */
    static Duration waitAt(LocalDateTime now) {
        LocalTime t = now.toLocalTime();
        if (!t.isBefore(CLEAR_FROM) && t.isBefore(CLEAR_UNTIL)) {
            return Duration.ZERO;
        }
        LocalDateTime clear = (t.isBefore(CLEAR_FROM) ? now.toLocalDate() : now.toLocalDate().plusDays(1))
                .atTime(CLEAR_FROM);
        return Duration.between(now, clear);
    }

    /** Waits until the IST day is clear of midnight; returns that day. */
    static LocalDate awaitClear() {
        Duration wait = waitAt(LocalDateTime.now(IST));
        if (wait.isZero()) {
            return LocalDate.now(IST);
        }
        System.err.printf("IstMidnight: waiting %d s until %s IST: these tests read the database clock and must not "
                + "cross IST midnight%n", wait.toSeconds(), CLEAR_FROM);
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for IST midnight to pass", e);
        }
        return LocalDate.now(IST);
    }
}
