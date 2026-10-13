package com.autotrade.features.levels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.time.MarketTime;

/** Features v12 (A-090): previous ISO week's and previous calendar month's high / low, never today. */
class PeriodLevelsTest {

    private static List<MinuteBar> day(LocalDate d, double high, double low) {
        return List.of(new MinuteBar(d.atTime(10, 0).atZone(MarketTime.IST).toInstant(), low, high, low, high));
    }

    @Test
    void previousWeekAndMonthOfTuesday13October() {
        LocalDate session = LocalDate.of(2026, 10, 13);                 // Tuesday; its week starts Mon 12 Oct
        List<List<MinuteBar>> history = List.of(
                day(LocalDate.of(2026, 10, 12), 999, 1),                 // this week: not the previous week
                day(LocalDate.of(2026, 10, 9), 22600, 22400),           // previous week (5-9 Oct)
                day(LocalDate.of(2026, 10, 5), 22700, 22350),
                day(LocalDate.of(2026, 10, 1), 22900, 22500),           // this month: not the previous month
                day(LocalDate.of(2026, 9, 30), 23000, 22600),           // previous month (September)
                day(LocalDate.of(2026, 9, 2), 23950, 23500),
                day(LocalDate.of(2026, 8, 31), 24500, 20000));          // August: neither
        PeriodLevels p = PeriodLevels.of(session, history);
        assertThat(p.weekHigh()).isEqualTo(22700);
        assertThat(p.weekLow()).isEqualTo(22350);
        assertThat(p.monthHigh()).isEqualTo(23950);
        assertThat(p.monthLow()).isEqualTo(22600);
        assertThat(PeriodLevels.of(session, List.of()).weekHigh()).isNaN();
    }
}
