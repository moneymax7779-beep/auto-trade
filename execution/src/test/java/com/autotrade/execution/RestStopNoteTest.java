package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** User 25 Nov 2026: a partial exit's line says what the rest's broker stop was left as, and only a failed one says NOT. */
class RestStopNoteTest {

    @Test
    void aStopPlacedButNotYetInTheBookIsSaidAsPlaced() {
        String note = FollowerSimulator.restStopNote(true, FollowerSimulator.RestStop.PLACED_UNSEEN);
        assertThat(note).startsWith(" with a new broker stop").contains("placed").doesNotContainIgnoringCase("not placed")
                .doesNotContain("no new entries");
    }

    @Test
    void onlyAFailedStopSaysNotPlacedAndThatEntriesWait() {
        assertThat(FollowerSimulator.restStopNote(true, FollowerSimulator.RestStop.FAILED))
                .contains("NOT placed yet").contains("no new entries");
        assertThat(FollowerSimulator.restStopNote(true, FollowerSimulator.RestStop.NOT_CONFIRMED))
                .contains("not confirmed yet").doesNotContain("no new entries");
        assertThat(FollowerSimulator.restStopNote(true, FollowerSimulator.RestStop.ARMED)).isEqualTo(" with a new broker stop");
    }

    @Test
    void aSimulatedVenueSaysNothingOfAStop() {
        for (FollowerSimulator.RestStop s : FollowerSimulator.RestStop.values()) {
            assertThat(FollowerSimulator.restStopNote(false, s)).isEmpty();
        }
    }
}
