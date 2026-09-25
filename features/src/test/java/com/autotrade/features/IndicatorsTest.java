package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.autotrade.features.bars.Bar;
import com.autotrade.features.bars.BarSeries;
import com.autotrade.features.indicators.Ema;
import com.autotrade.features.indicators.SwingTracker;
import com.autotrade.features.indicators.TimedSeries;
import com.autotrade.features.indicators.WilderAtr;

class IndicatorsTest {

    private static final Instant OPEN = Instant.parse("2026-09-25T03:45:00Z"); // 09:15 IST

    @Test
    void emaSeedsWithSimpleAverageThenSmooths() {
        Ema ema = new Ema(3, 5);
        ema.update(1);
        ema.update(2);
        assertThat(ema.ready()).isFalse();
        ema.update(3);
        assertThat(ema.value()).isEqualTo(2.0);
        ema.update(6);
        assertThat(ema.value()).isEqualTo(4.0); // alpha 0.5
        assertThat(ema.slope(1)).isEqualTo(2.0);
    }

    @Test
    void wilderAtrUsesTrueRange() {
        WilderAtr atr = new WilderAtr(2);
        atr.update(bar(0, 10, 12, 9, 11));
        atr.update(bar(1, 11, 15, 11, 14)); // TR = max(15,11) - min(11,11) = 4
        assertThat(atr.value()).isEqualTo((3 + 4) / 2.0);
        atr.update(bar(2, 14, 14, 8, 9)); // TR = 14 - 8 = 6
        assertThat(atr.value()).isEqualTo((3.5 * 1 + 6) / 2);
    }

    @Test
    void threeMinuteBarsAlignToTheSessionOpenAndCloseOnTime() {
        BarSeries series = new BarSeries(Duration.ofMinutes(3), OPEN, 10);
        series.update(OPEN.plusSeconds(10), 100, 5);
        series.update(OPEN.plusSeconds(170), 104, 5);
        series.update(OPEN.plusSeconds(100), 98, 0);
        assertThat(series.closed()).isEmpty();

        series.advanceTo(OPEN.plusSeconds(180));

        Bar bar = series.lastClosed();
        assertThat(bar.start()).isEqualTo(OPEN);
        assertThat(bar.end()).isEqualTo(OPEN.plusSeconds(180));
        assertThat(bar.open()).isEqualTo(100);
        assertThat(bar.high()).isEqualTo(104);
        assertThat(bar.low()).isEqualTo(98);
        assertThat(bar.close()).isEqualTo(98);
        assertThat(bar.volume()).isEqualTo(10);
        assertThat(series.bucketStart(OPEN.plusSeconds(400))).isEqualTo(OPEN.plusSeconds(360));
    }

    @Test
    void timedSeriesLooksBackAndReportsMissingHistory() {
        TimedSeries series = new TimedSeries(Duration.ofMinutes(10));
        series.add(OPEN, 100);
        series.add(OPEN.plusSeconds(30), 103);
        series.add(OPEN.plusSeconds(90), 110);

        assertThat(series.valueAt(OPEN.plusSeconds(60))).isEqualTo(103);
        assertThat(series.change(OPEN.plusSeconds(90), Duration.ofSeconds(60))).isEqualTo(7);
        assertThat(series.valueAt(OPEN.minusSeconds(1))).isNaN();
    }

    @Test
    void swingsAreConfirmedOnlyAfterTheRightSideBars() {
        SwingTracker swings = new SwingTracker(2);
        double[] lows = {10, 9, 8, 9, 10, 9.5, 9, 10, 11};
        for (int i = 0; i < lows.length; i++) {
            swings.update(bar(i, lows[i] + 1, lows[i] + 2, lows[i], lows[i] + 1));
            if (i == 3) {
                assertThat(swings.lastLow()).isNull(); // pivot at bar 2 not yet confirmed
            }
        }
        assertThat(swings.lastLow().price()).isEqualTo(9.0);
        assertThat(swings.higherLows(2)).isTrue();
    }

    @Test
    void barShapeMeasures() {
        Bar bar = bar(0, 100, 110, 95, 108);
        assertThat(bar.closeLocation()).isCloseTo(13.0 / 15, within(1e-9));
        assertThat(bar.upperWick()).isEqualTo(2);
        assertThat(bar.body()).isEqualTo(8);
    }

    private static Bar bar(int index, double open, double high, double low, double close) {
        Instant start = OPEN.plusSeconds(180L * index);
        return new Bar(start, start.plusSeconds(180), open, high, low, close, 0, 1);
    }
}
