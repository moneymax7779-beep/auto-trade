package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * OI burst + EMA cross, continuation (A-250 / A-251; rule frozen from the A-250 bar study). Per index, in the entry window:
 * the index crossed its EMA20 of 1-minute closes (seeded with the previous close) within the last
 * {@code cross_lookback_min} minutes and is still on that side, while the ATM ± 2 net OI flow share (puts written minus
 * calls written) over 3 minutes is at least the index's threshold in the same direction and the 1-minute share has the
 * same sign. Only the day's first such event, and only if it is a continuation (its side = the sign of index - day
 * open). Stop: the extreme of the last {@code stop_lookback_min} minute closes; no target; flat at {@code flat_by}.
 * <p>
 * A-252 variants (off when absent): {@code setup.fresh_cross: false} enters on the burst with the close on its side of
 * the EMA, without a fresh cross; {@code exits.structure_buffer_pct} replaces the stop with a close beyond the
 * invalidation level (puts: the nearest of PDH and the opening-range high above the entry, else the day high; calls: PDL /
 * opening-range low below, else the day low) moved out by that % of price; {@code exits.counter_burst: true} exits on a
 * 3-minute share beyond the threshold against the trade.
 * <p>
 * A-253 ({@code setup.burst_memory_min} > 0): a burst is the 1-, 3- or 5-minute share at or beyond that window's own
 * threshold in one direction, remembered for that many minutes; the entry needs a remembered burst on the side the close
 * is on relative to the EMA (no 1-minute same-sign check).
 * <p>
 * A-254 ({@code exits.atr_stop_mult}): the stop is the tighter of the above and the entry -+ that many ATR3m.
 * A-255 ({@code exits.trail_arm_atr}, {@code exits.trail_atr}): once a close is that many ATR3m in favour, exit on a close
 * {@code trail_atr} ATR3m below the best close since the entry.
 */
final class OiBurstCross extends SetupStrategy {

    private final boolean nifty;
    private final double share3Min;
    private final double emaK;
    private final int crossLookback;
    private final int stopLookback;
    private final boolean freshCross;
    private final double structureBufferPct;
    private final boolean counterBurst;
    private int heldSide;
    private final int burstMemoryMin;
    private final double share1Min;
    private final double share5Min;
    private final double atrStopMult;
    private final double trailArmAtr;
    private final double trailAtr;
    private double entrySpot = Double.NaN;
    private double entryAtr = Double.NaN;
    private double bestFav = Double.NaN;
    private int lastUpBurst = -1000;
    private int lastDownBurst = -1000;

    private double ema = Double.NaN;
    private int lastMinute = -1;
    private final ArrayDeque<Integer> sides = new ArrayDeque<>();
    private boolean firstEventSeen;

