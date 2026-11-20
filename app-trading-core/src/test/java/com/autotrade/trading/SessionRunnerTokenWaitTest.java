package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** The 07:30 start waits for zt-tiger-v2's Upstox token until 09:00 (user 20 Nov 2026). */
class SessionRunnerTokenWaitTest {

    @Test
    void anEarlyUpstoxStartWaitsForTheTokenOnlyBeforeNine() {
        assertThat(SessionRunner.waitsForToken("upstox", LocalTime.of(7, 30), false)).isTrue();
        assertThat(SessionRunner.waitsForToken("upstox", LocalTime.of(8, 59, 59), false)).isTrue();
        assertThat(SessionRunner.waitsForToken("upstox", LocalTime.of(9, 0), false)).isFalse();   // starts as before (zt tail)
        assertThat(SessionRunner.waitsForToken("upstox", LocalTime.of(11, 0), false)).isFalse();  // a restart in market hours
    }

    @Test
    void aSeenTokenOrAnotherFeedNeverWaits() {
        assertThat(SessionRunner.waitsForToken("upstox", LocalTime.of(7, 30), true)).isFalse();
        assertThat(SessionRunner.waitsForToken("zt", LocalTime.of(7, 30), false)).isFalse();
        assertThat(SessionRunner.waitsForToken("replay", LocalTime.of(7, 30), false)).isFalse();
    }
}
