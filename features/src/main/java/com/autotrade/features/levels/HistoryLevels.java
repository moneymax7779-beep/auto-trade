package com.autotrade.features.levels;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.time.MarketTime;

/**
 * A-439 (features {@code structure.history_levels}): older levels known at 09:15, as the chart's LevelsService draws them
 * (prior day and period levels are already in the structure features):
 * <ul>
 * <li>the 2-day high / low (the two sessions before today);</li>
 * <li>the opening range (09:15-09:30) high / low of the session two days back ("2-day ORH / ORL");</li>
 * <li>each of the {@value #MONTHS} calendar months before today's month: its high and low, and the high / low over all
 *     of them.</li>
 * </ul>
 */
public final class HistoryLevels {

    public static final int MONTHS = 12;
    /** Enough sessions for {@value #MONTHS} months. */
    public static final int SESSIONS = 270;
    private static final LocalTime OR_END = LocalTime.of(9, 30);

    private HistoryLevels() {
    }

    public static List<Double> of(LocalDate session, List<List<MinuteBar>> sessions) {
        List<List<MinuteBar>> days = new ArrayList<>();
        for (List<MinuteBar> d : sessions) {
            if (!d.isEmpty() && date(d.getFirst()).isBefore(session)) {
                days.add(d);
            }
        }
        days.sort(Comparator.comparing((List<MinuteBar> d) -> date(d.getFirst())).reversed());
        List<Double> out = new ArrayList<>();
        if (days.size() >= 2) {
            out.add(Math.max(high(days.get(0)), high(days.get(1))));
            out.add(Math.min(low(days.get(0)), low(days.get(1))));
            double orh = Double.NEGATIVE_INFINITY, orl = Double.POSITIVE_INFINITY;
            for (MinuteBar b : days.get(1)) {
                if (b.start().atZone(MarketTime.IST).toLocalTime().isBefore(OR_END)) {
                    orh = Math.max(orh, b.high());
                    orl = Math.min(orl, b.low());
                }
            }
            if (Double.isFinite(orh)) {
                out.add(orh);
                out.add(orl);
            }
        }
        LocalDate first = session.withDayOfMonth(1).minusMonths(MONTHS), end = session.withDayOfMonth(1);
        double all = Double.NEGATIVE_INFINITY, allLow = Double.POSITIVE_INFINITY;
        for (LocalDate m = first; m.isBefore(end); m = m.plusMonths(1)) {
            double h = Double.NEGATIVE_INFINITY, l = Double.POSITIVE_INFINITY;
            for (List<MinuteBar> d : days) {
                LocalDate day = date(d.getFirst());
                if (!day.isBefore(m) && day.isBefore(m.plusMonths(1))) {
                    h = Math.max(h, high(d));
                    l = Math.min(l, low(d));
                }
            }
            if (Double.isFinite(h)) {
                out.add(h);
                out.add(l);
                all = Math.max(all, h);
                allLow = Math.min(allLow, l);
            }
        }
        if (Double.isFinite(all)) {
            out.add(all);
            out.add(allLow);
        }
        out.removeIf(v -> !Double.isFinite(v));
        return List.copyOf(out);
    }

    private static LocalDate date(MinuteBar b) {
        return b.start().atZone(MarketTime.IST).toLocalDate();
    }

    private static double high(List<MinuteBar> d) {
        return d.stream().mapToDouble(MinuteBar::high).max().orElse(Double.NaN);
    }

    private static double low(List<MinuteBar> d) {
        return d.stream().mapToDouble(MinuteBar::low).min().orElse(Double.NaN);
    }
}
