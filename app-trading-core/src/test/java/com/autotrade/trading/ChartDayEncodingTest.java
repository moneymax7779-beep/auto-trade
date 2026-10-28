package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A kept chart day reads back as the same day: the same series, so the page cannot tell it from a fresh read. */
class ChartDayEncodingTest {

    private static double[] steps(double... v) {
        double[] a = new double[OiTapeController.STEPS];
        Arrays.fill(a, Double.NaN);
        System.arraycopy(v, 0, a, 0, v.length);
        return a;
    }

    @Test
    void anOiTapeDayReadsBackWithTheSameSeriesAndSeeds() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 27));
        d.steps = 3;
        d.futExpiry = "2026-10-27";
        d.spot[0] = 22_705.6; d.spot[2] = 22_712.0;
        d.futPx[0] = 22_760.0; d.futOi[0] = 18_400_000;
        d.seedCalls.put(22_700.0, 1_000.0);
        d.seedPuts.put(22_700.0, 2_000.0);
        d.calls.put(22_700.0, steps(1_100, Double.NaN, 1_300));
        d.puts.put(22_700.0, steps(Double.NaN, 1_900, Double.NaN));
        d.calls.put(22_750.0, steps(500, 650, 700));
        d.complete = true;

        OiTapeController.Day back = OiTapeController.decode(OiTapeController.encode(d));

        assertThat(back.complete).isTrue();
        assertThat(back.date).isEqualTo(d.date);
        assertThat(back.expiry).isEqualTo(d.expiry);
        assertThat(back.futExpiry).isEqualTo("2026-10-27");
        assertThat(back.steps).isEqualTo(3);
        assertThat(back.seedCalls).isEqualTo(d.seedCalls);
        assertThat(back.seedPuts).isEqualTo(d.seedPuts);
        assertThat(back.calls.keySet()).isEqualTo(d.calls.keySet());
        assertThat(back.calls.get(22_750.0)).containsExactly(d.calls.get(22_750.0));
        assertThat(OiTapeController.series(back, back.strikeStep(), 5)).isEqualTo(OiTapeController.series(d, d.strikeStep(), 5));
    }

    @Test
    void aPcrDayReadsBackWithTheSameSeries() {
        PcrController.Day d = new PcrController.Day(LocalDate.of(2026, 10, 27), Instant.EPOCH);
        d.contracts = 64;
        d.series.add(List.of("09:15", 0.812, 41_000_000L, 50_500_000L));
        d.series.add(List.of("09:16", 0.83, 42_000_000L, 50_600_000L));
        d.complete = true;

        PcrController.Day back = PcrController.decode(PcrController.encode(d));

        assertThat(back.complete).isTrue();
        assertThat(back.expiry).isEqualTo(d.expiry);
        assertThat(back.contracts).isEqualTo(64);
        assertThat(back.series).isEqualTo(d.series);
    }

    @Test
    void anUnreadablePayloadMeansComputingTheDayAgain() {
        assertThat(OiTapeController.decode(new byte[] {1, 2, 3})).isNull();
        assertThat(PcrController.decode(ChartDb.encode(out -> out.writeInt(99)))).as("another format").isNull();
    }
}
