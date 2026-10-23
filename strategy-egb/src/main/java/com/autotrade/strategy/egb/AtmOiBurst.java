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
import com.autotrade.strategy.PositionView;

/**
 * ATM OI burst (A-157 / A-158): when the 3-minute net OI change over the ATM ± 2 strikes (puts written minus calls written,
 * as a share of those strikes' OI) is at least {@code entry.burst_k} times the day's mean so far, and the futures volume of
 * the last 3 minutes is at least {@code entry.volume_ratio_min} times the median of the day's earlier 3-minute windows, buy
 * the ATM option in the flow's direction (puts written faster = call). No index stop or target. aob-v1 holds until a burst
 * of the same strength the other way ({@code exits.opposite_burst_k}), the premium stop or {@code flat_by}; aob-v2 (A-159,
 * no {@code opposite_burst_k}) exits on the frame's premium trail, the premium stop or {@code flat_by}. aob-v4 (A-161,
 * {@code exits.climax_k}) adds the climax exit: the trade's own driving OI (calls written for a put, puts written for a
 * call) at least {@code climax_k} times the day's mean of that side while the index makes a new extreme since entry, once
 * the trade is {@code exits.climax_min_minutes} old and the option's bid is above its premium. aob-v5 (A-179,
 * {@code entry.burst_kind: covering}) enters only on a covering burst: an up burst whose call OI fall outweighs its put
 * OI rise, a down burst whose put OI fall outweighs its call OI rise (writers closing, A-178's split). aob-v6 (A-180):
 * the day's mean counts minutes from {@code entry.mean_from} only, and a second trigger, sustained flow: the sum of the
 * 3-minute shares at m, m − 3, m − 6, m − 9, m − 12 (about the 15-minute change) at least {@code entry.sustained_k} times
 * the day's mean of that sum, with at least {@code entry.sustained_agree} of the five on its side; no volume condition.
 * aob-v7 (A-181) also needs the newest of the five on the sum's side ({@code entry.sustained_newest_same_side}) and no one
 * of them above {@code entry.sustained_max_share} of the sum, so one old spike cannot carry it. aob-v9 (A-186,
 * {@code entry.with_day_direction}) buys a call only with the index above the day's open and a put only below it.
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
    private final double climaxK;
    private final int climaxMinMinutes;
    /** A-179: only bursts driven by writers closing ("covering"); absent = any burst. */
    private final boolean coveringOnly;
    /** A-180: the first minute the day's means count (absent: 09:16), and the sustained-flow trigger (absent: off). */
    private final LocalTime meanFrom;
    private final double sustainedK;
    private final int sustainedAgree;
    private final boolean sustainedNewestSameSide;
    private final double sustainedMaxShare;
    private final boolean withDayDirection;
    private final Map<Integer, Double> shareByMinute = new java.util.HashMap<>();
    private double s15Sum;
    private int s15Count;
    private double callSum, putSum;
    private int sideCount;
    private double callMean = Double.NaN, putMean = Double.NaN;
    private double callShare = Double.NaN, putShare = Double.NaN;
    /** A-161: the index extreme (minute closes) in the open trade's direction since its entry, and that entry's time. */
    private java.time.Instant extremeFor;
    private double extreme = Double.NaN;

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
        oppositeK = c.has("exits.opposite_burst_k") ? c.getDouble("exits.opposite_burst_k") : Double.NaN;   // absent: off
        climaxK = c.has("exits.climax_k") ? c.getDouble("exits.climax_k") : Double.NaN;                    // A-161: absent = off
        climaxMinMinutes = c.has("exits.climax_min_minutes") ? c.getInt("exits.climax_min_minutes") : 10;
        String kind = c.has("entry.burst_kind") ? c.getString("entry.burst_kind") : "any";
        if (!kind.equals("any") && !kind.equals("covering")) {
            throw new IllegalArgumentException("entry.burst_kind must be any or covering: " + kind);
        }
        coveringOnly = kind.equals("covering");
        meanFrom = c.has("entry.mean_from") ? c.getTime("entry.mean_from") : HISTORY_FROM;
        sustainedK = c.has("entry.sustained_k") ? c.getDouble("entry.sustained_k") : Double.NaN;
        sustainedAgree = c.has("entry.sustained_agree") ? c.getInt("entry.sustained_agree") : 4;
        sustainedNewestSameSide = c.has("entry.sustained_newest_same_side") && c.getBoolean("entry.sustained_newest_same_side");
        sustainedMaxShare = c.has("entry.sustained_max_share") ? c.getDouble("entry.sustained_max_share") : Double.NaN;
        withDayDirection = c.has("entry.with_day_direction") && c.getBoolean("entry.with_day_direction");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        share = s.options() == null ? Double.NaN : s.options().atmBandShare3m();
        callShare = s.options() == null ? Double.NaN : s.options().atmBandCallShare3m();
        putShare = s.options() == null ? Double.NaN : s.options().atmBandPutShare3m();
        callMean = sideCount >= minHistory ? callSum / sideCount : Double.NaN;
        putMean = sideCount >= minHistory ? putSum / sideCount : Double.NaN;
        if (!time.isBefore(HISTORY_FROM) && Double.isFinite(callShare) && Double.isFinite(putShare)) {
            callSum += Math.abs(callShare);
            putSum += Math.abs(putShare);
            sideCount++;
        }
        double mean = absShareCount >= minHistory ? absShareSum / absShareCount : Double.NaN;
        relative = Double.isFinite(share) && mean > 0 ? Math.abs(share) / mean : Double.NaN;
        double volumeRatio = Double.NaN;
        if (!time.isBefore(meanFrom) && Double.isFinite(share)) {
            absShareSum += Math.abs(share);
            absShareCount++;
        }
        // A-180: sustained flow from the five 3-minute shares ending at m, m - 3, ..., m - 12
        int minute = time.getHour() * 60 + time.getMinute();
        if (Double.isFinite(share)) {
            shareByMinute.put(minute, share);
        }
        double s15 = 0, largest = 0;
        int agree = 0;
        boolean full = true;
        for (int back = 0; back <= 12; back += 3) {
            Double v = shareByMinute.get(minute - back);
            if (v == null) {
                full = false;
                break;
            }
            s15 += v;
            largest = Math.max(largest, Math.abs(v));
        }
        if (full) {
            for (int back = 0; back <= 12; back += 3) {
                if (Math.signum(shareByMinute.get(minute - back)) == Math.signum(s15) && s15 != 0) {
                    agree++;
                }
            }
        }
        double mean15 = s15Count >= minHistory ? s15Sum / s15Count : Double.NaN;
        boolean sustained = sustainedK > 0 && full && mean15 > 0 && Math.abs(s15) >= sustainedK * mean15 && agree >= sustainedAgree
                && (!sustainedNewestSameSide || Math.signum(share) == Math.signum(s15))                 // A-181
                && !(largest > sustainedMaxShare * Math.abs(s15));                                       // NaN: no cap
        if (full && !time.isBefore(meanFrom)) {
            s15Sum += Math.abs(s15);
            s15Count++;
        }
        if (!time.isBefore(HISTORY_FROM)) {
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
        // A-179: covering = the falling side drives the burst (up: -dCall > dPut; down: -dPut > dCall)
        boolean covering = share > 0 ? -callShare > putShare : -putShare > callShare;     // NaN compares false
        boolean spike = burst && volume && (!coveringOnly || covering);
        OptionSide side = spike ? (share > 0 ? OptionSide.CE : OptionSide.PE)
                : sustained ? (s15 > 0 ? OptionSide.CE : OptionSide.PE)
                : share > 0 ? OptionSide.CE : OptionSide.PE;
        LocalTime last = lastEntry.get(side);
        boolean gap = last == null || Duration.between(last, time).toMinutes() >= gapMin;
        c.put("burst", burst);
        c.put("burst_up", burst && share > 0);
        c.put("futures_volume", volume);
        c.put("same_direction_gap", gap);
        if (coveringOnly) {
            c.put("covering", covering);
        }
        if (sustainedK > 0) {
            c.put("sustained_flow", sustained);
        }
        if (withDayDirection) {                                                  // A-186: with the day, not against it
            double open = s.structure() == null ? Double.NaN : s.structure().dayOpen();
            boolean with = side == OptionSide.CE ? s.spot() > open : s.spot() < open;   // NaN compares false: no entry
            c.put("with_day_direction", with);
            if (!with) {
                return null;
            }
        }
        if (!canEnter || !(spike || sustained) || !gap) {
            return null;
        }
        lastEntry.put(side, time);
        String up = spike ? "OI_BURST_UP" : "OI_FLOW_UP", down = spike ? "OI_BURST_DOWN" : "OI_FLOW_DOWN";
        return new Entry(side, Double.NaN, Double.NaN, side == OptionSide.CE ? up : down);
    }

    @Override
    protected String customExit(FeatureSnapshot s, PositionView position, LocalTime time) {
        OptionSide side = position.side();
        double sign = side == OptionSide.CE ? 1 : -1;
        if (sign * share < 0 && relative >= oppositeK) {                               // NaN: never
            return "OPPOSITE_BURST";
        }
        // A-161: a new extreme since entry, judged on the minute closes (the study used minute lows / highs)
        double spot = s.spot();
        boolean newExtreme = false;
        if (position.openedAt() != null && !position.openedAt().equals(extremeFor)) {
            extremeFor = position.openedAt();
            extreme = spot;
        } else if (Double.isFinite(spot) && Double.isFinite(extreme)) {
            newExtreme = side == OptionSide.PE ? spot < extreme : spot > extreme;
            extreme = side == OptionSide.PE ? Math.min(extreme, spot) : Math.max(extreme, spot);
        }
        if (!(climaxK > 0) || position.openedAt() == null
                || Duration.between(position.openedAt(), s.time()).toMinutes() < climaxMinMinutes
                || !(position.bid() > position.averagePremium())) {
            return null;
        }
        double own = side == OptionSide.PE ? callShare : putShare;
        double mean = side == OptionSide.PE ? callMean : putMean;
        return newExtreme && mean > 0 && own >= climaxK * mean ? "OI_CLIMAX" : null;
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
