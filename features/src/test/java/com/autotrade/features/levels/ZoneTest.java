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

/** features v9: the tested zone is the low of the 180 bars before the latest, with its separate touches. */
class ZoneTest {

    private static final Instant OPEN = LocalDate.of(2026, 9, 28).atTime(LocalTime.of(9, 15)).atZone(MarketTime.IST).toInstant();

    private static FeatureConfig config(String features) {
        return FeatureConfig.from(ThresholdConfig.load(Path.of("..", "config", "features", features)),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
    }

    /** One bar: opens 23015, trades down to {@code low} and up to 23020, closes {@code close}. */
    private static void minute(StructureState s, int i, double low, double close) {
        s.onSpot(OPEN.plusSeconds(60L * i + 1), 23015, null);
        s.onSpot(OPEN.plusSeconds(60L * i + 10), low, null);
        s.onSpot(OPEN.plusSeconds(60L * i + 20), 23020, null);
        s.onSpot(OPEN.plusSeconds(60L * i + 50), close, null);
    }

    @Test
    void theZoneLowAndItsSeparateTouches() {
        FeatureConfig v9 = config("features.v9.yaml");
        assertThat(v9.zoneMinutes()).isEqualTo(180);
        StructureState s = new StructureState(v9, OPEN, Optional.empty());
        for (int i = 0; i < 185; i++) {
            double low = switch (i) {
                case 10, 11 -> 23000;                        // the floor (two adjacent bars: one touch)
                case 30 -> 23001;                            // a second touch
                case 120 -> 23002;                           // a third
                default -> 23010;
            };
            minute(s, i, low, 23015);
        }
        minute(s, 185, 22998, 22999);                        // the break bar: closes below the zone
        s.onSpot(OPEN.plusSeconds(60L * 186 + 1), 22999, null);
        StructureFeatures f = s.snapshot(OPEN.plusSeconds(60L * 186 + 2), Double.NaN);
        assertThat(f.zoneLow()).isEqualTo(23000);
        assertThat(f.zoneLowTouches()).isEqualTo(3);
        assertThat(f.zoneHigh()).isEqualTo(23020);
        assertThat(f.lastMinuteClose()).isEqualTo(22999);
        assertThat(config("features.v8.yaml").zoneMinutes()).isZero();
    }
}
