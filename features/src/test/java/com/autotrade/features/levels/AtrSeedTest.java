package com.autotrade.features.levels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.config.FeatureConfig;

/** features v7: the 3-minute ATR is ready at the open because it is seeded from the previous session. */
class AtrSeedTest {

    private static FeatureConfig config(String features) {
        return FeatureConfig.from(ThresholdConfig.load(Path.of("..", "config", "features", features)),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
    }

    private static Instant at(LocalDate day, String hm) {
        return day.atTime(LocalTime.parse(hm)).atZone(MarketTime.IST).toInstant();
    }

    /** 60 one-minute bars 09:15-10:14 of the previous session: each 3-minute bar has range 6, closes step by 1. */
    private static List<MinuteBar> previous(LocalDate day) {
        List<MinuteBar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double base = 23000 + i / 3;                  // flat inside each 3-minute bar
            bars.add(new MinuteBar(at(day, "09:15").plusSeconds(60L * i), base, base + 3, base - 3, base));
        }
        return bars;
    }

    @Test
    void v7IsSeededFromThePreviousSessionAndV6IsNot() {
        LocalDate today = LocalDate.of(2026, 9, 28);
        LocalDate yesterday = LocalDate.of(2026, 9, 25);
        FeatureConfig v7 = config("features.v7.yaml");
        FeatureConfig v6 = config("features.v6.yaml");
        assertThat(v7.atrSeedPreviousSession()).isTrue();
        assertThat(v6.atrSeedPreviousSession()).isFalse();

        StructureState seeded = new StructureState(v7, at(today, "09:15"), Optional.empty());
        seeded.seedAtr(previous(yesterday));
        // 20 three-minute bars: the first true range is its range (6); the others span the previous close
        // (base-1) to base+3 and down to base-3, i.e. 6 as well, so Wilder's ATR is exactly 6.
        assertThat(seeded.atr3m()).isCloseTo(6.0, within(1e-9));

        StructureState unseeded = new StructureState(v6, at(today, "09:15"), Optional.empty());
        assertThat(unseeded.atr3m()).isNaN();

        seeded.seedAtr(List.of());                        // no previous session: nothing changes
        assertThat(seeded.atr3m()).isCloseTo(6.0, within(1e-9));
    }
}
