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
 * <p>
 * A-256 ({@code setup.early_until}): before that time the 3-minute share must reach {@code setup.early_share3_min_<index>}
 * instead; a smaller burst then is not an event (it neither enters nor ends the day).
 * <p>
 * A-257 ({@code setup.early_share1_min_<index>}, {@code setup.early_share2_min_<index>}): before {@code early_until} a burst
 * is instead the 1-minute or the 2-minute share at or beyond its own threshold, remembered {@code setup.early_memory_min}
 * minutes; the entry needs a remembered burst on the side the close is on relative to the EMA.
 * <p>
 * A-258 (family {@code oi-burst-allday}; {@code setup.max_entries_per_day} > 1, {@code setup.reversal_ends_day: false}):
 * up to that many entries a day, one position at a time; a burst against the day's direction is skipped instead of
 * ending the day; a re-entry needs a new burst (one that switched on after the last trade ended).
 * <p>
 * A-261 ({@code exits.counter_burst_by_time: true}): the counter-burst exit uses the threshold of the minute it fires in
 * ({@code early_share3_min_*} before {@code early_until}, else {@code share3_min_*}).
 * <p>
 * A-265 ({@code setup.skip_fresh_buildup: true}): no entry when the futures price/OI state shows fresh build-up in the
 * trade's own direction (FRESH_LONG for a call, FRESH_SHORT for a put); that burst is not traded (one-event days end).
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
    private final boolean counterBurstByTime;
    private final boolean skipFreshBuildup;
    private int vetoedBurst = -1000;
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
    private final int earlyUntil;
    private final double earlyShare3Min;
    private final double earlyShare1Min;
    private final double earlyShare2Min;
    private final int earlyMemoryMin;
    private int lastEarlyUp = -1000;
    private int lastEarlyDown = -1000;
    private final int maxEntries;
    private final boolean reversalEndsDay;
    private int entries;
    private boolean inTrade;
    private boolean prevBurst;
    private int burstStart = -1000;
    private int lastExit = -1000;
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
        counterBurstByTime = c.has("exits.counter_burst_by_time") && c.getBoolean("exits.counter_burst_by_time");
        skipFreshBuildup = c.has("setup.skip_fresh_buildup") && c.getBoolean("setup.skip_fresh_buildup");
        burstMemoryMin = c.has("setup.burst_memory_min") ? c.getInt("setup.burst_memory_min") : 0;
        atrStopMult = c.has("exits.atr_stop_mult") ? c.getDouble("exits.atr_stop_mult") : Double.NaN;
        trailArmAtr = c.has("exits.trail_arm_atr") ? c.getDouble("exits.trail_arm_atr") : Double.NaN;
        trailAtr = c.has("exits.trail_atr") ? c.getDouble("exits.trail_atr") : Double.NaN;
        String ix = nifty ? "nifty" : "sensex";
        maxEntries = c.has("setup.max_entries_per_day") ? c.getInt("setup.max_entries_per_day") : 1;
        reversalEndsDay = !c.has("setup.reversal_ends_day") || c.getBoolean("setup.reversal_ends_day");
        if (c.has("setup.early_until")) {
            LocalTime t = LocalTime.parse(c.getString("setup.early_until"));
            earlyUntil = t.getHour() * 60 + t.getMinute();
            earlyShare3Min = c.has("setup.early_share3_min_" + ix) ? c.getDouble("setup.early_share3_min_" + ix) : Double.NaN;
        } else {
            earlyUntil = -1;
            earlyShare3Min = Double.NaN;
        }
        boolean rolling = c.has("setup.early_share1_min_" + ix);
        earlyShare1Min = rolling ? c.getDouble("setup.early_share1_min_" + ix) : Double.NaN;
        earlyShare2Min = rolling ? c.getDouble("setup.early_share2_min_" + ix) : Double.NaN;
        earlyMemoryMin = rolling ? c.getInt("setup.early_memory_min") : 0;
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
        } else if (minute < earlyUntil && earlyMemoryMin > 0) {   // A-257: rolling extreme 1- or 2-minute burst
            double s2 = o.atmBandShare2m();
            boolean up = s1 >= earlyShare1Min || s2 >= earlyShare2Min;
            boolean down = s1 <= -earlyShare1Min || s2 <= -earlyShare2Min;   // NaN compares false
            if (up && !down) {
                lastEarlyUp = minute;
            } else if (down && !up) {
                lastEarlyDown = minute;
            }
            int last = crossed > 0 ? lastEarlyUp : lastEarlyDown;
            burst = crossed != 0 && minute - last <= earlyMemoryMin;
        } else {
            double need = minute < earlyUntil ? earlyShare3Min : share3Min;   // A-256: an extreme burst before early_until
            burst = crossed != 0 && Double.isFinite(s3) && Double.isFinite(s1) && s3 * crossed >= need
                    && (nifty ? s1 * crossed > 0 : s1 * crossed >= 0);
        }
        c.put("ema_cross", crossed != 0);
        c.put("oi_burst", burst);
        if (burst && !prevBurst) {
            burstStart = minute;
        }
        prevBurst = burst;
        if (inTrade && canEnter) {                            // flat again: the last trade ended (or never filled)
            inTrade = false;
            lastExit = minute;
        }
        if (!canEnter || firstEventSeen || !burst) {
            return null;
        }
        if (entries > 0 && burstStart <= lastExit) {
            return null;                                      // A-258: a re-entry needs a new burst
        }
        if (burstStart == vetoedBurst) {
            return null;                                      // A-265: this burst was vetoed
        }
        if (maxEntries == 1) {
            firstEventSeen = true;                            // the day's first event, continuation or not
        }
        boolean continuation = Double.isFinite(st.dayOpen()) && Math.signum(spot - st.dayOpen()) == crossed;
        c.put("continuation", continuation);
        if (!continuation) {
            firstEventSeen |= reversalEndsDay;
            return null;
        }
        if (skipFreshBuildup) {                               // A-265: fresh build-up in the trade's own direction
            boolean buildup = (crossed > 0 ? "FRESH_LONG" : "FRESH_SHORT").equals(s.futures().oiState());
            c.put("fut_oi_veto", buildup);
            if (buildup) {
                vetoedBurst = burstStart;
                return null;
            }
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
        entries++;
        firstEventSeen |= entries >= maxEntries;
        inTrade = true;
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
        int minute = time.getHour() * 60 + time.getMinute();
        double against = counterBurstByTime && minute < earlyUntil ? earlyShare3Min : share3Min;   // A-261
        if (counterBurst && heldSide != 0 && Double.isFinite(s3) && s3 * heldSide <= -against) {
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
