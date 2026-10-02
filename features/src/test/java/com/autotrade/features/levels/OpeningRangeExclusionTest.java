package com.autotrade.features.levels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureConfig;

/** features v10: the breakout bar's range is compared only with 3-minute bars after the opening range. */
class OpeningRangeExclusionTest {

    private static final Instant OPEN = LocalDate.of(2026, 9, 29).atTime(LocalTime.of(9, 15)).atZone(MarketTime.IST).toInstant();

    private static FeatureConfig config(String features) {
        return FeatureConfig.from(ThresholdConfig.load(Path.of("..", "config", "features", features)),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
    }

    /** A 3-minute bar from minute {@code m}: trades {@code low}..{@code low + range}. */
    private static void bar(StructureState s, int m, double low, double range) {
        s.onSpot(OPEN.plusSeconds(60L * m + 1), low + range / 2, null);
        s.onSpot(OPEN.plusSeconds(60L * m + 20), low, null);
        s.onSpot(OPEN.plusSeconds(60L * m + 70), low + range, null);
        s.onSpot(OPEN.plusSeconds(60L * m + 170), low + range / 2, null);
    }

    private static double rangeVsAvg(String features) {
        StructureState s = new StructureState(config(features), OPEN, Optional.empty());
        for (int m = 0; m < 15; m += 3) {
            bar(s, m, 22700 - 20 * m, 60);                 // five huge gap-open bars (range 60)
        }
        bar(s, 15, 22600, 10);                             // 09:30 bar, range 10
        bar(s, 18, 22590, 20);                             // 09:33 breakout bar, range 20
        s.onSpot(OPEN.plusSeconds(60L * 21 + 1), 22595, null);   // closes it
        return s.snapshot(OPEN.plusSeconds(60L * 21 + 2), Double.NaN).lastBarRangeVsAvg();
    }

    @Test
    void theGapOpenBarsNoLongerDwarfTheBreakout() {
        assertThat(config("features.v10.yaml").breakoutExcludesOpeningRange()).isTrue();
        assertThat(config("features.v7.yaml").breakoutExcludesOpeningRange()).isFalse();
        assertThat(config("features.v7.yaml").vwapBasisMinutes()).isZero();
        assertThat(config("features.v11.yaml").vwapBasisMinutes()).isEqualTo(15);
        assertThat(config("features.v11.yaml").breakoutExcludesOpeningRange()).isFalse();
        assertThat(rangeVsAvg("features.v7.yaml")).isCloseTo(20.0 / ((5 * 60 + 10) / 6.0), within(1e-9));   // 0.39
        assertThat(rangeVsAvg("features.v10.yaml")).isCloseTo(2.0, within(1e-9));                          // 20 / 10
    }
}