    OiBurstCross(ThresholdConfig c, String underlying) {
        super(c);
        nifty = !"SENSEX".equals(underlying);
        share3Min = c.getDouble("setup.share3_min_" + (nifty ? "nifty" : "sensex"));
        emaK = 2.0 / (c.getInt("setup.ema_period") + 1);
        crossLookback = c.getInt("setup.cross_lookback_min");
        stopLookback = c.getInt("setup.stop_lookback_min");
        freshCross = !c.has("setup.fresh_cross") || c.getBoolean("setup.fresh_cross");
        structureBufferPct = c.has("exits.structure_buffer_pct") ? c.getDouble("exits.structure_buffer_pct") : Double.NaN;
        counterBurst = c.has("exits.counter_burst") && c.getBoolean("exits.counter_burst");
        burstMemoryMin = c.has("setup.burst_memory_min") ? c.getInt("setup.burst_memory_min") : 0;
        atrStopMult = c.has("exits.atr_stop_mult") ? c.getDouble("exits.atr_stop_mult") : Double.NaN;
        trailArmAtr = c.has("exits.trail_arm_atr") ? c.getDouble("exits.trail_arm_atr") : Double.NaN;
        trailAtr = c.has("exits.trail_atr") ? c.getDouble("exits.trail_atr") : Double.NaN;
        String ix = nifty ? "nifty" : "sensex";
        share1Min = burstMemoryMin > 0 ? c.getDouble("setup.share1_min_" + ix) : Double.NaN;
        share5Min = burstMemoryMin > 0 ? c.getDouble("setup.share5_min_" + ix) : Double.NaN;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        OptionsFeatures o = s.options();
        double spot = s.spot();
        int minute = time.getHour() * 60 + time.getMinute();
        if (!Double.isFinite(spot) || minute == lastMinute) {
            return null;                                      // one update per minute
        }
        lastMinute = minute;
        if (Double.isNaN(ema)) {
            ema = Double.isFinite(st.prevClose()) && st.prevClose() > 0 ? st.prevClose() : spot;
        }
        ema += emaK * (spot - ema);
        int side = spot > ema ? 1 : -1;
        sides.addLast(side);
        while (sides.size() > crossLookback + 1) {
            sides.removeFirst();
        }
        boolean mixed = sides.stream().distinct().count() > 1;
        int crossed = mixed || !freshCross ? side : 0;
        double s3 = o.atmBandShare3m(), s1 = o.atmBandShare1m();
        boolean burst;
        if (burstMemoryMin > 0) {                             // A-253: any window at its own threshold, remembered
            double s5 = o.atmBandShare5m();
            boolean up = s1 >= share1Min || s3 >= share3Min || s5 >= share5Min;
            boolean down = s1 <= -share1Min || s3 <= -share3Min || s5 <= -share5Min;   // NaN compares false
            if (up && !down) {
                lastUpBurst = minute;
            } else if (down && !up) {
                lastDownBurst = minute;
            }
            int last = crossed > 0 ? lastUpBurst : lastDownBurst;
            burst = crossed != 0 && minute - last <= burstMemoryMin;
        } else {
            burst = crossed != 0 && Double.isFinite(s3) && Double.isFinite(s1) && s3 * crossed >= share3Min
                    && (nifty ? s1 * crossed > 0 : s1 * crossed >= 0);
        }
        c.put("ema_cross", crossed != 0);
        c.put("oi_burst", burst);
        if (!canEnter || firstEventSeen || !burst) {
            return null;
        }
        firstEventSeen = true;                                // the day's first event, continuation or not
        boolean continuation = Double.isFinite(st.dayOpen()) && Math.signum(spot - st.dayOpen()) == crossed;
        c.put("continuation", continuation);
        if (!continuation) {
            return null;
        }
        double stop = Double.isNaN(structureBufferPct) ? (crossed > 0 ? extreme(false) : extreme(true))
                : structureStop(st, spot, crossed);
        if (Double.isFinite(atrStopMult) && st.atr3m() > 0) {   // A-254: the tighter of the stop and k x ATR3m
            double atrStop = spot - crossed * atrStopMult * st.atr3m();
            stop = Double.isFinite(stop) ? (crossed > 0 ? Math.max(stop, atrStop) : Math.min(stop, atrStop)) : atrStop;
        }
        if (!Double.isFinite(stop) || crossed * (spot - stop) <= 0) {
            return null;
        }
        heldSide = crossed;
        entrySpot = spot;
        entryAtr = st.atr3m();
        bestFav = 0;
        return new Entry(crossed > 0 ? OptionSide.CE : OptionSide.PE, stop, Double.NaN, crossed > 0 ? "OBC_UP" : "OBC_DOWN");
    }

    /** A-252: the invalidation level beyond the entry, moved out by the buffer. */
    private double structureStop(StructureFeatures st, double spot, int side) {
        double or = side < 0 ? (st.orComplete() ? st.orHigh() : st.dayHigh()) : (st.orComplete() ? st.orLow() : st.dayLow());
        double prior = side < 0 ? st.pdh() : st.pdl();
        double level = Double.NaN;
        for (double l : new double[] {prior, or}) {
            if (Double.isFinite(l) && l > 0 && side * (spot - l) > 0 && (Double.isNaN(level) || Math.abs(spot - l) < Math.abs(spot - level))) {
                level = l;
            }
        }
        if (Double.isNaN(level)) {
            level = side < 0 ? st.dayHigh() : st.dayLow();
        }
        return level * (1 - side * structureBufferPct / 100.0);
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        double s3 = s.options().atmBandShare3m();
        if (counterBurst && heldSide != 0 && Double.isFinite(s3) && s3 * heldSide <= -share3Min) {
            return "COUNTER_BURST";
        }
        // A-255: armed at +trail_arm_atr x ATR3m (close basis), exit trail_atr x ATR3m below the best close since entry
        if (Double.isFinite(trailAtr) && heldSide != 0 && entryAtr > 0 && Double.isFinite(s.spot())) {
            double fav = heldSide * (s.spot() - entrySpot);
            bestFav = Math.max(bestFav, fav);
            if (bestFav >= trailArmAtr * entryAtr && fav <= bestFav - trailAtr * entryAtr) {
                return "TRAIL_ATR";
            }
        }
        return null;
    }

    /** The highest / lowest of the last {@code stopLookback} minute closes (this one included). */
    private double extreme(boolean high) {
        double v = Double.NaN;
        for (int back = 0; back < stopLookback; back++) {
            double x = close(back);
            if (Double.isFinite(x)) {
                v = Double.isNaN(v) ? x : high ? Math.max(v, x) : Math.min(v, x);
            }
        }
        return v;
    }
}
