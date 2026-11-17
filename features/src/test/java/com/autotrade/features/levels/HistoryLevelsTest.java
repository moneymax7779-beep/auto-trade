package com.autotrade.features.levels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.time.MarketTime;

/** A-439: HistoryLevels from three earlier sessions (two in this month, one in the month before). */
class HistoryLevelsTest {

    private static List<MinuteBar> day(LocalDate d, double orHigh, double orLow, double high, double low) {
        return List.of(bar(d, "09:15", orHigh, orLow), bar(d, "09:29", orHigh - 1, orLow + 1), bar(d, "11:00", high, low));
    }

    private static MinuteBar bar(LocalDate d, String t, double h, double l) {
        return new MinuteBar(d.atTime(LocalTime.parse(t)).atZone(MarketTime.IST).toInstant(), l, h, l, h);
    }

    @Test
    void twoDayOpeningRangeAndMonths() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        List<List<MinuteBar>> sessions = List.of(                         // newest first, as SessionHistory gives them
                day(LocalDate.of(2026, 11, 11), 25300, 25250, 25350, 25200),
                day(LocalDate.of(2026, 11, 10), 25100, 25050, 25400, 25000),
                day(LocalDate.of(2026, 10, 30), 23000, 22950, 23100, 22900),
                day(today, 1, 1, 1, 1));                                // today is never used
        assertThat(HistoryLevels.of(today, sessions)).containsExactly(
                25400.0, 25000.0,                                          // 2-day high / low
                25100.0, 25050.0,                                          // the opening range two sessions back
                23100.0, 22900.0,                                          // October
                23100.0, 22900.0);                                         // over all 12 months (only October has data)
    }
}
