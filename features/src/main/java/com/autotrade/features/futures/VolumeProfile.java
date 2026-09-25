package com.autotrade.features.futures;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Futures volume by minute of day over previous sessions, for time-of-day relative volume. */
public final class VolumeProfile {

    public record SlotMedian(double median, int sessions) {
    }

    private final List<Map<LocalTime, Long>> sessions;

    public VolumeProfile(List<Map<LocalTime, Long>> sessions) {
        this.sessions = List.copyOf(sessions);
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** Median over previous sessions of the summed volume of {@code minutes}; sessions missing a minute are skipped. */
    public SlotMedian slotMedian(List<LocalTime> minutes) {
        List<Long> totals = new ArrayList<>();
        for (Map<LocalTime, Long> session : sessions) {
            long total = 0;
            boolean complete = true;
            for (LocalTime minute : minutes) {
                Long volume = session.get(minute);
                if (volume == null) {
                    complete = false;
                    break;
                }
                total += volume;
            }
            if (complete) {
                totals.add(total);
            }
        }
        if (totals.isEmpty()) {
            return new SlotMedian(Double.NaN, 0);
        }
        totals.sort(Long::compare);
        int n = totals.size();
        double median = n % 2 == 1 ? totals.get(n / 2) : (totals.get(n / 2 - 1) + totals.get(n / 2)) / 2.0;
        return new SlotMedian(median, n);
    }
}
