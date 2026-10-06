package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class BarFetcherTest {

    @Test
    void keepsTheExpiriesWhoseLastDaysOverlapTheRange() {
        List<LocalDate> expiries = List.of(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 27));
        assertThat(BarFetcher.expiriesCovering(expiries, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 6), 8))
                .containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 13));
        assertThat(BarFetcher.expiriesCovering(expiries, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 6), 40))
                .contains(LocalDate.of(2026, 10, 27));
    }

    @Test
    void keepsStrikesWithinTenStepsOfTheWeeksIndexRange() {
        List<Double> listed = new java.util.ArrayList<>();
        for (double s = 21000; s <= 24000; s += 50) {
            listed.add(s);
        }
        var keep = BarFetcher.strikesToKeep(listed, new double[] {22403.25, 22776.1}, 10);
        assertThat(keep.first()).isEqualTo(21950.0);     // 22403.25 - 10 x 50 = 21903: the first listed strike at or above
        assertThat(keep.last()).isEqualTo(23250.0);      // 22776.1 + 500 = 23276: the last at or below
        assertThat(keep).hasSize(27);
        assertThat(BarFetcher.strikesToKeep(listed, null, 10)).hasSize(listed.size());
    }
}
