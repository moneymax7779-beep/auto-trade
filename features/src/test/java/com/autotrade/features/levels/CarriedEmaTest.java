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
import com.autotrade.core.history.DailyBar;
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
        var today0 = state.snapshot(at(today, "09:15"), Double.NaN);
        assertThat(today0.ema9()).isNaN();                // review 22 Nov 2026: today's EMAs and swings stay unseeded
        assertThat(today0.ema20()).isNaN();
        assertThat(today0.lastSwingLow()).isNaN();
        assertThat(today0.lastSwingHigh()).isNaN();
        assertThat(today0.carriedEma20()).isCloseTo(23009.5, within(1e-9));

        state.carryPrevious(List.of());                   // no previous session: nothing changes
        assertThat(state.carriedEma20()).isCloseTo(23009.5, within(1e-9));
    }

    /** Review 22 Nov 2026: today's 3-minute closes keep moving the carried averages. */
    @Test
    void todaysClosesKeepUpdatingTheCarriedAverages() {
        FeatureConfig config = FeatureConfig.from(ThresholdConfig.load(Path.of("..", "config", "features", "features.v15.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
        LocalDate today = LocalDate.of(2026, 9, 28);
        LocalDate yesterday = LocalDate.of(2026, 9, 25);
        List<MinuteBar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            bars.add(new MinuteBar(at(yesterday, "09:15").plusSeconds(60L * i), 23000, 23003, 22997, 23000));
        }
        StructureState state = new StructureState(config, at(today, "09:15"), Optional.empty());
        state.carryPrevious(bars);
        double before = state.carriedEma9();
        for (int s = 0; s <= 7 * 60; s += 5) {            // today trades 23100 for seven minutes: two 3-minute closes
            state.onSpot(at(today, "09:15").plusSeconds(s), 23100, null);
        }
        assertThat(state.carriedEma9()).isGreaterThan(before);
    }

    /** Review 22 Nov 2026: bars of another day than the previous session (a missed capture) are not carried. */
    @Test
    void barsOfAnotherDayThanThePreviousSessionAreNotCarried() {
        FeatureConfig config = FeatureConfig.from(ThresholdConfig.load(Path.of("..", "config", "features", "features.v15.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
        LocalDate today = LocalDate.of(2026, 9, 28);
        LocalDate older = LocalDate.of(2026, 9, 24);     // the capture's newest day; the previous session was 25 Sep
        List<MinuteBar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            bars.add(new MinuteBar(at(older, "09:15").plusSeconds(60L * i), 23000, 23003, 22997, 23000));
        }
        StructureState state = new StructureState(config, at(today, "09:15"),
                Optional.of(new DailyBar(LocalDate.of(2026, 9, 25), 23000, 23100, 22900, 23050)));
        state.carryPrevious(bars);
        assertThat(state.carriedEma9()).isNaN();
        assertThat(state.carriedEma20()).isNaN();
    }
}
