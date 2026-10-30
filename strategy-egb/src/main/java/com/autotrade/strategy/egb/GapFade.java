package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;

/**
 * Gap fade (A-270, user 29 Oct 2026: "yes register A-270 and build and run replay I want to see it"; from A-269's
 * descriptive arm). When the day's open is at least {@code setup.min_gap_pct} % away from the previous close, buy the ATM
 * option against the gap in the entry window (a put after a gap up, a call after a gap down). Target: a minute close at
 * or through the previous close (the gap filled); stop: a minute close beyond the opening range (features ORH / ORL) in
 * the gap's direction; flat by the file's {@code flat_by}. One trade per index per day.
 * <p>
 * A-272 ({@code setup.oi_gate_ratio}, off when absent): the outside AI's OI gate. Over the ATM ± 5 strikes, the OI change
 * of calls and puts in the 2 minutes ending at the entry snapshot: after a gap up the put is bought only when the calls'
 * change is positive and at least the ratio times the puts' (a falling put OI counts as 0): writers capping the gap;
 * after a gap down, mirrored. Otherwise no trade that day.
 * <p>
 * A-274 ({@code exits.touch_target}, off when absent): exit as soon as a snapshot shows the session's low (put) or high
 * (call) newly at or through the previous close, i.e. the index touched it since the entry without closing a minute there.
 * <p>
 * A-275 ({@code setup.event_entry}): no fixed entry minute. On every snapshot of the entry window the first one where the
 * file's checks all hold enters: the gap still alive (not filled: the session low / high has not reached the previous
 * close; the opening range not closed beyond in the gap's direction), the OI gate over {@code setup.oi_gate_window_min}
 * minutes true on {@code setup.oi_gate_persist} consecutive snapshots, and when set, the futures OI state agreeing with
 * the fade ({@code setup.futures_agree}) and the minute close back through the open or VWAP ({@code setup.price_confirm}).
 * <p>
 * A-276, profit protection: {@code exits.near_fill_pct} moves the target (minute close and touch) from the previous close
 * to the level where that share of the gap has filled; {@code exits.giveback_arm_pct} / {@code exits.giveback_keep}: once
 * the option's bid has been that % above the average premium, exit when it falls back to the average + that share of the
 * peak gain.
 * <p>
 * A-277, the runner ({@code exits.runner}): the fill (the previous close on a minute close, or touched with
 * {@code exits.touch_target}) does not end the trade; from then on the data decides, first of the rules the file turns on:
 * a minute close back through the previous close by {@code exits.run_reclaim_atr} ATR ({@code RUN_RECLAIM}); a minute
 * close beyond a trailing line against the position by {@code exits.run_trail_atr} ATR, the line being
 * {@code exits.run_trail} = ema9 / ema20 / vwap / swing (the last swing high for a put, low for a call) ({@code RUN_TRAIL});
 * the ATM +- 5 OI flow against the position (for a put: puts added over 5 minutes > 0 and at least
 * {@code exits.run_oi_ratio} times the calls) on {@code exits.run_oi_persist} snapshots in a row ({@code RUN_OI}); the futures
 * OI state against (a put: FRESH_LONG / SHORT_COVERING) on {@code exits.run_fut_persist} snapshots ({@code RUN_FUT}); a
 * futures-volume minute at least {@code exits.run_climax_rvol} times normal moving the position's way, the exhaustion
 * ({@code RUN_CLIMAX}), or {@code exits.run_volagainst_rvol} times normal moving against it ({@code RUN_VOL_AGAINST}); the
 * supporting writers fading: the OI change over 5 minutes on the side that carries the trade (calls for a put: the cap above)
 * at or below zero on {@code exits.run_support_fade_persist} snapshots in a row ({@code RUN_SUPPORT_FADE}).
 * {@code exits.runner_from_entry}: no fill target at all, the same rules from the entry (the reclaim only after a fill).
 * The opening-range stop, the premium stop and {@code flat_by} stay as the safety net.
 */
final class GapFade extends SetupStrategy {

