package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.features.bars.Bar;
import com.autotrade.features.futures.OiState;
import com.autotrade.features.futures.VolumeProfile;
import com.autotrade.features.levels.LevelAcceptance;

class LevelsAndStatesTest {

    private static final Instant OPEN = Instant.parse("2026-09-25T03:45:00Z");

    @Test
    void levelAcceptanceCountsClosesAndRetest() {
        LevelAcceptance orh = new LevelAcceptance(23116);
        orh.update(bar(0, 23100, 23110, 23095, 23108), 2);
        orh.update(bar(1, 23108, 23125, 23105, 23122), 2); // break up
        orh.update(bar(2, 23122, 23130, 23117, 23128), 2); // retest into band, holds
        assertThat(orh.closesAbove()).isEqualTo(2);
        assertThat(orh.retestHeldAbove()).isTrue();
        assertThat(orh.maxAboveAfterBreak()).isEqualTo(14);

        orh.update(bar(3, 23128, 23129, 23105, 23110), 2); // back below
        assertThat(orh.closesAbove()).isZero();
        assertThat(orh.closesBelow()).isEqualTo(1);
        assertThat(orh.retestHeldAbove()).isFalse();
    }

    @Test
    void oiStateFollowsPriceAndOiDirection() {
        assertThat(OiState.classify(10, 5000, 1, 100)).isEqualTo(OiState.FRESH_LONG);
        assertThat(OiState.classify(10, -5000, 1, 100)).isEqualTo(OiState.SHORT_COVERING);
        assertThat(OiState.classify(-10, 5000, 1, 100)).isEqualTo(OiState.FRESH_SHORT);
        assertThat(OiState.classify(-10, -5000, 1, 100)).isEqualTo(OiState.LONG_UNWIND);
        assertThat(OiState.classify(0.5, 5000, 1, 100)).isEqualTo(OiState.NEUTRAL);
        assertThat(OiState.SHORT_COVERING.longScore()).isEqualTo(1);
    }

    @Test
    void volumeProfileTakesMedianOfCompleteSessions() {
        LocalTime a = LocalTime.of(14, 48);
        LocalTime b = LocalTime.of(14, 49);
        VolumeProfile profile = new VolumeProfile(List.of(
                Map.of(a, 10L, b, 10L), Map.of(a, 30L, b, 30L), Map.of(a, 50L, b, 50L), Map.of(a, 1000L)));

        VolumeProfile.SlotMedian median = profile.slotMedian(List.of(a, b));

        assertThat(median.sessions()).isEqualTo(3);
        assertThat(median.median()).isEqualTo(60.0);
    }

    private static Bar bar(int index, double open, double high, double low, double close) {
        Instant start = OPEN.plusSeconds(180L * index);
        return new Bar(start, start.plusSeconds(180), open, high, low, close, 0, 1);
    }
}
