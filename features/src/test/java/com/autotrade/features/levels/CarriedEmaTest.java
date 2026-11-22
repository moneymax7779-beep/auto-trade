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

/** A-537: EMAs carried over from the previous session exist at the open; today's EMAs are untouched. */
class CarriedEmaTest {

    private static Instant at(LocalDate day, String hm) {
        return day.atTime(LocalTime.parse(hm)).atZone(MarketTime.IST).toInstant();
    }

    @Test
    void previousSessionSeedsOnlyTheCarriedAverages() {
        FeatureConfig config = FeatureConfig.from(ThresholdConfig.load(Path.of("..", "config", "features", "features.v15.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
        LocalDate today = LocalDate.of(2026, 9, 28);
        LocalDate yesterday = LocalDate.of(2026, 9, 25);
        List<MinuteBar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {                    // 20 three-minute bars closing 23000, 23001, ..., 23019
            double base = 23000 + i / 3;
            bars.add(new MinuteBar(at(yesterday, "09:15").plusSeconds(60L * i), base, base + 3, base - 3, base));
        }
        StructureState state = new StructureState(config, at(today, "09:15"), Optional.empty());
        state.carryPrevious(bars);
        assertThat(state.carriedEma20()).isCloseTo(23009.5, within(1e-9));   // seeded with the mean of 20 closes
        assertThat(state.carriedEma9()).isGreaterThan(state.carriedEma20());   // rising closes: fast above slow
        assertThat(state.atr3m()).isNaN();                // today's indicators are not fed by carryPrevious

        state.carryPrevious(List.of());                   // no previous session: nothing changes
        assertThat(state.carriedEma20()).isCloseTo(23009.5, within(1e-9));
    }
}