    private final double minGapPct;
    private final double oiGateRatio;
    private final boolean touchTarget;
    private final boolean eventEntry;
    private final int gateWindowMin;
    private final int gatePersist;
    private final boolean futuresAgree;
    private final boolean priceConfirm;
    private final double nearFillPct;
    private final double givebackArmPct;
    private final double givebackKeep;
    private final boolean runner;
    private final boolean runnerFromEntry;
    private final double runReclaimAtr;
    private final String runTrail;
    private final double runTrailAtr;
    private final double runOiRatio;
    private final int runOiPersist;
    private final int runFutPersist;
    private final double runClimaxRvol;
    private final double runVolAgainstRvol;
    private final int runSupportFadePersist;
    /** A-277: the previous close reached since the entry; the run lengths of the OI and futures rules. */
    private boolean filled;
    private int oiAgainstRun;
    private int futAgainstRun;
    private int supportFadeRun;
    private boolean taken;
    /** The index level that ends the trade as a target (the previous close, or the near-fill level, A-276). */
    private double targetLevel = Double.NaN;
    private double peakBid = Double.NaN;
    /** A-275: a minute close beyond the opening range in the gap's direction since it completed; the gate's run length. */
    private boolean rangeBroken;
    private int gateRun;
    /** The session's low and high at the entry snapshot: a later extreme beyond them is a new touch (A-274). */
    private double entryDayLow = Double.NaN, entryDayHigh = Double.NaN;

    GapFade(ThresholdConfig c) {
        super(c);
        minGapPct = c.getDouble("setup.min_gap_pct");
        oiGateRatio = c.has("setup.oi_gate_ratio") ? c.getDouble("setup.oi_gate_ratio") : Double.NaN;
        touchTarget = c.has("exits.touch_target") && c.getBoolean("exits.touch_target");
        eventEntry = c.has("setup.event_entry") && c.getBoolean("setup.event_entry");
        gateWindowMin = c.has("setup.oi_gate_window_min") ? c.getInt("setup.oi_gate_window_min") : 2;
        if (gateWindowMin != 2 && gateWindowMin != 5) {
            throw new IllegalArgumentException("setup.oi_gate_window_min: 2 or 5 (the features computed), not " + gateWindowMin);
        }
        gatePersist = c.has("setup.oi_gate_persist") ? c.getInt("setup.oi_gate_persist") : 1;
        futuresAgree = c.has("setup.futures_agree") && c.getBoolean("setup.futures_agree");
        priceConfirm = c.has("setup.price_confirm") && c.getBoolean("setup.price_confirm");
        nearFillPct = c.has("exits.near_fill_pct") ? c.getDouble("exits.near_fill_pct") : 100;
        givebackArmPct = c.has("exits.giveback_arm_pct") ? c.getDouble("exits.giveback_arm_pct") : 0;
        givebackKeep = c.has("exits.giveback_keep") ? c.getDouble("exits.giveback_keep") : 0.5;
        runnerFromEntry = c.has("exits.runner_from_entry") && c.getBoolean("exits.runner_from_entry");
        runner = runnerFromEntry || c.has("exits.runner") && c.getBoolean("exits.runner");
        runReclaimAtr = c.has("exits.run_reclaim_atr") ? c.getDouble("exits.run_reclaim_atr") : Double.NaN;
        runTrail = c.has("exits.run_trail") ? c.getString("exits.run_trail") : "none";
        if (!Set.of("none", "ema9", "ema20", "vwap", "swing").contains(runTrail)) {
            throw new IllegalArgumentException("exits.run_trail: none, ema9, ema20, vwap or swing, not " + runTrail);
        }
        runTrailAtr = c.has("exits.run_trail_atr") ? c.getDouble("exits.run_trail_atr") : 0;
        runOiRatio = c.has("exits.run_oi_ratio") ? c.getDouble("exits.run_oi_ratio") : Double.NaN;
        runOiPersist = c.has("exits.run_oi_persist") ? c.getInt("exits.run_oi_persist") : 2;
        runFutPersist = c.has("exits.run_fut_persist") ? c.getInt("exits.run_fut_persist") : 0;
        runClimaxRvol = c.has("exits.run_climax_rvol") ? c.getDouble("exits.run_climax_rvol") : Double.NaN;
        runVolAgainstRvol = c.has("exits.run_volagainst_rvol") ? c.getDouble("exits.run_volagainst_rvol") : Double.NaN;
        runSupportFadePersist = c.has("exits.run_support_fade_persist") ? c.getInt("exits.run_support_fade_persist") : 0;
    }

