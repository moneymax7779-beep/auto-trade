package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The open-interest reading behind the surge panel, checked on the 29 Sep NIFTY 10:07 and 12:30 minutes. */
class SurgeFlowTest {

    @Test
    void futuresReadOiWithPrice() {
        assertThat(SurgeFlow.futures(22603, 8_341_905, 22609.9, 8_633_040)).isEqualTo(new SurgeFlow.Reading("long build", 1));
        assertThat(SurgeFlow.futures(22690, 7_846_995, 22645, 7_853_365).label()).isEqualTo("flat");   // +0.08 %
        assertThat(SurgeFlow.futures(100, 1000, 99, 1010)).isEqualTo(new SurgeFlow.Reading("short build", -1));
        assertThat(SurgeFlow.futures(100, 1000, 101, 990)).isEqualTo(new SurgeFlow.Reading("short covering", 1));
        assertThat(SurgeFlow.futures(100, 1000, 99, 990)).isEqualTo(new SurgeFlow.Reading("long unwinding", -1));
    }

    @Test
    void callsAndPutsReadOppositeWays() {
        // 12:30: call OI up while calls fall = writing (bearish); put OI down while puts rise = short covering (bearish)
        assertThat(SurgeFlow.options(true, 100, 120, 74, 46)).isEqualTo(new SurgeFlow.Reading("writing", -1));
        assertThat(SurgeFlow.options(false, 100, 80, 40, 60)).isEqualTo(new SurgeFlow.Reading("short covering", -1));
        // 10:07: call OI up with calls up = buying (bullish); put OI down with puts down = long unwinding (bullish)
        assertThat(SurgeFlow.options(true, 100, 102.3, 64.20, 64.45)).isEqualTo(new SurgeFlow.Reading("buying", 1));
        assertThat(SurgeFlow.options(false, 100, 98.4, 65.55, 62.62)).isEqualTo(new SurgeFlow.Reading("long unwinding", 1));
        assertThat(SurgeFlow.options(false, 100, 110, 60, 50)).isEqualTo(new SurgeFlow.Reading("writing", 1));
        assertThat(SurgeFlow.options(true, 100, 100.5, 60, 70).label()).isEqualTo("flat");        // under 1 %
    }

    @Test
    void twoAgreeingAndNoneAgainst() {
        assertThat(SurgeFlow.overall(List.of(1, 1, 1))).isEqualTo("bullish");
        assertThat(SurgeFlow.overall(List.of(0, -1, -1))).isEqualTo("bearish");
        assertThat(SurgeFlow.overall(List.of(1, 1, -1))).isEqualTo("mixed");
        assertThat(SurgeFlow.overall(List.of(1, 0, 0))).isEqualTo("mixed");
    }
}
