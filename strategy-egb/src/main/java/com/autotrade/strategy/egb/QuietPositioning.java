package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * Quiet price with one-sided option positioning (A-132; user 17 Oct 2026 after the 1 Oct NIFTY table "PCR ratio along
 * with long buildup and long unwinding": 10:45-11:30 the price was flat while call OI rose and put OI fell, then the
 * 12:07 break down; follow-up in docs/studies/2026-10-17-break-oi-volume-study.md, loosened definition). Both indices.
 * <p>
 * At each 15-minute boundary from 09:45 (09:30-09:45 is the first interval): the interval's near-ATM call and put OI
 * changes (the sum of the 1-minute changes) and the index move. Bearish: call OI up, put OI down, the index down by at
 * most {@code quiet_max_atr} ATR; bullish mirrors it. {@code intervals} such intervals in a row with the same direction →
 * buy the ATM put (call), once per run.
 * <p>
 * Exit (the user's choices): the index closes back beyond the far side of the run's range (a put: above its highest
 * close) and at that close the OI flow has reversed (put OI building and call OI unwinding over 1 or 3 minutes);
 * otherwise held to flat_by. The resting premium stop is the emergency stop.
 */
final class QuietPositioning extends SetupStrategy {

    private static final LocalTime FIRST = LocalTime.of(9, 30);

    private final double quietMaxAtr;
    private final int intervals;
    /** A-134: "level_oi" (v1), "level" (range back alone) or "atr" (index stop stop_atr_from_entry ATR). */
    private final String stopMode;
    private final double stopAtrFromEntry;
    /** A-135: index target this many ATR(3m) from the entry (NaN = none: hold to flat_by). */
    private final double targetAtr;
    private double ceSum, peSum;
    private double intervalStartSpot = Double.NaN;
    private double intervalHigh = Double.NEGATIVE_INFINITY, intervalLow = Double.POSITIVE_INFINITY;
    private int runDir, runLen;
    private final double[] runHighs = new double[16], runLows = new double[16];
    private double rangeHigh = Double.NaN, rangeLow = Double.NaN;
    private boolean curFlowUp, curFlowDown;
    private double curSpot = Double.NaN;

    QuietPositioning(ThresholdConfig c) {
        super(c);
        quietMaxAtr = c.getDouble("setup.quiet_max_atr");
        intervals = c.getInt("setup.intervals");
        stopMode = c.has("exits.stop_mode") ? c.getString("exits.stop_mode") : "level_oi";
        stopAtrFromEntry = c.has("exits.stop_atr_from_entry") ? c.getDouble("exits.stop_atr_from_entry") : Double.NaN;
        targetAtr = c.has("exits.target_atr") ? c.getDouble("exits.target_atr") : Double.NaN;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        OptionsFeatures o = s.options();
        double spot = close(0), atr = s.structure().atr3m();
        curSpot = spot;
        curFlowUp = (o.peOiChange1m() > 0 && o.ceOiChange1m() < 0) || (o.peOiChange3m() > 0 && o.ceOiChange3m() < 0);
        curFlowDown = (o.ceOiChange1m() > 0 && o.peOiChange1m() < 0) || (o.ceOiChange3m() > 0 && o.peOiChange3m() < 0);
        if (time.isBefore(FIRST)) {
            return null;
        }
        if (time.equals(FIRST) || Double.isNaN(intervalStartSpot)) {
            startInterval(spot);
            return null;
        }
        if (Double.isFinite(o.ceOiChange1m())) {
            ceSum += o.ceOiChange1m();
        }
        if (Double.isFinite(o.peOiChange1m())) {
            peSum += o.peOiChange1m();
        }
        if (Double.isFinite(spot)) {
            intervalHigh = Math.max(intervalHigh, spot);
            intervalLow = Math.min(intervalLow, spot);
        }
        long minutes = Duration.between(FIRST, time).toMinutes();
        if (minutes % 15 != 0) {
            return null;
        }
        double move = spot - intervalStartSpot;
        boolean quiet = atr > 0 && Math.abs(move) <= quietMaxAtr * atr;
        int dir = !quiet ? 0
                : ceSum > 0 && peSum < 0 && move < 0 ? -1
                : peSum > 0 && ceSum < 0 && move > 0 ? 1 : 0;
        if (dir != 0 && dir == runDir) {
            runLen++;
        } else {
            runDir = dir;
            runLen = dir == 0 ? 0 : 1;
        }
        if (runLen >= 1 && runLen <= runHighs.length) {
            runHighs[runLen - 1] = Math.max(intervalHigh, intervalStartSpot);
            runLows[runLen - 1] = Math.min(intervalLow, intervalStartSpot);
        }
        c.put("quiet", quiet);
        c.put("positioning_down", dir < 0);
        c.put("positioning_up", dir > 0);
        startInterval(spot);
        if (runLen != intervals || !canEnter) {
            return null;
        }
        rangeHigh = Double.NEGATIVE_INFINITY;
        rangeLow = Double.POSITIVE_INFINITY;
        for (int i = 0; i < intervals; i++) {
            rangeHigh = Math.max(rangeHigh, runHighs[i]);
            rangeLow = Math.min(rangeLow, runLows[i]);
        }
        return runDir < 0
                ? new Entry(OptionSide.PE, indexStop(spot, atr, false), indexTarget(spot, atr, false), "QUIET_POSITIONING_DOWN")
                : new Entry(OptionSide.CE, indexStop(spot, atr, true), indexTarget(spot, atr, true), "QUIET_POSITIONING_UP");
    }

    private void startInterval(double spot) {
        intervalStartSpot = spot;
        ceSum = 0;
        peSum = 0;
        intervalHigh = Double.NEGATIVE_INFINITY;
        intervalLow = Double.POSITIVE_INFINITY;
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        if (!Double.isFinite(curSpot) || !Double.isFinite(rangeHigh)) {
            return null;
        }
        boolean put = side == OptionSide.PE;
        boolean levelBack = put ? curSpot > rangeHigh : curSpot < rangeLow;
        boolean flowAgainst = put ? curFlowUp : curFlowDown;
        return switch (stopMode) {
            case "level" -> levelBack ? "RANGE_BACK" : null;
            case "atr" -> null;                             // the frame's index STOP
            default -> levelBack && flowAgainst ? "RANGE_BACK_OI_REVERSED" : null;
        };
    }

    /** The index stop: stop_atr_from_entry ATR against the entry in "atr" mode, else none. */
    private double indexStop(double spot, double atr, boolean call) {
        return "atr".equals(stopMode) ? spot + (call ? -1 : 1) * stopAtrFromEntry * atr : Double.NaN;
    }

    /** The index target: target_atr ATR in the trade's direction, else none. */
    private double indexTarget(double spot, double atr, boolean call) {
        return Double.isFinite(targetAtr) ? spot + (call ? 1 : -1) * targetAtr * atr : Double.NaN;
    }
}