    /** The trade's entry, with its target at the previous close or at the near-fill level (A-276). */
    private Entry entry(StructureFeatures st, boolean up, double open, double prev) {
        taken = true;
        entryDayLow = st.dayLow();
        entryDayHigh = st.dayHigh();
        peakBid = Double.NaN;
        filled = false;
        oiAgainstRun = 0;
        futAgainstRun = 0;
        supportFadeRun = 0;
        targetLevel = open + (prev - open) * nearFillPct / 100.0;     // 100 % = the previous close
        double target = runner ? Double.NaN : targetLevel;             // A-277: the runner handles the fill itself
        return up ? new Entry(OptionSide.PE, st.orHigh(), target, "GAP_UP_FADE")
                : new Entry(OptionSide.CE, st.orLow(), target, "GAP_DOWN_FADE");
    }

    @Override
    protected String customExit(FeatureSnapshot s, PositionView position, LocalTime time) {
        if (runner) {
            return runnerExit(s, position.side());
        }
        String touch = customExit(s, position.side(), time);
        if (touch != null || !(givebackArmPct > 0)) {
            return touch;
        }
        double avg = position.averagePremium(), bid = position.bid();                 // A-276 give-back
        if (!(avg > 0) || !(bid > 0)) {
            return null;
        }
        peakBid = Double.isNaN(peakBid) ? bid : Math.max(peakBid, bid);
        boolean armed = peakBid >= avg * (1 + givebackArmPct / 100.0);
        return armed && bid <= avg + givebackKeep * (peakBid - avg) ? "GIVEBACK" : null;
    }

    /** A-277: the runner's exit on this snapshot, or null (holding). */
    private String runnerExit(FeatureSnapshot s, OptionSide side) {
        StructureFeatures st = s.structure();
        double spot = s.spot(), sign = side == OptionSide.CE ? 1 : -1, atr = st.atr1m();
        if (!Double.isFinite(spot)) {
            return null;
        }
        boolean touched = touchTarget && !Double.isNaN(entryDayLow) && (side == OptionSide.PE
                ? st.dayLow() < entryDayLow && st.dayLow() <= targetLevel : st.dayHigh() > entryDayHigh && st.dayHigh() >= targetLevel);
        if (!filled && (sign * (spot - targetLevel) >= 0 || touched)) {
            filled = true;
            return null;                                      // the fill itself is not an exit: from the next snapshot on
        }
        // the OI and futures run lengths are kept on every snapshot, so a run can start before the fill
        double puts = s.options().atmBand5PutChange5m(), calls = s.options().atmBand5CallChange5m();
        double against = side == OptionSide.PE ? puts : calls, with = side == OptionSide.PE ? calls : puts;
        oiAgainstRun = against > 0 && against >= runOiRatio * Math.max(with, 0) ? oiAgainstRun + 1 : 0;
        String oiState = s.futures().oiState();
        boolean futAgainst = oiState != null && (side == OptionSide.PE
                ? oiState.equals("FRESH_LONG") || oiState.equals("SHORT_COVERING")
                : oiState.equals("FRESH_SHORT") || oiState.equals("LONG_UNWIND"));
        futAgainstRun = futAgainst ? futAgainstRun + 1 : 0;
        supportFadeRun = with <= 0 ? supportFadeRun + 1 : 0;              // NaN compares false: unknown is not a fade
        if (!filled && !runnerFromEntry) {
            return null;
        }
        double buffer = Double.isFinite(atr) ? atr : 0;
        if (filled && Double.isFinite(runReclaimAtr) && sign * (spot - st.prevClose()) < -runReclaimAtr * buffer) {
            return "RUN_RECLAIM";
        }
        double line = switch (runTrail) {
            case "ema9" -> st.ema9();
            case "ema20" -> st.ema20();
            case "vwap" -> st.vwapSpotProxy();
            case "swing" -> side == OptionSide.PE ? st.lastSwingHigh() : st.lastSwingLow();
            default -> Double.NaN;
        };
        if (Double.isFinite(line) && sign * (spot - line) < -runTrailAtr * buffer) {
            return "RUN_TRAIL";
        }
        if (Double.isFinite(runOiRatio) && oiAgainstRun >= runOiPersist) {
            return "RUN_OI";
        }
        if (runFutPersist > 0 && futAgainstRun >= runFutPersist) {
            return "RUN_FUT";
        }
        if (runSupportFadePersist > 0 && supportFadeRun >= runSupportFadePersist) {
            return "RUN_SUPPORT_FADE";
        }
        double rvol = s.futures().rvolTod(), move = sign * st.spotChange1m();
        if (Double.isFinite(runClimaxRvol) && rvol >= runClimaxRvol && move > 0) {
            return "RUN_CLIMAX";
        }
        if (Double.isFinite(runVolAgainstRvol) && rvol >= runVolAgainstRvol && move < 0) {
            return "RUN_VOL_AGAINST";
        }
        return null;
    }

