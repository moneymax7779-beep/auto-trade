package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Compression break (A-140; user 18 Oct 2026, the 1 Oct chart: a three-hour box on dead futures volume, then the 12:07
 * break on a 16x volume surge). Decided on every 1-minute close, both indices, every day, one trade per index per day.
 * <p>
 * The box is the last {@code box_minutes} 1-minute closes before this one: it qualifies when its height is at most
 * {@code box_max_pct} % of the index and the mean futures time-of-day RVOL over those minutes is at most
 * {@code quiet_rvol_max}. A break is a close beyond the box; it stays open for {@code confirm_minutes} closes while the
 * index stays beyond the box, for the confirmation: futures RVOL at least {@code min_rvol_tod} and the near-ATM OI flow
 * agreeing over 1 or 3 minutes (down: call OI building, put OI unwinding; up mirrored) → ATM option. Exit: a 1-minute
 * close back inside the box (BOX_BACK); otherwise held to flat_by; the resting premium stop is the emergency stop.
 */
final class CompressionBreak extends SetupStrategy {

    private final int boxMinutes;
    private final double boxMaxPct;
    private final double quietRvolMax;
    private final double minRvolTod;
    private final int confirmMinutes;
    private final boolean requireFlow;
    private final ArrayDeque<double[]> rows = new ArrayDeque<>();     // {close, rvolTod} per minute, the day so far
    private final List<double[]> pending = new ArrayList<>();       // {direction, box edge, minute}
    private int minute;
    private boolean traded;
    private double brokenEdge = Double.NaN, curSpot = Double.NaN;
    private boolean curFlowUp, curFlowDown;

    CompressionBreak(ThresholdConfig c) {
        super(c);
        boxMinutes = c.getInt("setup.box_minutes");
        boxMaxPct = c.getDouble("setup.box_max_pct");
        quietRvolMax = c.getDouble("setup.quiet_rvol_max");
        minRvolTod = c.getDouble("setup.min_rvol_tod");
        confirmMinutes = c.getInt("setup.confirm_minutes");
        requireFlow = c.getBoolean("setup.require_flow");
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        OptionsFeatures o = s.options();
        double spot = close(0), rvol = s.futures().rvolTod();
        curSpot = spot;
        minute++;
        curFlowUp = (o.peOiChange1m() > 0 && o.ceOiChange1m() < 0) || (o.peOiChange3m() > 0 && o.ceOiChange3m() < 0);
        curFlowDown = (o.ceOiChange1m() > 0 && o.peOiChange1m() < 0) || (o.ceOiChange3m() > 0 && o.peOiChange3m() < 0);
        // the box: the closes before this one
        boolean boxOk = false;
        double hi = Double.NaN, lo = Double.NaN;
        if (rows.size() >= boxMinutes) {
            hi = Double.NEGATIVE_INFINITY;
            lo = Double.POSITIVE_INFINITY;
            double rvolSum = 0;
            int rvolN = 0;
            for (double[] r : rows) {
                hi = Math.max(hi, r[0]);
                lo = Math.min(lo, r[0]);
                if (Double.isFinite(r[1])) {
                    rvolSum += r[1];
                    rvolN++;
                }
            }
            boxOk = Double.isFinite(spot) && (hi - lo) / spot * 100 <= boxMaxPct && rvolN >= boxMinutes / 2
                    && rvolSum / rvolN <= quietRvolMax;
        }
        if (boxOk && Double.isFinite(spot)) {
            if (spot > hi) {
                pending.add(new double[] {1, hi, minute});
            } else if (spot < lo) {
                pending.add(new double[] {-1, lo, minute});
            }
        }
        if (Double.isFinite(spot)) {
            rows.addLast(new double[] {spot, rvol});
            while (rows.size() > boxMinutes) {
                rows.removeFirst();
            }
        }
        double openUp = Double.NaN, openDown = Double.NaN;
        for (var it = pending.iterator(); it.hasNext();) {
            double[] p = it.next();
            boolean beyond = p[0] > 0 ? spot > p[1] : spot < p[1];
            if (minute - p[2] > confirmMinutes || !beyond) {
                it.remove();
            } else if (p[0] > 0) {
                openUp = p[1];
            } else {
                openDown = p[1];
            }
        }
        boolean surge = rvol >= minRvolTod;                            // NaN compares false
        c.put("box", boxOk);
        c.put("break_open_up", Double.isFinite(openUp));
        c.put("break_open_down", Double.isFinite(openDown));
        c.put("volume_surge", surge);
        c.put("flow_up", curFlowUp);
        c.put("flow_down", curFlowDown);
        if (!canEnter || traded) {
            return null;
        }
        if (Double.isFinite(openUp) && surge && (!requireFlow || curFlowUp)) {
            traded = true;
            brokenEdge = openUp;
            pending.clear();
            return new Entry(OptionSide.CE, Double.NaN, Double.NaN, "COMPRESSION_BREAK_UP");
        }
        if (Double.isFinite(openDown) && surge && (!requireFlow || curFlowDown)) {
            traded = true;
            brokenEdge = openDown;
            pending.clear();
            return new Entry(OptionSide.PE, Double.NaN, Double.NaN, "COMPRESSION_BREAK_DOWN");
        }
        return null;
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        if (!Double.isFinite(brokenEdge) || !Double.isFinite(curSpot)) {
            return null;
        }
        boolean back = side == OptionSide.PE ? curSpot > brokenEdge : curSpot < brokenEdge;
        return back ? "BOX_BACK" : null;
    }
}
