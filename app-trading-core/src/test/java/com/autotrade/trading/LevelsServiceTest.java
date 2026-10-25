package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.time.SessionClock;

/**
 * Tested zones appear only from the minute their second touch is confirmed, and end at a close beyond them; prior-day
 * and period levels come only from the actual previous trading day / complete periods.
 */
class LevelsServiceTest {

    private static List<LevelsService.Bar> bars(double... closes) {
        List<LevelsService.Bar> out = new ArrayList<>();
        LocalTime t = LocalTime.of(10, 0);
        for (double c : closes) {
            out.add(new LevelsService.Bar(t, c, c + 1, c - 1, c));
            t = t.plusMinutes(1);
        }
        return out;
    }

    @Test
    void aResistanceZoneFormsAtTheSecondConfirmedTouchAndEndsWhenBroken() {
        // a high at 10:03 (100) and another at 10:11 (100.02), each with 3 lower bars either side; then a close above
        List<LevelsService.Bar> b = bars(95, 96, 97, 100, 97, 96, 95, 94, 95, 96, 97, 100.02, 97, 96, 95, 96, 98, 103);
        List<LevelsService.Zone> zones = LevelsService.zones(b);
        assertThat(zones).singleElement().satisfies(z -> {
            assertThat(z.kind()).isEqualTo("resistance");
            assertThat(z.touches()).isEqualTo(2);
            assertThat(z.from()).isEqualTo("10:14");        // the 10:11 pivot confirmed 3 minutes later
            assertThat(z.until()).isEqualTo("10:17");       // the first close above the zone
            assertThat(z.lo()).isEqualTo(101.0);
            assertThat(z.hi()).isEqualTo(101.02);
        });
    }

    @Test
    void vwapSubtractsTheTrailingMeanBasisNotTheLatestTick() {
        // futures VWAP 1,000 throughout; the tick basis jumps 50 → 90 → 50
        List<Object> v = LevelsService.smoothVwap(List.of("09:16", "09:17", "09:18"),
                List.of(950.0, 910.0, 950.0), List.of(50.0, 90.0, 50.0), 15);
        assertThat(v).containsExactly(List.of("09:16", 950.0), List.of("09:17", 930.0), List.of("09:18", 936.67));
        // only the last `window` basis values count; a missing basis falls back to the proxy
        assertThat(LevelsService.smoothVwap(List.of("a", "b", "c"), List.of(950.0, 910.0, 900.0),
                java.util.Arrays.asList(50.0, 90.0, null), 1)).containsExactly(List.of("a", 950.0), List.of("b", 910.0), List.of("c", 900.0));
    }

    @Test
    void emaStartsAfterAFullPeriod() {
        assertThat(LevelsService.ema(bars(1, 2, 3, 4, 5), 5)).hasSize(1);
        assertThat(LevelsService.ema(bars(1, 2, 3, 4), 5)).isEmpty();
    }