    /** The OI gate on this snapshot: the fading side's writers ahead {@code oiGateRatio}:1 over ATM +- 5 in the window. */
    private boolean gate(FeatureSnapshot s, boolean up) {
        double calls = gateWindowMin == 5 ? s.options().atmBand5CallChange5m() : s.options().atmBand5CallChange2m();
        double puts = gateWindowMin == 5 ? s.options().atmBand5PutChange5m() : s.options().atmBand5PutChange2m();
        double lead = up ? calls : puts, lag = up ? puts : calls;
        return lead > 0 && lead >= oiGateRatio * Math.max(lag, 0);          // NaN compares false
    }

    @Override
    protected String customExit(FeatureSnapshot s, OptionSide side, LocalTime time) {
        if (!touchTarget || Double.isNaN(entryDayLow)) {
            return null;
        }
        StructureFeatures st = s.structure();
        boolean touched = side == OptionSide.PE ? st.dayLow() < entryDayLow && st.dayLow() <= targetLevel
                : st.dayHigh() > entryDayHigh && st.dayHigh() >= targetLevel;
        return touched ? "TARGET_TOUCH" : null;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        double open = st.dayOpen(), prev = st.prevClose();
        boolean known = Double.isFinite(open) && prev > 0;
        double gapPct = known ? (open - prev) / prev * 100 : Double.NaN;
        boolean up = gapPct >= minGapPct, down = gapPct <= -minGapPct;
        boolean range = st.orComplete() && Double.isFinite(st.orHigh()) && Double.isFinite(st.orLow());
        c.put("gap_up", up);
        c.put("gap_down", down);
        c.put("opening_range", range);
        if (eventEntry) {
            return eventEntry(s, st, up, down, range, prev, open, canEnter, c);
        }
        if (!canEnter || taken || !range || !(up || down)) {
            return null;
        }
        taken = true;
        if (Double.isFinite(oiGateRatio)) {                          // A-272
            boolean allowed = gate(s, up);
            c.put("oi_gate", allowed);
            if (!allowed) {
                return null;
            }
        }
        return entry(st, up, open, prev);
    }

    /** A-275: the data-driven entry, evaluated on every snapshot (the state is kept even outside the entry window). */
    private Entry eventEntry(FeatureSnapshot s, StructureFeatures st, boolean up, boolean down, boolean range, double prev,
                             double open, boolean canEnter, Map<String, Boolean> c) {
        if (!(up || down) || !range) {
            return null;
        }
        double spot = close(0);
        if (Double.isFinite(spot) && (up ? spot > st.orHigh() : spot < st.orLow())) {
            rangeBroken = true;
        }
        boolean filled = up ? st.dayLow() <= prev : st.dayHigh() >= prev;
        boolean gateNow = gate(s, up);
        gateRun = gateNow ? gateRun + 1 : 0;
        String oiState = s.futures().oiState();
        boolean futuresOk = oiState != null && (oiState.equals("NEUTRAL") || (up
                ? oiState.equals("FRESH_SHORT") || oiState.equals("LONG_UNWIND")
                : oiState.equals("FRESH_LONG") || oiState.equals("SHORT_COVERING")));
        double vwap = st.vwapSpotProxy();
        boolean priceOk = Double.isFinite(spot) && (up ? spot < open || (Double.isFinite(vwap) && spot < vwap)
                : spot > open || (Double.isFinite(vwap) && spot > vwap));
        boolean alive = !filled && !rangeBroken;
        c.put("gap_alive", alive);
        c.put("oi_gate", gateRun >= gatePersist);
        c.put("futures_agree", futuresOk);
        c.put("price_confirms", priceOk);
        if (!canEnter || taken || !alive || gateRun < gatePersist || (futuresAgree && !futuresOk) || (priceConfirm && !priceOk)) {
            return null;
        }
        return entry(st, up, open, prev);
    }
}
