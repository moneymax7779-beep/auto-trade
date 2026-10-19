package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The OI tape's per-step arithmetic: carried OI, the band around the money, flow only from strikes seen twice. */
class OiTapeSeriesTest {

    private static OiTapeController.Day day() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19));
        d.steps = 4;
        d.spot[0] = 23_010; d.spot[1] = 23_020; d.spot[3] = 23_030;            // step 2 has no index tick: carried
        d.seedCalls.put(23_000.0, 1_000.0);
        d.seedPuts.put(23_000.0, 2_000.0);
        d.calls.put(23_000.0, steps(1_100, Double.NaN, 1_300, Double.NaN));    // published in steps 0 and 2
        d.puts.put(23_000.0, steps(Double.NaN, 1_900, Double.NaN, Double.NaN));
        d.calls.put(22_950.0, steps(Double.NaN, Double.NaN, 500, 650));        // first recorded in step 2: no flow then
        d.calls.put(23_600.0, steps(10, 20, 30, 40));                          // beyond ATM ± 10 strikes: left out
        return d;
    }

    private static double[] steps(double... v) {
        double[] a = new double[OiTapeController.STEPS];
        java.util.Arrays.fill(a, Double.NaN);
        System.arraycopy(v, 0, a, 0, v.length);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> row(List<Object> series, int i) {
        return (List<Object>) series.get(i);
    }

    @Test
    void flowCountsOnlyStrikesWithOiInBothStepsInsideTheBand() {
        List<Object> s = OiTapeController.series(day(), 50);
        assertThat(s).hasSize(4);
        // [time, spot, futPx, futOi, atm, ce, pe, dce, dpe, pcr]
        assertThat(row(s, 0)).containsExactly("09:15:00", 23_010.0, null, null, 23_000.0, 1_100L, 2_000L, 100L, 0L, 1.818);
        assertThat(row(s, 1).subList(5, 9)).containsExactly(1_100L, 1_900L, 0L, -100L);
        // step 2: the 22,950 call appears (500 added to the band's OI, none to its flow); the 23,000 call +200
        assertThat(row(s, 2).get(1)).isEqualTo(23_020.0);
        assertThat(row(s, 2).subList(5, 9)).containsExactly(1_800L, 1_900L, 200L, 0L);
        // step 3: the 22,950 call +150 counts now
        assertThat(row(s, 3).subList(5, 9)).containsExactly(1_950L, 1_900L, 150L, 0L);
    }

    @Test
    void stepIndexAndLabel() {
        assertThat(OiTapeController.index(LocalTime.of(9, 15))).isZero();
        assertThat(OiTapeController.index(LocalTime.of(9, 34, 29))).isEqualTo(38);
        assertThat(OiTapeController.time(38)).isEqualTo("09:34:00");
        assertThat(OiTapeController.time(39)).isEqualTo("09:34:30");
        assertThat(OiTapeController.STEPS).isEqualTo(750);
    }
}
