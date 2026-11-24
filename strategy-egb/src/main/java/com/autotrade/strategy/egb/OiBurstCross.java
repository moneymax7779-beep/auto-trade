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
 * <p>
 * A-266 ({@code setup.skip_gap_continuation_pct}): no entry in the direction of the day's opening gap when the gap
 * (open vs previous close) is at least that many percent; entries against the gap are unaffected; the burst is not traded.
 */
final class OiBurstCross extends SetupStrategy {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OiBurstCross.class);

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
    private final double skipGapPct;
    /**
     * A-437 obc-v25: skip the burst when a known level (day open, opening range, prior day high / low / close, prior week and
     * month high / low) is within this x EM of the spot, either side; EM = day open x first VIX / 100 / sqrt(252). NaN = off.
     */
    private final double levelClearEm;
    private double firstVix = Double.NaN;
    /**
     * A-465 obc-v26: before chase_veto_until, skip a burst when the index has already moved more than this x EM (day open x
     * first VIX / 100 / sqrt(252)) with it since the open and the futures OI is above its first value of the session (fresh
     * buildup behind a move already made: a chase). NaN = off.
     */
    private final double chaseVetoEm;
    /** A-471: no PE (CE) entry when the dealer-gamma proxy gexShare is above this (NaN = off). */
    private final double gexVetoPe, gexVetoCe;
    /** A-591: skip a burst when futures RVOL (time of day) is below this; NaN = off; a missing value passes. */
    private final double minFutRvol;
    /** A-591: read NIFTY's futures RVOL for both indexes (SENSEX futures RVOL is missing early on some days). */
    private final boolean futRvolFromNifty;
    /** A-591: NIFTY's latest futures RVOL per session date: {minute of day, rvol}; written by the NIFTY instance. */
    static final java.util.concurrent.ConcurrentHashMap<String, double[]> NIFTY_RVOL = new java.util.concurrent.ConcurrentHashMap<>();
    private final LocalTime chaseVetoUntil;
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
    /** A-583: skip a burst when the index is more than this many ATR3m beyond obc's EMA in the trade's direction (NaN = off). */
    private final double maxEmaDistAtr;
    /** A-584 (pasted Combo 1): this index's VWAP-extension cap in ATR3m (NaN = off), and the put veto on fresh futures longs. */
    private final double maxVwapExtAtr;
    private final boolean vetoPeFreshLong;
    /** A-587: per-print burst (1-minute share from the 09:18 decision, 3-minute rule from 09:20) and the EMA seeded at the open. */
    private final boolean printMode;
    private final double printShareMin;
    /** A-595: burst against today's own flow: s1 or s3 at least relative_k x the median |value| of today's earlier minutes with a print. */
    private final boolean relativeMode;
    private final double relativeK;
    private final int relativeMinSamples;
    private final java.util.List<Double> dayAbsS1 = new java.util.ArrayList<>();
    private final java.util.List<Double> dayAbsS3 = new java.util.ArrayList<>();
    /** A-596: first decision minutes of the per-print and 3-minute rules (print / relative modes; defaults 09:18 / 09:20). */
    private final int printFrom;
    private final int windowFrom;
    /** A-596: enter only while the ATM option of the trade's side is rising (mid, 1- and 3-minute change > 0). */
    private final boolean premiumRisingVeto;
    /** A-596: level-to-level exits: stall at an unbroken level -> exit; once broken, hold to the next; a close back -> exit. */
    private final boolean levelMode;
    private final double levelTouchAtr;
    private final double levelBreakPct;
    private final int levelStallMin;
    private double brokenLevel = Double.NaN;
    private int touchedAt = -1;
    private double levelTarget = Double.NaN;
    /** A-597: exit when the day's ATM OI build-up (sum of 1-minute net shares from 09:16) turns 20 % off its peak since entry. */
    private final boolean oiTrendExit;
    private final double oiTrendRetrace;
    private double dayBuildUp;
    private double buildUpPeak = Double.NaN;
    /** A-603: after an index-STOP exit, one same-side re-entry when a minute closes beyond the day's extreme at the stop. */
    private final boolean reentryAfterStop;
    private boolean reentryArmed;
    private boolean reentryUsed;
    private int reSide;
    private double reExtreme = Double.NaN;
    /** A-618: after a STOP, the opposite side may also re-enter on a close beyond the day's opposite extreme (first trigger wins). */
    private final boolean reentryReverse;
    private boolean reverseArmed;
    private double revExtreme = Double.NaN;
    /** A-621: entry burst thresholds scaled (exits keep the full share3_min); 1 = off. */
    private final double entryShareScale;
    /** A-622: skip a burst whose half-strength flow began more than this many minutes before (0 = off). */
    private final int staleBurstMaxMin;
    private final java.util.List<double[]> s3History = new java.util.ArrayList<>();   // [minute, s3, spot]
    /** A-630: the day's first entry only before this minute (-1 = scope window only). */
    private final int firstEntryUntil;
    /** A-630: second and later entries: windows where strong data is enough; elsewhere only excellent data; a gap after the last exit. */
    private final int[][] secondWindows;
    private final int secondMinGapMin;
    private final double secondStrongScale;
    private final double secondExcellentScale;
    private final double secondMinFutRvol;
    private final boolean secondRequireRising;
    /** A-634: once an index has traded one way today, no entry the other way. */
    private final boolean noSideFlip;
    private int daySide;
    /** A-634: no new entry in this index after this many losing trades today (0 = off); a loser = last bid below the price paid. */
    private final int maxLosersPerDay;
    /** A-635: further entries need the day's and the 1/3/5/10-minute OI flow with them, and volume and / or the EMA. */
    private final boolean secondExcellentOi;
    private final String secondConfirm;                       // none | and | or
    private final int secondEmaSlopeMin;
    private final java.util.ArrayDeque<Double> emaHistory = new java.util.ArrayDeque<>();
    /** A-635: a further trade is held while its structure holds: stop, a close beyond the 10-minute extreme, flat_by. */
    private final boolean holdStructureFurther;
    private int heldEntryNo;
    /** A-636: a tighter give-back once the best close is this many ATR in favour; a stall exit once armed. */
    private final double trailTightAfterAtr;
    private final double trailTightAtr;
    private final int stallExitMin;
    private int bestMinute;
    private int losers;
    private double lastBid = Double.NaN;
    private double lastAvg = Double.NaN;
    /** A-616: an entry the engine refused or never filled gives back its place in the day's allowance (a new burst is still needed). */
    private final boolean refusedEntryRefund;
    private boolean everOpen;
    private boolean firstEventAtEntry;
    private int levelMinute = -1;
    private final boolean emaSeedOpen;
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
        skipGapPct = c.has("setup.skip_gap_continuation_pct") ? c.getDouble("setup.skip_gap_continuation_pct") : Double.NaN;
        levelClearEm = c.has("setup.level_clear_em") ? c.getDouble("setup.level_clear_em") : Double.NaN;
        chaseVetoEm = c.has("setup.chase_veto_em") ? c.getDouble("setup.chase_veto_em") : Double.NaN;
        gexVetoPe = c.has("setup.gex_veto_pe") ? c.getDouble("setup.gex_veto_pe") : Double.NaN;
        gexVetoCe = c.has("setup.gex_veto_ce") ? c.getDouble("setup.gex_veto_ce") : Double.NaN;
        minFutRvol = c.has("setup.min_fut_rvol") ? c.getDouble("setup.min_fut_rvol") : Double.NaN;
        String rvolSource = c.has("setup.fut_rvol_source") ? c.getString("setup.fut_rvol_source") : "own";
        if (!rvolSource.equals("own") && !rvolSource.equals("nifty")) {
            throw new IllegalArgumentException("setup.fut_rvol_source must be own or nifty, not " + rvolSource);
        }
        futRvolFromNifty = rvolSource.equals("nifty");
        chaseVetoUntil = c.has("setup.chase_veto_until") ? c.getTime("setup.chase_veto_until") : LocalTime.of(9, 45);
        burstMemoryMin = c.has("setup.burst_memory_min") ? c.getInt("setup.burst_memory_min") : 0;
        atrStopMult = c.has("exits.atr_stop_mult") ? c.getDouble("exits.atr_stop_mult") : Double.NaN;
        trailArmAtr = c.has("exits.trail_arm_atr") ? c.getDouble("exits.trail_arm_atr") : Double.NaN;
        trailAtr = c.has("exits.trail_atr") ? c.getDouble("exits.trail_atr") : Double.NaN;
        String ix = nifty ? "nifty" : "sensex";
        maxEntries = c.has("setup.max_entries_per_day") ? c.getInt("setup.max_entries_per_day") : 1;
        reversalEndsDay = !c.has("setup.reversal_ends_day") || c.getBoolean("setup.reversal_ends_day");
        maxEmaDistAtr = c.has("setup.max_ema_dist_atr") ? c.getDouble("setup.max_ema_dist_atr") : Double.NaN;
        maxVwapExtAtr = c.has("setup.max_vwap_ext_atr_" + ix) ? c.getDouble("setup.max_vwap_ext_atr_" + ix) : Double.NaN;
        vetoPeFreshLong = c.has("setup.veto_pe_fresh_long") && c.getBoolean("setup.veto_pe_fresh_long");
        String burstMode = c.has("setup.burst_mode") ? c.getString("setup.burst_mode") : "window";
        if (!burstMode.equals("window") && !burstMode.equals("print") && !burstMode.equals("relative")) {
            throw new IllegalArgumentException("setup.burst_mode must be window, print or relative, not " + burstMode);
        }
        printMode = burstMode.equals("print");
        relativeMode = burstMode.equals("relative");
        printFrom = c.has("setup.print_from") ? minuteOf(c.getString("setup.print_from")) : 9 * 60 + 18;
        windowFrom = c.has("setup.window_from") ? minuteOf(c.getString("setup.window_from")) : 9 * 60 + 20;
        premiumRisingVeto = c.has("setup.premium_rising_veto") && c.getBoolean("setup.premium_rising_veto");
        levelMode = c.has("exits.level_mode") && c.getBoolean("exits.level_mode");
        oiTrendExit = c.has("exits.oi_trend_exit") && c.getBoolean("exits.oi_trend_exit");
        reentryAfterStop = c.has("setup.reentry_after_stop_new_extreme") && c.getBoolean("setup.reentry_after_stop_new_extreme");
        refusedEntryRefund = c.has("setup.refused_entry_refund") && c.getBoolean("setup.refused_entry_refund");
        reentryReverse = c.has("setup.reentry_reverse_after_stop") && c.getBoolean("setup.reentry_reverse_after_stop");
        entryShareScale = c.has("setup.entry_share_scale") ? c.getDouble("setup.entry_share_scale") : 1.0;
        staleBurstMaxMin = c.has("setup.stale_burst_max_min") ? c.getInt("setup.stale_burst_max_min") : 0;
        firstEntryUntil = c.has("setup.first_entry_until") ? minuteOf(c.getString("setup.first_entry_until")) : -1;
        if (c.has("setup.second_entry_windows")) {
            String[] parts = c.getString("setup.second_entry_windows").split(",");
            secondWindows = new int[parts.length][2];
            for (int i = 0; i < parts.length; i++) {
                String[] ab = parts[i].trim().split("-");
                secondWindows[i][0] = minuteOf(ab[0].trim());
                secondWindows[i][1] = minuteOf(ab[1].trim());
            }
        } else {
            secondWindows = null;
        }
        secondMinGapMin = c.has("setup.second_entry_min_gap_min") ? c.getInt("setup.second_entry_min_gap_min") : 0;
        secondStrongScale = c.has("setup.second_entry_strong_scale") ? c.getDouble("setup.second_entry_strong_scale") : 1.0;
        secondExcellentScale = c.has("setup.second_entry_excellent_scale") ? c.getDouble("setup.second_entry_excellent_scale") : 1.5;
        secondMinFutRvol = c.has("setup.second_entry_min_fut_rvol") ? c.getDouble("setup.second_entry_min_fut_rvol") : Double.NaN;
        secondRequireRising = !c.has("setup.second_entry_require_rising") || c.getBoolean("setup.second_entry_require_rising");
        noSideFlip = c.has("setup.no_side_flip") && c.getBoolean("setup.no_side_flip");
        maxLosersPerDay = c.has("setup.max_losers_per_day") ? c.getInt("setup.max_losers_per_day") : 0;
        secondExcellentOi = c.has("setup.second_entry_excellent_oi") && c.getBoolean("setup.second_entry_excellent_oi");
        secondConfirm = c.has("setup.second_entry_confirm") ? c.getString("setup.second_entry_confirm") : "none";
        if (!java.util.Set.of("none", "and", "or").contains(secondConfirm)) {
            throw new IllegalArgumentException("setup.second_entry_confirm must be none, and or or, not " + secondConfirm);
        }
        secondEmaSlopeMin = c.has("setup.second_entry_ema_slope_min") ? c.getInt("setup.second_entry_ema_slope_min") : 5;
        holdStructureFurther = c.has("exits.hold_structure_further") && c.getBoolean("exits.hold_structure_further");
        trailTightAfterAtr = c.has("exits.trail_tight_after_atr") ? c.getDouble("exits.trail_tight_after_atr") : Double.NaN;
        trailTightAtr = c.has("exits.trail_tight_atr") ? c.getDouble("exits.trail_tight_atr") : Double.NaN;
        stallExitMin = c.has("exits.stall_exit_min") ? c.getInt("exits.stall_exit_min") : 0;
        if (!(entryShareScale > 0)) {
            throw new IllegalArgumentException("setup.entry_share_scale must be > 0");
        }
        oiTrendRetrace = oiTrendExit ? c.getDouble("exits.oi_trend_retrace") : Double.NaN;
        if (oiTrendExit && !(oiTrendRetrace > 0 && oiTrendRetrace < 1)) {
            throw new IllegalArgumentException("exits.oi_trend_retrace must be between 0 and 1");
        }
        levelTouchAtr = levelMode ? c.getDouble("exits.level_touch_atr") : Double.NaN;
        levelBreakPct = levelMode ? c.getDouble("exits.level_break_pct") : Double.NaN;
        levelStallMin = levelMode ? c.getInt("exits.level_stall_min") : 0;
        relativeK = relativeMode ? c.getDouble("setup.relative_k") : Double.NaN;
        relativeMinSamples = relativeMode ? c.getInt("setup.relative_min_samples") : 0;
        if (relativeMode && (!(relativeK > 0) || relativeMinSamples < 1)) {
            throw new IllegalArgumentException("setup.relative_k must be > 0 and setup.relative_min_samples >= 1");
        }
        printShareMin = printMode ? c.getDouble("setup.print_share_min_" + ix) : Double.NaN;
        String emaSeed = c.has("setup.ema_seed") ? c.getString("setup.ema_seed") : "prev_close";
        if (!emaSeed.equals("prev_close") && !emaSeed.equals("open")) {
            throw new IllegalArgumentException("setup.ema_seed must be prev_close or open, not " + emaSeed);
        }
        emaSeedOpen = emaSeed.equals("open");
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
        if (Double.isNaN(firstVix) && s.volatility() != null && Double.isFinite(s.volatility().vix())) {
            firstVix = s.volatility().vix();                  // A-437: as sbr's EM
        }
        if (!Double.isFinite(spot) || minute == lastMinute) {
            return null;                                      // one update per minute
        }
        lastMinute = minute;
        double ownRvol = s.futures() == null ? Double.NaN : s.futures().rvolTod();
        if (nifty && Double.isFinite(ownRvol) && s.session() != null) {
            NIFTY_RVOL.put(s.session().toString(), new double[] {minute, ownRvol});   // A-591
        }
        if (Double.isNaN(ema)) {
            if (emaSeedOpen && Double.isFinite(st.dayOpen()) && st.dayOpen() > 0) {      // A-587
                ema = st.dayOpen();
            } else {
                ema = Double.isFinite(st.prevClose()) && st.prevClose() > 0 ? st.prevClose() : spot;
            }
        }
        ema += emaK * (spot - ema);
        emaHistory.addLast(ema);                              // A-635: one value per minute
        while (emaHistory.size() > 31) {
            emaHistory.removeFirst();
        }
        int side = spot > ema ? 1 : -1;
        sides.addLast(side);
        while (sides.size() > crossLookback + 1) {
            sides.removeFirst();
        }
        boolean mixed = sides.stream().distinct().count() > 1;
        int crossed = mixed || !freshCross ? side : 0;
        double s3 = o.atmBandShare3m(), s1 = o.atmBandShare1m();
        if (minute >= 9 * 60 + 16 && Double.isFinite(s1)) {
            dayBuildUp += s1;                                 // A-597: the day's net OI build-up (puts minus calls)
        }
        if (staleBurstMaxMin > 0) {
            s3History.add(new double[] {minute, Double.isFinite(s3) ? s3 : Double.NaN, spot});   // A-622
        }
        final double share3Entry = share3Min * entryShareScale;          // A-621
        final double printEntry = printShareMin * entryShareScale;
        boolean burst;
        if (relativeMode) {                                   // A-595: against today's own earlier minutes
            boolean byPrint = false;
            boolean byWindow = false;
            if (minute >= printFrom && Double.isFinite(s1)) {
                byPrint = dayAbsS1.size() >= relativeMinSamples && s1 * crossed > 0
                        && s1 * crossed >= relativeK * median(dayAbsS1);
                if (s1 != 0) {
                    dayAbsS1.add(Math.abs(s1));                // minutes with an OI print only (SENSEX prints ~every 162 s)
                }
            }
            if (minute >= windowFrom && Double.isFinite(s3)) {
                byWindow = dayAbsS3.size() >= relativeMinSamples && Double.isFinite(s1) && s3 * crossed > 0
                        && s3 * crossed >= relativeK * median(dayAbsS3) && (nifty ? s1 * crossed > 0 : s1 * crossed >= 0);
                if (s3 != 0) {
                    dayAbsS3.add(Math.abs(s3));
                }
            }
            burst = crossed != 0 && (byPrint || byWindow);
        } else if (printMode) {                                      // A-587: the latest print, opening prints excluded
            boolean byPrint = minute >= printFrom && Double.isFinite(s1) && s1 * crossed >= printEntry;
            boolean byWindow = minute >= windowFrom && Double.isFinite(s3) && Double.isFinite(s1)
                    && s3 * crossed >= share3Entry && (nifty ? s1 * crossed > 0 : s1 * crossed >= 0);
            burst = crossed != 0 && (byPrint || byWindow);
        } else if (burstMemoryMin > 0) {                             // A-253: any window at its own threshold, remembered
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
            double need = minute < earlyUntil ? earlyShare3Min : share3Entry;   // A-256 / A-621
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
            if (refusedEntryRefund && !everOpen) {           // A-616: refused / never filled: not one of the day's entries
                entries = Math.max(0, entries - 1);
                firstEventSeen = firstEventAtEntry;
                c.put("refused_entry_refunded", true);
            }
            if ((reentryAfterStop || reentryReverse) && !reentryUsed && "STOP".equals(lastExitReason)) {   // A-603 / A-618
                reentryArmed = reentryAfterStop;
                reverseArmed = reentryReverse;
                reentryUsed = true;
                reSide = heldSide;
                reExtreme = heldSide > 0 ? st.dayHigh() : st.dayLow();
                revExtreme = heldSide > 0 ? st.dayLow() : st.dayHigh();   // the side the stop went to
            }
            lastExitReason = null;
        }
        if (reverseArmed && canEnter) {                       // A-618: the direction changed: the other side
            double close = st.lastMinuteClose();
            boolean beyond = Double.isFinite(close) && Double.isFinite(revExtreme) && -reSide * (close - revExtreme) > 0;
            c.put("reentry_reverse", beyond);
            if (beyond) {
                boolean sameArmed = reentryArmed;
                reentryArmed = false;
                reverseArmed = false;
                reSide = -reSide;
                Entry e = reentry(s, st, spot, minute, c);
                if (e == null) {
                    reSide = -reSide;                         // not entered (premium check / stop): both stay armed
                    reverseArmed = true;
                    reentryArmed = sameArmed;
                }
                return e;
            }
        }
        if (reentryArmed && canEnter) {                       // A-603: the trend the stop shook us out of resumes
            double close = st.lastMinuteClose();
            boolean beyond = Double.isFinite(close) && Double.isFinite(reExtreme) && reSide * (close - reExtreme) > 0;
            c.put("reentry_new_extreme", beyond);
            if (beyond) {
                boolean revArmed = reverseArmed;
                reverseArmed = false;                         // A-618: the first trigger wins
                Entry e = reentry(s, st, spot, minute, c);
                if (e == null) {
                    reverseArmed = revArmed;
                }
                return e;
            }
        }
        if (!canEnter || firstEventSeen || !burst) {
            return null;
        }
        if (entries > 0 && burstStart <= lastExit) {
            return null;                                      // A-258: a re-entry needs a new burst
        }
        if (maxLosersPerDay > 0 && losers >= maxLosersPerDay) {
            c.put("max_losers", true);
            return null;                                      // A-634: this index is done for the day
        }
        if (noSideFlip && daySide != 0 && crossed != daySide) {
            c.put("side_flip", true);
            return null;                                      // A-634: not the other way today
        }
        if (firstEntryUntil >= 0 && entries == 0 && minute >= firstEntryUntil) {
            return null;                                      // A-630: the day's first entry is a morning entry
        }
        if (secondWindows != null && entries > 0) {           // A-630: a further entry needs a gap and strong data
            if (minute - lastExit < secondMinGapMin) {
                c.put("second_gap", false);
                return null;
            }
            boolean inWindow = false;
            for (int[] w : secondWindows) {
                inWindow |= minute >= w[0] && minute < w[1];
            }
            double need = (inWindow ? secondStrongScale : secondExcellentScale) * share3Min;
            boolean flow = Double.isFinite(s3) && s3 * crossed >= need;
            double rv = s.futures() == null ? Double.NaN : s.futures().rvolTod();
            if (!Double.isFinite(rv) && !nifty && s.session() != null) {   // SENSEX futures RVOL is often missing: NIFTY's (A-591)
                double[] n = NIFTY_RVOL.get(s.session().toString());
                rv = n != null && minute - n[0] <= 2 ? n[1] : Double.NaN;
            }
            boolean rvolOk = Double.isFinite(rv) && Double.isFinite(secondMinFutRvol) && rv >= secondMinFutRvol;
            boolean volume = Double.isNaN(secondMinFutRvol) || rvolOk;
            if (!secondConfirm.equals("none")) {              // A-635: volume and / or the EMA sloping with the trade
                boolean emaSlope = emaHistory.size() > secondEmaSlopeMin
                        && crossed * (ema - emaAgo(secondEmaSlopeMin)) > 0;
                c.put("second_ema_slope", emaSlope);
                c.put("second_rvol", rvolOk);
                volume = secondConfirm.equals("and") ? rvolOk && emaSlope : rvolOk || emaSlope;
            }
            if (secondExcellentOi) {                          // A-635: the day's and every window's OI flow with the trade
                boolean oi = crossed * dayBuildUp > 0
                        && aligned(o.peOiChange1m() - o.ceOiChange1m(), crossed, !nifty)
                        && aligned(o.peOiChange3m() - o.ceOiChange3m(), crossed, false)
                        && aligned(o.peOiChange5m() - o.ceOiChange5m(), crossed, false)
                        && aligned(o.peOiChange10m() - o.ceOiChange10m(), crossed, false);
                c.put("second_excellent_oi", oi);
                volume &= oi;
            }
            com.autotrade.features.snapshot.PremiumFeatures pf = s.premium();
            double ch1 = pf == null ? Double.NaN : crossed > 0 ? pf.ceChange1mPct() : pf.peChange1mPct();
            double ch3 = pf == null ? Double.NaN : crossed > 0 ? pf.ceChange3mPct() : pf.peChange3mPct();
            boolean rising = !secondRequireRising || ((Double.isFinite(ch1) || Double.isFinite(ch3))
                    && (!Double.isFinite(ch1) || ch1 > 0) && (!Double.isFinite(ch3) || ch3 > 0));
            c.put("second_entry_strong", flow && volume && rising);
            if (!(flow && volume && rising)) {
                return null;                                  // not vetoed: a later minute of this burst may qualify
            }
        }
        if (burstStart == vetoedBurst) {
            return null;                                      // A-265: this burst was vetoed
        }
        boolean firstEventBefore = firstEventSeen;           // A-616
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
        if (Double.isFinite(skipGapPct) && st.prevClose() > 0 && Double.isFinite(st.dayOpen())) {   // A-266
            boolean gapChase = crossed * (st.dayOpen() - st.prevClose()) / st.prevClose() * 100 >= skipGapPct;
            c.put("gap_veto", gapChase);
            if (gapChase) {
                vetoedBurst = burstStart;
                return null;
            }
        }
        if (Double.isFinite(levelClearEm)) {                 // A-437 obc-v25: not sitting on a level
            double em = st.dayOpen() * firstVix / 100 / Math.sqrt(252);
            boolean near = false;
            if (Double.isFinite(em)) {
                for (double y : new double[] {st.dayOpen(), st.orHigh(), st.orLow(), st.pdh(), st.pdl(), st.prevClose(),
                        st.pwh(), st.pwl(), st.pmh(), st.pml()}) {
                    near |= Double.isFinite(y) && Math.abs(spot - y) < levelClearEm * em;
                }
            }
            c.put("level_clear", !near);
            if (near) {
                vetoedBurst = burstStart;
                return null;
            }
        }
        if (Double.isFinite(chaseVetoEm) && time.isBefore(chaseVetoUntil)) {      // A-465 obc-v26
            double em = st.dayOpen() * firstVix / 100 / Math.sqrt(252);
            double move = crossed * (spot - st.dayOpen()) / em;
            double oiDay = s.futures() == null ? Double.NaN : s.futures().oiChangeDay();
            boolean chase = Double.isFinite(move) && move > chaseVetoEm && Double.isFinite(oiDay) && oiDay > 0;
            c.put("chase_veto", chase);
            if (chase) {
                vetoedBurst = burstStart;
                return null;
            }
        }
        if (Double.isFinite(maxVwapExtAtr) && Double.isFinite(st.distVwapAtr())) {   // A-584: stretched from VWAP
            boolean stretched = crossed * st.distVwapAtr() > maxVwapExtAtr;
            c.put("vwap_ext_veto", stretched);
            if (stretched) {
                vetoedBurst = burstStart;
                return null;
            }
        }
        if (vetoPeFreshLong && crossed < 0 && s.futures() != null && "FRESH_LONG".equals(s.futures().oiState())) {   // A-584
            c.put("fut_fresh_long_veto", true);
            vetoedBurst = burstStart;
            return null;
        }
        if (Double.isFinite(maxEmaDistAtr) && st.atr3m() > 0) {   // A-583: already stretched from the EMA
            double dist = crossed * (spot - ema) / st.atr3m();
            boolean stretched = dist > maxEmaDistAtr;
            c.put("ema_stretch_veto", stretched);
            if (stretched) {
                vetoedBurst = burstStart;
                return null;
            }
        }
        if (Double.isFinite(minFutRvol)) {                   // A-591: an OI burst without futures participation
            double rvol = ownRvol;
            if (futRvolFromNifty && !nifty) {
                double[] n = s.session() == null ? null : NIFTY_RVOL.get(s.session().toString());
                rvol = n != null && minute - n[0] <= 2 ? n[1] : Double.NaN;
            }
            boolean low = Double.isFinite(rvol) && rvol < minFutRvol;
            c.put("fut_rvol_veto", low);
            if (low) {
                vetoedBurst = burstStart;
                return null;
            }
        }
        if (staleBurstMaxMin > 0) {                           // A-622: the flow began long before this burst qualified
            int stale = staleMinutes(crossed, minute);
            boolean isStale = stale > staleBurstMaxMin;
            c.put("stale_burst", isStale);
            if (isStale) {
                vetoedBurst = burstStart;
                return null;
            }
        }
        if (premiumRisingVeto) {                             // A-596: the option we would buy must already be rising
            com.autotrade.features.snapshot.PremiumFeatures pf = s.premium();
            double ch1 = pf == null ? Double.NaN : crossed > 0 ? pf.ceChange1mPct() : pf.peChange1mPct();
            double ch3 = pf == null ? Double.NaN : crossed > 0 ? pf.ceChange3mPct() : pf.peChange3mPct();
            boolean rising = (Double.isFinite(ch1) || Double.isFinite(ch3))
                    && (!Double.isFinite(ch1) || ch1 > 0) && (!Double.isFinite(ch3) || ch3 > 0);
            c.put("premium_rising", rising);
            if (!rising) {
                return null;                                  // not a veto of the burst: a later minute of it may pass
            }
        }
        double gexLimit = crossed > 0 ? gexVetoCe : gexVetoPe;
        if (Double.isFinite(gexLimit)) {                     // A-471: long dealer gamma absorbs the burst
            double gex = s.options() == null ? Double.NaN : s.options().gexShare();
            boolean veto = Double.isFinite(gex) && gex > gexLimit;
            c.put("gex_veto", veto);
            log.info("A471GEX {} {} side {} gex {}", s.underlying(), time, crossed > 0 ? "CE" : "PE", gex);
            if (veto) {
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
        heldEntryNo = entries;                                // A-635
        firstEventAtEntry = firstEventBefore;                 // A-616: what a refund restores
        everOpen = false;
        firstEventSeen |= entries >= maxEntries;
        inTrade = true;
        heldSide = crossed;
        entrySpot = spot;
        entryAtr = st.atr3m();
        bestFav = 0;
        bestMinute = minute;                                  // A-636
        brokenLevel = Double.NaN;                             // A-596
        touchedAt = -1;
        levelTarget = Double.NaN;
        levelMinute = minute;
        buildUpPeak = Double.NaN;                             // A-597
        return new Entry(crossed > 0 ? OptionSide.CE : OptionSide.PE, stop, Double.NaN, crossed > 0 ? "OBC_UP" : "OBC_DOWN");
    }

    /** A-635: this trade is a further entry held by structure only. */
    @Override
    protected boolean holdOnlyStructure() {
        return holdStructureFurther && heldEntryNo > 1 && heldSide != 0;
    }

    private double emaAgo(int minutes) {
        var it = emaHistory.descendingIterator();
        double v = Double.NaN;
        for (int i = 0; i <= minutes && it.hasNext(); i++) {
            v = it.next();
        }
        return v;
    }

    /** OI net flow (puts minus calls) with the trade; zero allowed where prints are sparse (SENSEX 1 minute). */
    private static boolean aligned(double net, int side, boolean zeroOk) {
        return Double.isFinite(net) && (zeroOk ? side * net >= 0 : side * net > 0);
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
    public com.autotrade.strategy.Decision decide(FeatureSnapshot s, com.autotrade.strategy.PositionView position) {
        if (position.open()) {
            everOpen = true;                                  // A-616: the entry filled
            if (daySide == 0 && position.side() != null) {
                daySide = position.side() == com.autotrade.strategy.OptionSide.CE ? 1 : -1;   // A-634: the day's side (a filled trade)
            }
            if (position.bid() > 0 && position.averagePremium() > 0) {   // A-634: how the open trade stands
                lastBid = position.bid();
                lastAvg = position.averagePremium();
            }
        } else if (Double.isFinite(lastAvg)) {
            if (lastBid < lastAvg) {
                losers++;                                     // A-634: the trade that just closed lost
            }
            lastBid = Double.NaN;
            lastAvg = Double.NaN;
        }
        return super.decide(s, position);
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        double s3 = s.options().atmBandShare3m();
        int minute = time.getHour() * 60 + time.getMinute();
        double against = counterBurstByTime && minute < earlyUntil ? earlyShare3Min : share3Min;   // A-261
        if (holdOnlyStructure()) {                            // A-635: a further trade, held while its structure holds
            double now = close(0), prev = Double.NaN;
            for (int back = 1; back <= stopLookback; back++) {
                double x = close(back);
                if (Double.isFinite(x)) {
                    prev = Double.isNaN(prev) ? x : heldSide > 0 ? Math.min(prev, x) : Math.max(prev, x);
                }
            }
            return Double.isFinite(now) && Double.isFinite(prev) && heldSide * (now - prev) < 0 ? "STRUCTURE_BREAK" : null;
        }
        if (counterBurst && heldSide != 0 && Double.isFinite(s3) && s3 * heldSide <= -against) {
            return "COUNTER_BURST";
        }
        if (oiTrendExit && heldSide != 0) {                   // A-597: the build-up turned against the trade
            double gap = heldSide * dayBuildUp;
            buildUpPeak = Double.isNaN(buildUpPeak) ? gap : Math.max(buildUpPeak, gap);
            if (buildUpPeak > 0 && gap <= (1 - oiTrendRetrace) * buildUpPeak) {
                return "OI_TURN";
            }
        }
        if (levelMode && heldSide != 0) {
            String lv = levelExit(s.structure(), minute);
            if (lv != null) {
                return lv;
            }
        }
        // A-255: armed at +trail_arm_atr x ATR3m (close basis), exit trail_atr x ATR3m below the best close since entry
        if (Double.isFinite(trailAtr) && !(levelMode && Double.isFinite(brokenLevel)) && heldSide != 0 && entryAtr > 0 && Double.isFinite(s.spot())) {
            double fav = heldSide * (s.spot() - entrySpot);
            if (fav > bestFav) {
                bestFav = fav;
                bestMinute = minute;                          // A-636: when the best close was made
            }
            boolean tight = Double.isFinite(trailTightAfterAtr) && Double.isFinite(trailTightAtr)
                    && bestFav >= trailTightAfterAtr * entryAtr;      // A-636: once well in favour, give back less
            if (bestFav >= trailArmAtr * entryAtr && fav <= bestFav - (tight ? trailTightAtr : trailAtr) * entryAtr) {
                return "TRAIL_ATR";
            }
            if (stallExitMin > 0 && bestFav >= trailArmAtr * entryAtr && minute - bestMinute >= stallExitMin) {
                return "STALL";                               // A-636: armed, and no new best close for stall_exit_min
            }
        }
        return null;
    }

    /**
     * A-596, judged once per minute on the last completed minute's close: the next level is the nearest of day open,
     * opening range (once complete), previous close / high / low, previous day's opening range, previous week and month
     * high / low beyond the entry (or beyond the last broken level) in the trade's direction. A close within
     * level_touch_atr x ATR3m of it starts the clock; a close beyond it by level_break_pct % breaks it (it becomes the
     * floor and the next level is the target); no break within level_stall_min minutes of the touch exits LEVEL_STALL;
     * after a break, a close back beyond the broken level by level_break_pct % exits LEVEL_LOST.
     */
    private String levelExit(StructureFeatures st, int minute) {
        if (st == null || minute == levelMinute) {
            return null;
        }
        levelMinute = minute;
        double close = st.lastMinuteClose();
        if (!Double.isFinite(close) || close <= 0) {
            return null;
        }
        double brk = levelBreakPct / 100.0;
        if (Double.isFinite(brokenLevel) && heldSide * (close - brokenLevel) < -brk * brokenLevel) {
            return "LEVEL_LOST";
        }
        double ref = Double.isFinite(brokenLevel) ? brokenLevel : entrySpot;
        double target = Double.NaN;
        double[] levels = {st.dayOpen(), st.orComplete() ? st.orHigh() : Double.NaN, st.orComplete() ? st.orLow() : Double.NaN,
                st.prevClose(), st.pdh(), st.pdl(), st.prevOrHigh(), st.prevOrLow(), st.pwh(), st.pwl(), st.pmh(), st.pml()};
        for (double l : levels) {
            if (Double.isFinite(l) && l > 0 && heldSide * (l - ref) > 0
                    && (Double.isNaN(target) || heldSide * (l - target) < 0)) {
                target = l;
            }
        }
        if (Double.isNaN(target)) {
            return null;                                      // nothing ahead: the other exits manage it
        }
        if (target != levelTarget) {                          // a new target (a break, or the opening range completing)
            levelTarget = target;
            touchedAt = -1;
        }
        if (heldSide * (close - target) >= brk * target) {   // broken: hold on to the next level
            brokenLevel = target;
            touchedAt = -1;
            return null;
        }
        double band = levelTouchAtr * (entryAtr > 0 ? entryAtr : st.atr3m());
        if (touchedAt < 0 && heldSide * (target - close) <= band) {
            touchedAt = minute;
        }
        if (touchedAt >= 0 && minute - touchedAt >= levelStallMin) {
            return "LEVEL_STALL";
        }
        return null;
    }

    /** A-603: the re-entry, with the same premium-rising check (when on) and the same stop rules as an entry. */
    private Entry reentry(FeatureSnapshot s, StructureFeatures st, double spot, int minute, Map<String, Boolean> c) {
        if (premiumRisingVeto) {
            com.autotrade.features.snapshot.PremiumFeatures pf = s.premium();
            double ch1 = pf == null ? Double.NaN : reSide > 0 ? pf.ceChange1mPct() : pf.peChange1mPct();
            double ch3 = pf == null ? Double.NaN : reSide > 0 ? pf.ceChange3mPct() : pf.peChange3mPct();
            boolean rising = (Double.isFinite(ch1) || Double.isFinite(ch3))
                    && (!Double.isFinite(ch1) || ch1 > 0) && (!Double.isFinite(ch3) || ch3 > 0);
            c.put("premium_rising", rising);
            if (!rising) {
                return null;                                  // still armed: a later minute may pass
            }
        }
        double stop = Double.isNaN(structureBufferPct) ? (reSide > 0 ? extreme(false) : extreme(true))
                : structureStop(st, spot, reSide);
        if (Double.isFinite(atrStopMult) && st.atr3m() > 0) {
            double atrStop = spot - reSide * atrStopMult * st.atr3m();
            stop = Double.isFinite(stop) ? (reSide > 0 ? Math.max(stop, atrStop) : Math.min(stop, atrStop)) : atrStop;
        }
        if (!Double.isFinite(stop) || reSide * (spot - stop) <= 0) {
            return null;
        }
        reentryArmed = false;
        inTrade = true;
        everOpen = true;                                      // A-616: a re-entry is outside the allowance, never refunded
        heldEntryNo = entries + 1;                            // a further trade (code review 24 Nov: was the previous one's)
        heldSide = reSide;
        entrySpot = spot;
        entryAtr = st.atr3m();
        bestFav = 0;
        bestMinute = minute;                                  // A-636
        brokenLevel = Double.NaN;
        touchedAt = -1;
        levelTarget = Double.NaN;
        levelMinute = minute;
        buildUpPeak = Double.NaN;
        return new Entry(reSide > 0 ? OptionSide.CE : OptionSide.PE, stop, Double.NaN, reSide > 0 ? "OBC_REENTRY_UP" : "OBC_REENTRY_DOWN");
    }

    /**
     * A-622: minutes from the first minute since the swing began (the day's opposite extreme so far, by minute spot) at
     * which share3 in the burst's direction reached half of share3_min, to now; 0 when none.
     */
    private int staleMinutes(int dir, int minute) {
        int swing = -1;
        double ext = Double.NaN;
        for (double[] h : s3History) {
            if (Double.isFinite(h[2]) && (Double.isNaN(ext) || dir * h[2] < dir * ext)) {
                ext = h[2];
                swing = (int) h[0];
            }
        }
        for (double[] h : s3History) {
            if (h[0] >= swing && h[0] >= 9 * 60 + 17 && Double.isFinite(h[1]) && h[1] * dir >= share3Min / 2) {
                return minute - (int) h[0];
            }
        }
        return 0;
    }

    private static int minuteOf(String hhmm) {
        LocalTime t = LocalTime.parse(hhmm);
        return t.getHour() * 60 + t.getMinute();
    }

    static double median(java.util.List<Double> xs) {
        double[] v = xs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = v.length;
        return n == 0 ? Double.NaN : n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
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
