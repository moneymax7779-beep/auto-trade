package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * A. Opening-low reclaim (A-036): the day's low made 09:15-09:30 at a prior-day level holds, then a close back above the
 * day open -> call; mirrored for a morning high and a close below the open -> put.
 */
final class OpeningReclaim extends SetupStrategy {

    private final LocalTime morningEnd;
    private final double nearAtr;
    private final int holdMin;
    private double low = Double.NaN, high = Double.NaN;
    private LocalTime lowAt, highAt;
    private boolean longTaken, shortTaken;

    OpeningReclaim(ThresholdConfig c) {
        super(c);
        morningEnd = c.getTime("setup.morning_until");
        nearAtr = c.getDouble("setup.near_level_atr");
        holdMin = c.getInt("setup.hold_min");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        if (Double.isFinite(st.dayLow()) && (Double.isNaN(low) || st.dayLow() < low)) {
            low = st.dayLow();
            lowAt = time;
        }
        if (Double.isFinite(st.dayHigh()) && (Double.isNaN(high) || st.dayHigh() > high)) {
            high = st.dayHigh();
            highAt = time;
        }
        double atr = st.atr3m(), open = st.dayOpen(), close = close(0), prev = close(1);
        double[] prior = {st.prevOrHigh(), st.prevOrLow(), st.pdh(), st.pdl(), st.prevClose()};
        boolean ready = atr > 0 && Double.isFinite(open) && Double.isFinite(close) && Double.isFinite(prev);
        boolean lowMorning = lowAt != null && !lowAt.isAfter(morningEnd);
        boolean lowAtLevel = ready && near(low, prior, nearAtr * atr);
        boolean lowHeld = lowAt != null && !time.isBefore(lowAt.plusMinutes(holdMin));
        boolean crossUp = ready && close > open && prev <= open;
        boolean highMorning = highAt != null && !highAt.isAfter(morningEnd);
        boolean highAtLevel = ready && near(high, prior, nearAtr * atr);
        boolean highHeld = highAt != null && !time.isBefore(highAt.plusMinutes(holdMin));
        boolean crossDown = ready && close < open && prev >= open;
        c.put("low_in_morning", lowMorning);
        c.put("low_at_prior_level", lowAtLevel);
        c.put("low_held", lowHeld);
        c.put("reclaims_open", crossUp);
        c.put("high_in_morning", highMorning);
        c.put("high_at_prior_level", highAtLevel);
        c.put("high_held", highHeld);
        c.put("loses_open", crossDown);
        if (!canEnter) {
            return null;
        }
        if (!longTaken && lowMorning && lowAtLevel && lowHeld && crossUp) {
            longTaken = true;
            return new Entry(OptionSide.CE, low, target(close, atr, true, 0.5, 1.5, st.orHigh(), st.pdh(), st.prevOrHigh()),
                    "OPENING_LOW_RECLAIM");
        }
        if (!shortTaken && highMorning && highAtLevel && highHeld && crossDown) {
            shortTaken = true;
            return new Entry(OptionSide.PE, high, target(close, atr, false, 0.5, 1.5, st.orLow(), st.pdl(), st.prevOrLow()),
                    "OPENING_HIGH_LOST");
        }
        return null;
    }

    private static boolean near(double x, double[] levels, double tolerance) {
        for (double l : levels) {
            if (Double.isFinite(l) && Math.abs(x - l) <= tolerance) {
                return true;
            }
        }
        return false;
    }
}
