package com.autotrade.features.levels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.autotrade.features.bars.Bar;

class RetestTrackerTest {

    private static final Instant T0 = Instant.parse("2026-09-22T05:00:00Z");
    private int bars;

    @Test
    void upwardBreakRetestAndHold() {
        RetestTracker orh = new RetestTracker(100, true);
        orh.update(bar(98, 99.5, 97.5, 99), 1);
        assertThat(orh.state()).isEqualTo(RetestTracker.State.NONE);
        orh.update(bar(99, 104, 98.8, 103.5), 1);          // closes through
        assertThat(orh.state()).isEqualTo(RetestTracker.State.BROKEN);
        orh.update(bar(103.5, 104.5, 100.6, 101), 1);      // comes back within the band, holds
        assertThat(orh.state()).isEqualTo(RetestTracker.State.RETESTING);
        orh.update(bar(101, 101.8, 100.3, 101.2), 1);      // deeper touch: new reference, still retesting
        assertThat(orh.state()).isEqualTo(RetestTracker.State.RETESTING);
        assertThat(orh.touchBar().low()).isEqualTo(100.3);
        orh.update(bar(101.2, 101.5, 101.0, 101.1), 1);    // higher low but closes lower: not yet resumed
        assertThat(orh.state()).isEqualTo(RetestTracker.State.RETESTING);
        orh.update(bar(101.2, 103, 100.9, 102.5), 1);      // higher low, close above the touch close, upper half
        assertThat(orh.state()).isEqualTo(RetestTracker.State.HELD);
        assertThat(orh.breakBar().close()).isEqualTo(103.5);
    }

    @Test
    void aCloseBackThroughByMoreThanTheBandFails() {
        RetestTracker orh = new RetestTracker(100, true);
        orh.update(bar(98, 99.5, 97.5, 99), 1);
        orh.update(bar(99, 102, 98.8, 101.5), 1);
        orh.update(bar(101.5, 101.8, 98.5, 98.9), 1);      // close 1.1 below the level, band 1
        assertThat(orh.state()).isEqualTo(RetestTracker.State.FAILED);
    }

    @Test
    void downwardBreakIsTheMirror() {
        RetestTracker orl = new RetestTracker(100, false);
        orl.update(bar(102, 102.5, 100.5, 101), 1);
        orl.update(bar(101, 101.2, 96, 96.5), 1);          // closes through downward
        assertThat(orl.state()).isEqualTo(RetestTracker.State.BROKEN);
        orl.update(bar(96.5, 99.4, 96.2, 99), 1);          // back up within the band
        assertThat(orl.state()).isEqualTo(RetestTracker.State.RETESTING);
        orl.update(bar(99, 99.2, 97, 97.5), 1);            // lower high, close below the touch close, lower half
        assertThat(orl.state()).isEqualTo(RetestTracker.State.HELD);
    }

    private Bar bar(double open, double high, double low, double close) {
        Instant start = T0.plus(Duration.ofMinutes(3L * bars++));
        return new Bar(start, start.plus(Duration.ofMinutes(3)), open, high, low, close, 0, 1);
    }
}
