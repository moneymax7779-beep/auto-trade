package com.autotrade.features.levels;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.snapshot.StructureFeatures;

/** features v8: the trailing box is the 90 one-minute bars before the latest closed one. */
class BoxTest {

    private static FeatureConfig config(String features) {
        return FeatureConfig.from(ThresholdConfig.load(Path.of("..", "config", "features", features)),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
    }

    private static final Instant OPEN = LocalDate.of(2026, 9, 28).atTime(LocalTime.of(9, 15)).atZone(MarketTime.IST).toInstant();

    private static void minute(StructureState s, int i, double first, double second) {
        s.onSpot(OPEN.plusSeconds(60L * i + 1), first, null);
        s.onSpot(OPEN.plusSeconds(60L * i + 30), second, null);
    }

    @Test
    void theBoxExcludesTheLatestBarAndV7HasNone() {
        FeatureConfig v8 = config("features.v8.yaml");
        assertThat(v8.boxMinutes()).isEqualTo(90);
        assertThat(config("features.v7.yaml").boxMinutes()).isZero();
        StructureState s = new StructureState(v8, OPEN, Optional.empty());
        minute(s, 0, 23100, 23005);                         // outside the window by the end
        for (int i = 1; i <= 90; i++) {
            minute(s, i, 23000, 23010);                     // the box: 23000-23010
        }
        minute(s, 91, 23001, 22990);                        // the break bar, closing below the box
        s.onSpot(OPEN.plusSeconds(60L * 92 + 1), 22991, null);   // closes bar 91
        StructureFeatures f = s.snapshot(OPEN.plusSeconds(60L * 92 + 2), Double.NaN);
        assertThat(f.boxHigh()).isEqualTo(23010);
        assertThat(f.boxLow()).isEqualTo(23000);
        assertThat(f.lastMinuteClose()).isEqualTo(22990);

        StructureState off = new StructureState(config("features.v7.yaml"), OPEN, Optional.empty());
        minute(off, 0, 23000, 23001);
        off.onSpot(OPEN.plusSeconds(61), 23002, null);
        assertThat(off.snapshot(OPEN.plusSeconds(62), Double.NaN).boxHigh()).isNaN();
    }
}
