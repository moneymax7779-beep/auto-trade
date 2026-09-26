package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.features.bars.Bar;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.levels.LevelAcceptance;
import com.autotrade.features.volatility.VolatilityState;

class VolatilityAndLevelsTest {

    private static final Instant T0 = Instant.parse("2026-09-25T04:00:00Z");

    @Test
    void percentileCountsTiesHalf() {
        assertThat(VolatilityState.percentile(3, List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(62.5);
        assertThat(VolatilityState.percentile(Double.NaN, List.of(1.0))).isNaN();
    }

    @Test
    void realizedVolIsAnnualisedStandardDeviationOfLogReturns() {
        // returns alternate +r, -r: sample sd of 2 returns = r × sqrt(2)
        double r = Math.log(1.001);
        List<Double> closes = List.of(100.0, 100.1, 100.0);
        double expected = Math.sqrt(2 * r * r * 94500);
        assertThat(VolatilityState.realized(closes, 2, 94500)).isCloseTo(expected, within(1e-6));
        assertThat(VolatilityState.realized(closes, 3, 94500)).isNaN();
    }

    @Test
    void windowMinMaxIncludeTheValueInForceAtTheWindowStart() {
        TimedSeries series = new TimedSeries(Duration.ofMinutes(5));
        series.add(T0, 0.4);
        series.add(T0.plusSeconds(15), 0.2);
        series.add(T0.plusSeconds(25), 0.3);
        assertThat(series.min(T0.plusSeconds(30), Duration.ofSeconds(20))).isEqualTo(0.2);
        assertThat(series.max(T0.plusSeconds(30), Duration.ofSeconds(20))).isEqualTo(0.4); // in force at +10 s
        assertThat(series.min(T0.plusSeconds(30), Duration.ofSeconds(60))).isNaN(); // does not reach back
    }

    @Test
    void levelAcceptanceRecordsTheBreakTimeTravelAndMinutesBeyond() {
        LevelAcceptance orh = new LevelAcceptance(100);
        orh.update(bar(0, 99, 99.5, 98.5, 99), 0.1);
        orh.update(bar(3, 99, 102, 98.9, 101.5), 0.1); // closes through at the bar end (+6 min)
        orh.update(bar(6, 101.5, 104, 100.05, 103), 0.1); // retest near the level, holds, travels 4
        assertThat(orh.brokeUpAt()).isEqualTo(T0.plus(Duration.ofMinutes(6)));
        assertThat(orh.maxAboveAfterBreak()).isEqualTo(4);
        assertThat(orh.retestHeldAbove()).isTrue();
        assertThat(orh.minutesAbove(T0.plus(Duration.ofMinutes(9)))).isEqualTo(3);
        orh.update(bar(9, 103, 103, 99, 99.5), 0.1); // back below: no longer accepted
        assertThat(orh.minutesAbove(T0.plus(Duration.ofMinutes(12)))).isZero();
    }

    private static Bar bar(int startMinute, double open, double high, double low, double close) {
        Instant start = T0.plus(Duration.ofMinutes(startMinute));
        return new Bar(start, start.plus(Duration.ofMinutes(3)), open, high, low, close, 0, 1);
    }
}