    @Test
    void periodLevelsFromBarSessionsUseThePreviousIsoWeekAndCalendarMonthOnly() {
        // Wednesday 7 Oct 2026: previous week = Mon 28 Sep - Fri 2 Oct, previous month = September
        List<LevelsService.DailyRange> prior = List.of(                    // newest first
                new LevelsService.DailyRange(java.time.LocalDate.of(2026, 10, 6), 999, 1, 0, 0, 0),   // this week: ignored
                new LevelsService.DailyRange(java.time.LocalDate.of(2026, 10, 1), 120, 90, 0, 0, 0),
                new LevelsService.DailyRange(java.time.LocalDate.of(2026, 9, 28), 110, 80, 0, 0, 0),
                new LevelsService.DailyRange(java.time.LocalDate.of(2026, 9, 25), 130, 85, 0, 0, 0),
                new LevelsService.DailyRange(java.time.LocalDate.of(2026, 8, 31), 500, 2, 0, 0, 0)); // August: ignored
        List<LevelsService.Level> levels = new ArrayList<>();
        LevelsService.periodLevels(java.time.LocalDate.of(2026, 10, 7), new java.util.HashMap<>(), prior, d -> false, levels);
        assertThat(levels).extracting(LevelsService.Level::name, LevelsService.Level::price).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PWH", 120.0), org.assertj.core.groups.Tuple.tuple("PWL", 80.0),
                org.assertj.core.groups.Tuple.tuple("PMH", 130.0), org.assertj.core.groups.Tuple.tuple("PML", 80.0));
    }

    // ------------------------------------------------------------------ prior sessions without live features

    private static MarketHours calendar() throws Exception {
        FeatureConfig config = FeatureConfig.from(
                ThresholdConfig.load(Path.of("..", "config", "features", "features.v12.yaml")),
                ThresholdConfig.load(Path.of("..", "config", "exchange", "nse-bse-sessions.v3.yaml")));
        return new MarketHours(new SessionClock(config), config.holidays());
    }

    private static LevelsService.DailyRange range(LocalDate day, double high, double low) {
        return new LevelsService.DailyRange(day, high, low, (high + low) / 2, high, low);
    }

    @Test
    void theOwnCaptureWinsOverTheBarsOnADateBothHoldAndSessionsAreNewestFirst() {
        List<LevelsService.DailyRange> merged = LevelsService.merge(
                List.of(range(LocalDate.of(2026, 10, 21), 23120.9, 22649.95), range(LocalDate.of(2026, 10, 6), 22731.35, 22561.8)),
                List.of(range(LocalDate.of(2026, 10, 6), 22776.1, 22558.3), range(LocalDate.of(2026, 10, 5), 22621.8, 22397.5)));
        assertThat(merged).extracting(LevelsService.DailyRange::day, LevelsService.DailyRange::high).containsExactly(
                tuple(LocalDate.of(2026, 10, 21), 23120.9), tuple(LocalDate.of(2026, 10, 6), 22731.35),
                tuple(LocalDate.of(2026, 10, 5), 22621.8));
    }

    @Test
    void aBarSessionWeeksOldIsNotThePreviousDay() throws Exception {
        MarketHours hours = calendar();
        LocalDate day = LocalDate.of(2026, 10, 22);
        // session 14723: the bars end 6 Oct; without the own capture's 21 Oct, the chart drew 6 Oct as PDH (22,776.10)
        List<LevelsService.DailyRange> barsOnly = List.of(range(LocalDate.of(2026, 10, 6), 22776.1, 22558.3));
        assertThat(LevelsService.previousSession(day, barsOnly, hours::knownTradingDay)).isNull();
        List<LevelsService.DailyRange> withOwn = LevelsService.merge(
                List.of(range(LocalDate.of(2026, 10, 21), 23120.9, 22649.95)), barsOnly);
        assertThat(LevelsService.previousSession(day, withOwn, hours::knownTradingDay).high()).isEqualTo(23120.9);

        List<LevelsService.Level> levels = new ArrayList<>();
        LevelsService.twoDay(withOwn, withOwn.getFirst(), 23120.9, 22649.95, hours::knownTradingDay, levels);
        assertThat(levels).as("6 Oct is not the session before 21 Oct").isEmpty();
    }

    @Test
    void holidaysAndWeekendsDoNotBreakThePreviousDay() throws Exception {
        MarketHours hours = calendar();
        List<LevelsService.DailyRange> prior = List.of(range(LocalDate.of(2026, 10, 19), 23174.5, 22845.25),
                range(LocalDate.of(2026, 10, 16), 23149.6, 22914.3));
        // Wed 21 Oct after the Tue 20 Oct Dussehra holiday; the 2-day range spans the weekend before
        LevelsService.DailyRange previous = LevelsService.previousSession(LocalDate.of(2026, 10, 21), prior, hours::knownTradingDay);
        assertThat(previous.day()).isEqualTo(LocalDate.of(2026, 10, 19));
        List<LevelsService.Level> levels = new ArrayList<>();
        LevelsService.twoDay(prior, previous, previous.high(), previous.low(), hours::knownTradingDay, levels);
        assertThat(levels).extracting(LevelsService.Level::name, LevelsService.Level::price)
                .containsExactly(tuple("2-day high", 23174.5), tuple("2-day low", 22845.25));
        // a missing trading day (no capture on Tue 13 Oct) means no previous-day levels on Wed 14 Oct
        assertThat(LevelsService.previousSession(LocalDate.of(2026, 10, 14),
                List.of(range(LocalDate.of(2026, 10, 12), 1, 1)), hours::knownTradingDay)).isNull();
    }

    @Test
    void yearsTheCalendarDoesNotListTrustTheBarsButNotBeyondTenDays() throws Exception {
        MarketHours hours = calendar();                  // lists 2026 holidays only
        assertThat(hours.knownTradingDay(LocalDate.of(2025, 3, 14))).isFalse();
        // Mon 17 Mar 2025 after Holi (Fri 14 Mar, not in the file): the bars' Thu 13 Mar is the previous day
        assertThat(LevelsService.previousSession(LocalDate.of(2025, 3, 17),
                List.of(range(LocalDate.of(2025, 3, 13), 1, 1)), hours::knownTradingDay)).isNotNull();
        assertThat(LevelsService.previousSession(LocalDate.of(2025, 3, 17),
                List.of(range(LocalDate.of(2025, 3, 6), 1, 1)), hours::knownTradingDay)).isNull();
    }

    @Test
    void aPeriodWithAMissingTradingDayIsLeftOut() throws Exception {
        MarketHours hours = calendar();
        // Thu 22 Oct 2026: previous week 12-16 Oct, previous month September (complete in the bars)
        List<LevelsService.DailyRange> prior = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 10, 16); !d.isBefore(LocalDate.of(2026, 9, 1)); d = d.minusDays(1)) {
            if (hours.knownTradingDay(d) && !d.equals(LocalDate.of(2026, 10, 14))) {     // 14 Oct missing
                prior.add(range(d, d.getMonthValue() * 100 + d.getDayOfMonth(), d.getDayOfMonth()));
            }
        }
        List<LevelsService.Level> levels = new ArrayList<>();
        LevelsService.periodLevels(LocalDate.of(2026, 10, 22), new java.util.HashMap<>(), prior, hours::knownTradingDay, levels);
        assertThat(levels).extracting(LevelsService.Level::name, LevelsService.Level::price)
                .containsExactly(tuple("PMH", 930.0), tuple("PML", 1.0));
    }

    @Test
    void aSessionCountsOnlyWhenItsDataCoversTheContinuousSession() {
        java.time.LocalTime t0915 = java.time.LocalTime.of(9, 15), t1529 = java.time.LocalTime.of(15, 29);
        org.assertj.core.api.Assertions.assertThat(LevelsService.covers(t0915, t1529)).isTrue();
        org.assertj.core.api.Assertions.assertThat(LevelsService.covers(java.time.LocalTime.of(11, 0), t1529))
                .as("capture started late after a restart").isFalse();
        org.assertj.core.api.Assertions.assertThat(LevelsService.covers(t0915, java.time.LocalTime.of(13, 0)))
                .as("capture stopped at 13:00").isFalse();
        org.assertj.core.api.Assertions.assertThat(LevelsService.covers(null, null)).isFalse();
    }
}
