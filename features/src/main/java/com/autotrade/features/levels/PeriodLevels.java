package com.autotrade.features.levels;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.time.MarketTime;

/**
 * Features v12 (A-090): the previous ISO week's (Monday-Friday before the session's week) and the previous calendar
 * month's high and low of an underlying, from the sessions' spot minute bars. NaN for a period with no session in the
 * history given (point-in-time: only sessions before {@code session} are passed in).
 */
public record PeriodLevels(double weekHigh, double weekLow, double monthHigh, double monthLow) {

    public static final PeriodLevels NONE = new PeriodLevels(Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public static PeriodLevels of(LocalDate session, List<List<MinuteBar>> sessions) {
        LocalDate weekStart = session.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate prevWeekStart = weekStart.minusWeeks(1);
        LocalDate monthStart = session.withDayOfMonth(1);
        LocalDate prevMonthStart = monthStart.minusMonths(1);
        double wh = Double.NEGATIVE_INFINITY, wl = Double.POSITIVE_INFINITY;
        double mh = Double.NEGATIVE_INFINITY, ml = Double.POSITIVE_INFINITY;
        for (List<MinuteBar> day : sessions) {
            if (day.isEmpty()) {
                continue;
            }
            LocalDate date = day.getFirst().start().atZone(MarketTime.IST).toLocalDate();
            if (!date.isBefore(session)) {
                continue;                                     // never today or later
            }
            boolean inWeek = !date.isBefore(prevWeekStart) && date.isBefore(weekStart);
            boolean inMonth = !date.isBefore(prevMonthStart) && date.isBefore(monthStart);
            if (!inWeek && !inMonth) {
                continue;
            }
            for (MinuteBar bar : day) {
                if (inWeek) {
                    wh = Math.max(wh, bar.high());
                    wl = Math.min(wl, bar.low());
                }
                if (inMonth) {
                    mh = Math.max(mh, bar.high());
                    ml = Math.min(ml, bar.low());
                }
            }
        }
        return new PeriodLevels(finite(wh), finite(wl), finite(mh), finite(ml));
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : Double.NaN;
    }
}
