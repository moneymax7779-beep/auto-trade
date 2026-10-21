package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.strategy.OptionSide;

/**
 * ATM OI burst (A-157 / A-158): when the 3-minute net OI change over the ATM ± 2 strikes (puts written minus calls written,
 * as a share of those strikes' OI) is at least {@code entry.burst_k} times the day's mean so far, and the futures volume of
 * the last 3 minutes is at least {@code entry.volume_ratio_min} times the median of the day's earlier 3-minute windows, buy
 * the ATM option in the flow's direction (puts written faster = call). No index stop or target: the position is held until
 * a burst of the same strength the other way ({@code exits.opposite_burst_k}), the premium stop or {@code flat_by}.
 * <p>
 * The day's mean and the volume median use only earlier snapshots (from 09:16), one per minute. No new entry in the same
 * direction within {@code entry.same_direction_gap_min} minutes of the last.
 */
final class AtmOiBurst extends SetupStrategy {

    private static final LocalTime HISTORY_FROM = LocalTime.of(9, 16);

    private final double burstK;
    private final int minHistory;
    private final double volumeRatioMin;
    private final int gapMin;
    private final double oppositeK;

    private double absShareSum;
    private int absShareCount;
    private final List<Long> minuteVolumes = new ArrayList<>();
    private final List<Long> windowVolumes = new ArrayList<>();
    private final Map<OptionSide, LocalTime> lastEntry = new EnumMap<>(OptionSide.class);
    /** This snapshot's share and its size against the day's earlier mean (set in evaluate, read by the exit). */
    private double share = Double.NaN;
    private double relative = Double.NaN;

    AtmOiBurst(ThresholdConfig c) {
        super(c);
        burstK = c.getDouble("entry.burst_k");
        minHistory = c.getInt("entry.min_history");
        volumeRatioMin = c.getDouble("entry.volume_ratio_min");
        gapMin = c.getInt("entry.same_direction_gap_min");
        oppositeK = c.getDouble("exits.opposite_burst_k");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        share = s.options() == null ? Double.NaN : s.options().atmBandShare3m();
        double mean = absShareCount >= minHistory ? absShareSum / absShareCount : Double.NaN;
        relative = Double.isFinite(share) && mean > 0 ? Math.abs(share) / mean : Double.NaN;
        double volumeRatio = Double.NaN;
        if (!time.isBefore(HISTORY_FROM)) {
            if (Double.isFinite(share)) {
                absShareSum += Math.abs(share);
                absShareCount++;
            }
            minuteVolumes.add(s.futures() == null ? 0L : s.futures().volumeLastMinute());
            int n = minuteVolumes.size();
            if (n >= 3) {
                long window = minuteVolumes.get(n - 1) + minuteVolumes.get(n - 2) + minuteVolumes.get(n - 3);
                double median = median(windowVolumes);
                volumeRatio = median > 0 ? window / median : Double.NaN;
                windowVolumes.add(window);
            }
        }
        boolean burst = relative >= burstK;                              // NaN compares false
        boolean volume = volumeRatio >= volumeRatioMin;
        OptionSide side = share > 0 ? OptionSide.CE : OptionSide.PE;
        LocalTime last = lastEntry.get(side);
        boolean gap = last == null || Duration.between(last, time).toMinutes() >= gapMin;
        c.put("burst", burst);
        c.put("burst_up", burst && share > 0);
        c.put("futures_volume", volume);
        c.put("same_direction_gap", gap);
        if (!canEnter || !burst || !volume || !gap) {
            return null;
        }
        lastEntry.put(side, time);
        return new Entry(side, Double.NaN, Double.NaN, side == OptionSide.CE ? "OI_BURST_UP" : "OI_BURST_DOWN");
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        double sign = side == OptionSide.CE ? 1 : -1;
        return sign * share < 0 && relative >= oppositeK ? "OPPOSITE_BURST" : null;
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
