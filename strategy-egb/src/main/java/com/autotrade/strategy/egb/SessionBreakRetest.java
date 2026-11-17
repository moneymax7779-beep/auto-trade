package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;

/**
 * Session break-retest (A-426 S2F0 / A-428, sbr-v1). Exactly the research rule in .local/a426/brk.py (setup 2, no filter).
 * <ul>
 * <li>LINES as srb-v3: the first-hour high / low (fixed, the high resistance and the low support) from the 10:15 snapshot,
 *     five-minute swings that are the session high / low when confirmed, lines with >= {@code setup.strong_touches} touches;
 *     tolerance {@code setup.line_tol_em} x EM. A line is spent only by a five-minute close a buffer beyond it: the BREAK.</li>
 * <li>RETEST: within {@code setup.retest_min} minutes of the break, a five-minute bar comes back within
 *     {@code setup.retest_band_em} x EM of the line, closes >= the buffer beyond it on the break side and beyond the previous
 *     bar's close: enter with the break (CE up, PE down). A five-minute close a buffer back across ends the break.</li>
 * <li>STOP a five-minute close a buffer back across the line; no target (flat_by). At most {@code setup.max_entries_per_day}.
 *     Strike ATM, one in the money at <= 1 day to expiry.</li>
 * <li>sbr-v2 (A-429 X2 / A-430, {@code exits.level_exit}, off by default): the TARGET LEVEL is the nearest of the levels at
 *     entry (tradeable lines other than the traded one, and the known levels) >= {@code exits.level_min_em} x EM beyond the
 *     entry close; once a minute comes within {@code exits.level_band_em} x EM of it, exit on the first five-minute close
 *     {@code exits.level_reject_em} x EM back from the best minute extreme since then (LEVEL_REJECT); a five-minute close a
 *     buffer beyond the level means it is crossed and the next level beyond it becomes the target level.</li>
 * <li>sbr-v3 (A-431 X4a, {@code exits.reclaim_exit}, "off" by default): a CROSSED level is a level at entry lying beyond the
 *     traded line in the trade direction but not beyond the entry close ("pre_entry"), plus, with "all" (A-431 X4b), every
 *     level at entry that a later five-minute close crosses by the buffer; exit on the first five-minute close back across
 *     any crossed level by the buffer (LEVEL_RECLAIM), checked after the LINE_STOP and before the level rejection.</li>
 * </ul>
 */
final class SessionBreakRetest extends SetupStrategy {

    record Bar5(LocalTime close, double h, double l, double c) {
    }

    private static final class Line {
        double price;
        final List<Double> touchPrices = new ArrayList<>();
        boolean major;
        boolean fixed;
        Boolean resistance;
        boolean broken;
    }

    private static final class Break {
        final Line line;
        final int dir;
        final LocalTime at;
        int closes;
        boolean retested;
        boolean done;

        Break(Line line, int dir, LocalTime at) {
            this.line = line;
            this.dir = dir;
            this.at = at;
        }
    }

    private static final LocalTime OPEN = LocalTime.of(9, 15);

    private final double lineTolEm;
    private final int strongTouches;
    private final double bufferEm;
    private final double bandEm;
    private final int retestMin;
    private final int holdBars;
    private final int maxEntriesPerDay;
    private final LocalTime firstHourEnd;

    private double slotHigh = Double.NaN, slotLow = Double.NaN, slotClose = Double.NaN;
    private double runHigh = Double.NaN, runLow = Double.NaN;
    private LocalTime lastMinuteSeen;
    private final List<Bar5> bars = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Break> breaks = new ArrayList<>();
    private double firstVix = Double.NaN;
    private int filled;
    private double pendingLine = Double.NaN, pendingBuf;
    private Instant positionOpenedAt;
    private double positionLine = Double.NaN, positionBuf;
    private LocalTime lastStopCheck;
    private final boolean levelExit;
    private final double levelMinEm, levelBandEm, levelRejectEm;
    private double em = Double.NaN;
    private List<Double> pendingLevels = List.of();
    private double pendingPx = Double.NaN, pendingEm = Double.NaN;
    private List<Double> positionLevels = List.of();
    private double positionPx = Double.NaN, positionEm = Double.NaN, level = Double.NaN, bestExt = Double.NaN;
    private boolean armed;
    private LocalTime lastLevelMinute;
    private final String reclaimExit;
    private final List<Double> crossed = new ArrayList<>();
    /** sbr-v4 (A-433): an entry needs the next level more than this % of the entry close away (NaN = off). */
    private final double minRoomPct;
    /**
     * A-437 sbr-v7 / v8: the break, the retest, the LINE_STOP and the LEVEL_RECLAIM run on closes of this many minutes
     * (09:15-based); the lines (swings) and the level exit stay on five-minute bars. 5 = as v1-v6.
     */
    private final int breakBarMin;
    private double nHigh = Double.NaN, nLow = Double.NaN, nClose = Double.NaN;
    private final List<Bar5> barsN = new ArrayList<>();
    /**
     * A-437 sbr-v6 (A-401's gate): a CE is skipped when the 15-minute ATM +- 3 OI flow is <= -oi_gate and the index has not
     * fallen more than oi_gate_points over the same 15 minutes (a PE: >= +oi_gate and not risen more); NaN = off.
     */
    private final double oiGate;
    private final double oiGatePoints;
    /** A-437 sbr-v6: at a LEVEL_REJECT, hold on to the next level while the flow is >= this in the trade's favour (NaN = off). */
    private final double oiHold;
    private final java.util.TreeMap<LocalTime, Double> minuteClose = new java.util.TreeMap<>();
    /**
     * A-438 sbr-v9: a five-minute swing confirmed while the position is open that is still the session extreme in the
     * trade's direction (a fresh top for a CE, bottom for a PE), >= level_min_em x EM beyond the entry close and nearer than
     * the current target, becomes the target level (the level exit's band and rejection then apply). Default off.
     */
    private final boolean freshTargets;
    private int posDir;
    /**
     * A-439 sbr-v11: the entry's level list (the exit targets) also holds the prior day's opening-range high / low and the
     * features' history levels (2-day high / low, 2-day opening range, 12 months' highs / lows).
     */
    private final boolean extraLevels;
    /**
     * A-439 sbr-v11: and the chart's tested zones (LevelsService.zones on minute bars: 3-minute pivots, 0.04 % clusters,
     * touches 5+ minutes apart, ended by a minute close beyond) with at least this many touches at entry; 0 = off.
     */
    private final int zoneMinTouches;
    private final List<double[]> zoneBars = new ArrayList<>();        // {minute of day, high, low, close}
    /** A-440 sbr-v13: the 15-minute opening range high / low become fixed, tradeable lines once the range is complete. */
    private final boolean orLines;
    /**
     * A-440 sbr-v14: and every known level becomes a fixed, tradeable line at the same time: day open, previous close, PDH /
     * PDL, prior ORH / ORL, PWH / PWL, PMH / PML and the features' history levels (2-day high / low and opening range, 12
     * months' highs / lows); resistance when above the close then, support when below.
     */
    private final boolean levelLines;
    /** A-466 sbr-v25: setup.level_lines_set "prior" = only PDH / PDL and the prior ORH / ORL become lines ("all" = v14). */
    private final boolean levelLinesPriorOnly;
    private boolean openLinesAdded;
    /**
     * A-441 sbr-v15: a level or line added with a side (resistance / support) merges only into a line on the same side
     * (v14 let a prior-week high just below the price turn the ORH resistance on 15 Oct into a support).
     */
    private final boolean sameSideMerge;
    /**
     * A-447 sbr-v17: an emergency stop on the one-minute close: exit when it is more than this x EM (at entry) back across
     * the traded line (the five-minute LINE_STOP stays); NaN = off.
     */
    private final double fastStopEm;
    private LocalTime lastFastMinute;
    /**
     * A-452 sbr-v18: half size when the 30-minute ATM +- 3 net OI flow at entry already backs the trade by more than this
     * fraction (the writers' move is mature: on the A-440 tick days such entries won 37 %, the others 82 %); with
     * position.intended_lots 2, a normal entry is 2 lots (the full budget) and a crowded one 1; NaN = off (1 lot).
     */
    private final double crowdedOiFlow;
    /** A-452 sbr-v19 / v20: half size when the ATM IV fell over the last 3 minutes (atmIvChange3m < 0) at the entry. */
    private final boolean weakIvHalf;
    /** A-453 sbr-v21: no entry when both are weak (30-minute OI crowded and IV falling); one weak = half, none = full. */
    private final boolean skipBothWeak;
    private int pendingLots = 1;
    private final List<Zone> zones = new ArrayList<>();
    /**
     * A-460 sbr-v23: on a range day, buy the other side when a break of a major line fails: when a five-minute close comes
     * back across a fixed (opening range, first hour) or session-extreme line a buffer the wrong way, and at least
     * fade_min_failed breaks (this one included) have failed today, enter the opposite direction (a failed up-break buys a PE).
     * The failed line is the stop (a five-minute close a buffer back beyond it), the levels the targets. Default off.
     */
    private final boolean failedBreakFade;
    private final int fadeMinFailed;
    private int failedToday;
    /**
     * A-467 sbr-v26: setup.fade_own_slot: a fade entry does not use one of the max_entries_per_day breakout entries; fades have
     * their own cap (setup.fade_max_per_day, default 1). fadesFilled counts filled fade entries (from the position's fill count).
     */
    private final boolean fadeOwnSlot;
    private final int fadeMaxPerDay;
    private int fadesFilled, lastFilled;
    private boolean lastEntryFade;
    /** A-459: diagnostics only (env SBR_DEBUG=1): lines, breaks and retests to stdout; no effect on trading. */
    private static final boolean DEBUG = "1".equals(System.getenv("SBR_DEBUG"));
    private String dbgU = "";
    private LocalTime dbgT;

    private void dbg(String fmt, Object... args) {
        if (DEBUG) {
            System.out.println("SBRDBG " + dbgU + " " + dbgT + " " + String.format(fmt, args));
        }
    }

    private static final class Zone {
        final boolean resistance;
        final List<Double> prices = new ArrayList<>();
        int lastTouch;
        boolean broken;

        Zone(boolean resistance, double price, int at) {
            this.resistance = resistance;
            prices.add(price);
            lastTouch = at;
        }

        double mean() {
            return prices.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        }

        double lo() {
            return prices.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        }

        double hi() {
            return prices.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        }
    }

    private static final double ZONE_TOL = 0.0004;
    private static final int ZONE_PIVOT = 3, ZONE_GAP_MIN = 5, ZONES_UNTIL = 15 * 60 + 15;

    /** A-439: LevelsService.zones, one minute bar at a time. */
    private void zoneBar(LocalTime minute, double h, double l, double c) {
        int t = minute.getHour() * 60 + minute.getMinute();
        zoneBars.add(new double[] {t, h, l, c});
        int i = zoneBars.size() - 1, p = i - ZONE_PIVOT;
        if (p >= ZONE_PIVOT) {
            double[] pv = zoneBars.get(p);
            boolean high = true, low = true;
            for (int j = p - ZONE_PIVOT; j <= p + ZONE_PIVOT; j++) {
                double[] b = zoneBars.get(j);
                if (j < p) {
                    high &= pv[1] > b[1];
                    low &= pv[2] < b[2];
                } else if (j > p) {
                    high &= pv[1] >= b[1];
                    low &= pv[2] <= b[2];
                }
            }
            if (pv[0] < ZONES_UNTIL) {
                if (high) {
                    zoneTouch(true, pv[1], (int) pv[0]);
                }
                if (low) {
                    zoneTouch(false, pv[2], (int) pv[0]);
                }
            }
        }
        for (Zone z : zones) {
            if (z.prices.size() >= 2 && !z.broken) {
                double tol = ZONE_TOL * c;
                z.broken = z.resistance ? c > z.hi() + tol : c < z.lo() - tol;
            }
        }
    }

    private void zoneTouch(boolean resistance, double price, int at) {
        double tol = ZONE_TOL * price;
        for (Zone z : zones) {
            if (z.resistance == resistance && !z.broken && Math.abs(price - z.mean()) <= tol) {
                if (at >= z.lastTouch + ZONE_GAP_MIN) {
                    z.prices.add(price);
                    z.lastTouch = at;
                }
                return;
            }
        }
        zones.add(new Zone(resistance, price, at));
    }

    SessionBreakRetest(ThresholdConfig c) {
        super(c);
        lineTolEm = c.getDouble("setup.line_tol_em");
        strongTouches = c.getInt("setup.strong_touches");
        bufferEm = c.getDouble("setup.break_buffer_em");
        bandEm = c.getDouble("setup.retest_band_em");
        retestMin = c.getInt("setup.retest_min");
        holdBars = c.getInt("setup.hold_bars");
        maxEntriesPerDay = c.getInt("setup.max_entries_per_day");
        firstHourEnd = c.getTime("setup.first_hour_end");
        levelExit = c.has("exits.level_exit") && c.getBoolean("exits.level_exit");
        levelMinEm = c.has("exits.level_min_em") ? c.getDouble("exits.level_min_em") : 0;
        levelBandEm = c.has("exits.level_band_em") ? c.getDouble("exits.level_band_em") : 0;
        levelRejectEm = c.has("exits.level_reject_em") ? c.getDouble("exits.level_reject_em") : 0;
        reclaimExit = c.has("exits.reclaim_exit") ? c.getString("exits.reclaim_exit") : "off";
        minRoomPct = c.has("setup.min_room_pct") ? c.getDouble("setup.min_room_pct") : Double.NaN;
        breakBarMin = c.has("setup.break_bar_min") ? c.getInt("setup.break_bar_min") : 5;
        if (breakBarMin < 1 || breakBarMin > 5) {
            throw new IllegalArgumentException("setup.break_bar_min must be 1-5: " + breakBarMin);
        }
        oiGate = c.has("setup.oi_gate") ? c.getDouble("setup.oi_gate") : Double.NaN;
        oiGatePoints = c.has("setup.oi_gate_points") ? c.getDouble("setup.oi_gate_points") : 10;
        oiHold = c.has("exits.oi_hold") ? c.getDouble("exits.oi_hold") : Double.NaN;
        freshTargets = c.has("exits.fresh_targets") && c.getBoolean("exits.fresh_targets");
        extraLevels = c.has("setup.extra_levels") && c.getBoolean("setup.extra_levels");
        zoneMinTouches = c.has("setup.zone_min_touches") ? c.getInt("setup.zone_min_touches") : 0;
        orLines = c.has("setup.or_lines") && c.getBoolean("setup.or_lines");
        levelLines = c.has("setup.level_lines") && c.getBoolean("setup.level_lines");
        levelLinesPriorOnly = c.has("setup.level_lines_set") && "prior".equals(c.getString("setup.level_lines_set"));
        sameSideMerge = c.has("setup.same_side_merge") && c.getBoolean("setup.same_side_merge");
        fastStopEm = c.has("exits.fast_stop_em") ? c.getDouble("exits.fast_stop_em") : Double.NaN;
        crowdedOiFlow = c.has("position.crowded_oi_flow") ? c.getDouble("position.crowded_oi_flow") : Double.NaN;
        weakIvHalf = c.has("position.weak_iv_half") && c.getBoolean("position.weak_iv_half");
        skipBothWeak = c.has("position.skip_both_weak") && c.getBoolean("position.skip_both_weak");
        failedBreakFade = c.has("setup.failed_break_fade") && c.getBoolean("setup.failed_break_fade");
        fadeMinFailed = c.has("setup.fade_min_failed") ? c.getInt("setup.fade_min_failed") : 2;
        fadeOwnSlot = c.has("setup.fade_own_slot") && c.getBoolean("setup.fade_own_slot");
        fadeMaxPerDay = c.has("setup.fade_max_per_day") ? c.getInt("setup.fade_max_per_day") : 1;
        if (!List.of("off", "pre_entry", "all").contains(reclaimExit)) {
            throw new IllegalArgumentException("exits.reclaim_exit must be off, pre_entry or all: " + reclaimExit);
        }
    }

    @Override
    protected int entryLots(Entry entry) {
        return Double.isNaN(crowdedOiFlow) && !weakIvHalf ? 1 : pendingLots;           // A-452
    }

    static boolean fiveMinuteClose(LocalTime time) {
        return barClose(time, 5);
    }

    static boolean barClose(LocalTime time, int n) {
        int minutes = (int) Duration.between(OPEN, time).toMinutes();
        return minutes > 0 && minutes % n == 0;
    }

    /** A-437: the 15-minute ATM +- 3 OI flow (a fraction), NaN when unknown. */
    private static double flow15(FeatureSnapshot s) {
        return s.options() == null ? Double.NaN : s.options().atmBand3Share15m();
    }

    /** A-437: the index change over the last 15 minutes in NIFTY points (SENSEX / 3.25); NaN without a close 15 minutes ago. */
    private double change15(FeatureSnapshot s, LocalTime minute) {
        var then = minuteClose.floorEntry(minute.minusMinutes(15));
        Double now = minuteClose.get(minute);
        if (then == null || now == null || then.getKey().isBefore(minute.minusMinutes(17))) {
            return Double.NaN;
        }
        return (now - then.getValue()) / ("SENSEX".equals(s.underlying()) ? 3.25 : 1.0);
    }

    /** A-437 sbr-v6: true when the OI gate blocks an entry in direction d. */
    private boolean oiBlocks(FeatureSnapshot s, LocalTime minute, int d) {
        double f = flow15(s), px = change15(s, minute);
        if (Double.isNaN(oiGate) || Double.isNaN(f)) {
            return false;
        }
        double pxOk = Double.isNaN(px) ? 0 : px;                 // no 15-minute change known: treat as flat
        return d > 0 ? f <= -oiGate && pxOk >= -oiGatePoints : f >= oiGate && pxOk <= oiGatePoints;
    }

    @Override
    public Decision decide(FeatureSnapshot s, PositionView position) {
        filled = position.closedToday() + (position.open() ? 1 : 0);
        if (filled > lastFilled) {                                  // A-467: a new fill belongs to the last entry asked for
            if (lastEntryFade) {
                fadesFilled += filled - lastFilled;
            }
            lastFilled = filled;
        }
        if (!position.open()) {
            positionOpenedAt = null;
            posDir = 0;
        }
        return super.decide(s, position);
    }

    private boolean tradeable(Line l) {
        return !l.broken && (l.major || l.touchPrices.size() >= strongTouches);
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        if (Double.isNaN(firstVix) && s.volatility() != null && Double.isFinite(s.volatility().vix())) {
            firstVix = s.volatility().vix();
        }
        LocalTime minute = time.withSecond(0).withNano(0);
        if (minute.equals(lastMinuteSeen) || minute.isBefore(OPEN.plusMinutes(1))) {
            return null;
        }
        lastMinuteSeen = minute;
        dbgU = s.underlying();
        dbgT = minute;
        double close = st.lastMinuteClose(), mh = st.lastMinuteHigh(), ml = st.lastMinuteLow();
        if (Double.isFinite(mh) && Double.isFinite(ml) && Double.isFinite(close)) {
            slotHigh = Double.isNaN(slotHigh) ? mh : Math.max(slotHigh, mh);
            slotLow = Double.isNaN(slotLow) ? ml : Math.min(slotLow, ml);
            slotClose = close;
            runHigh = Double.isNaN(runHigh) ? mh : Math.max(runHigh, mh);
            runLow = Double.isNaN(runLow) ? ml : Math.min(runLow, ml);
            if (breakBarMin != 5) {                                 // A-437: only the shorter break bars need these
                nHigh = Double.isNaN(nHigh) ? mh : Math.max(nHigh, mh);
                nLow = Double.isNaN(nLow) ? ml : Math.min(nLow, ml);
                nClose = close;
            }
            if (!Double.isNaN(oiGate)) {
                minuteClose.put(minute, close);
            }
            if (zoneMinTouches > 0) {
                zoneBar(minute, mh, ml, close);
            }
        }
        em = st.dayOpen() * firstVix / 100 / Math.sqrt(252);
        double tol = lineTolEm * em, buf = bufferEm * em, band = bandEm * em;
        if ((orLines || levelLines) && !openLinesAdded && Double.isFinite(tol) && Double.isFinite(close)
                && Double.isFinite(st.orHigh()) && Double.isFinite(st.orLow())) {           // A-440: from 09:30
            openLinesAdded = true;
            if (orLines) {
                add(st.orHigh(), true, close, tol, Boolean.TRUE, true);
                add(st.orLow(), true, close, tol, Boolean.FALSE, true);
            }
            if (levelLines) {
                List<Double> known = new ArrayList<>();
                double[] xs = levelLinesPriorOnly ? new double[] {st.pdh(), st.pdl(), st.prevOrHigh(), st.prevOrLow()}
                        : new double[] {st.dayOpen(), st.prevClose(), st.pdh(), st.pdl(), st.prevOrHigh(),
                        st.prevOrLow(), st.pwh(), st.pwl(), st.pmh(), st.pml()};
                for (double x : xs) {
                    known.add(x);
                }
                if (!levelLinesPriorOnly && st.historyLevels() != null) {
                    known.addAll(st.historyLevels());
                }
                for (Double x : known) {
                    if (x != null && Double.isFinite(x)) {
                        add(x, true, close, tol, x > close, true);
                    }
                }
            }
        }
        if (minute.equals(firstHourEnd) && Double.isFinite(tol) && Double.isFinite(runHigh)) {
            add(runHigh, true, close, tol, Boolean.TRUE, true);
            add(runLow, true, close, tol, Boolean.FALSE, true);
        }
        Entry entry = null;
        boolean close5 = fiveMinuteClose(minute), closeN = barClose(minute, breakBarMin);
        if (!close5 && !closeN) {
            return null;
        }
        if (close5) {
            Bar5 b5 = null;
            if (!Double.isNaN(slotClose)) {
                b5 = new Bar5(minute, slotHigh, slotLow, slotClose);
                bars.add(b5);
            }
            slotHigh = Double.NaN;
            slotLow = Double.NaN;
            slotClose = Double.NaN;
            int j = bars.size() - 3;
            if (b5 != null && Double.isFinite(tol) && j >= 2) {
                double hmax = Double.NEGATIVE_INFINITY, lmin = Double.POSITIVE_INFINITY;
                for (int x = j - 2; x <= j + 2; x++) {
                    hmax = Math.max(hmax, bars.get(x).h());
                    lmin = Math.min(lmin, bars.get(x).l());
                }
                Bar5 sw = bars.get(j);
                if (sw.h() == hmax) {
                    add(sw.h(), sw.h() >= runHigh, close, tol, null, false);
                }
                if (sw.l() == lmin) {
                    add(sw.l(), sw.l() <= runLow, close, tol, null, false);
                }
                if (freshTargets && posDir != 0 && levelExit && Double.isFinite(positionEm)) {      // A-438 sbr-v9
                    double y = posDir > 0 ? (sw.h() == hmax && sw.h() >= runHigh ? sw.h() : Double.NaN)
                            : (sw.l() == lmin && sw.l() <= runLow ? sw.l() : Double.NaN);
                    if (Double.isFinite(y) && posDir * (y - positionPx) >= levelMinEm * positionEm
                            && (Double.isNaN(level) || posDir * (y - positionPx) < posDir * (level - positionPx))) {
                        level = y;
                        armed = false;
                        bestExt = Double.NaN;
                        c.put("fresh_target", true);
                    }
                }
            }
        }
        if (!closeN) {
            return null;
        }
        List<Bar5> series = breakBarMin == 5 ? bars : barsN;
        Bar5 bar = null;
        if (breakBarMin == 5) {
            bar = !bars.isEmpty() && bars.getLast().close().equals(minute) ? bars.getLast() : null;
        } else {
            if (!Double.isNaN(nClose)) {
                bar = new Bar5(minute, nHigh, nLow, nClose);
                barsN.add(bar);
            }
            nHigh = Double.NaN;
            nLow = Double.NaN;
            nClose = Double.NaN;
        }
        if (bar == null || !Double.isFinite(tol)) {
            return null;
        }
        Double pc = series.size() >= 2 ? series.get(series.size() - 2).c() : null;
        for (Break b : breaks) {                                    // follow-ups of earlier breaks
            if (b.done) {
                continue;
            }
            double lp = b.line.price;
            int d = b.dir;
            if (d * (bar.c() - lp) < -buf) {
                b.done = true;                                      // closed back across: the break failed
                failedToday++;
                dbg("FAILED break %s of %.1f: close %.1f back across (failed today %d)", d > 0 ? "up" : "down", lp, bar.c(),
                        failedToday);
                if (failedBreakFade && failedToday >= fadeMinFailed && (b.line.fixed || b.line.major)) {   // A-460
                    dbg("FADE %s at %.1f: canEnter %s filled %d/%d cond %s", d > 0 ? "PE" : "CE", bar.c(), canEnter, filled,
                            maxEntriesPerDay, c);
                    boolean fadeRoom = fadeOwnSlot ? fadesFilled < fadeMaxPerDay : filled < maxEntriesPerDay;
                    if (entry == null && canEnter && fadeRoom) {
                        lastEntryFade = true;
                        pendingLine = lp;
                        pendingBuf = buf;
                        pendingLevels = levels(st, b.line);
                        pendingPx = bar.c();
                        pendingEm = em;
                        entry = new Entry(d > 0 ? OptionSide.PE : OptionSide.CE, Double.NaN, Double.NaN,
                                "FAILED_BREAK_" + (d > 0 ? "PE" : "CE"));
                        c.put("failed_break_fade", true);
                        if (!sized(s, -d, c)) {
                            entry = null;
                        }
                    }
                }
                continue;
            }
            b.closes++;
            long since = Duration.between(b.at, minute).toMinutes();
            if (!b.retested && since <= retestMin && pc != null) {
                boolean touch = d > 0 ? bar.l() <= lp + band : bar.h() >= lp - band;
                if (DEBUG && touch && !(d * (bar.c() - lp) >= buf && d * (bar.c() - pc) > 0)) {
                    dbg("retest touch of %.1f (%s) but close %.1f (prev %.1f) not a buffer beyond and moving away", lp,
                            d > 0 ? "up" : "down", bar.c(), pc);
                }
                if (touch && d * (bar.c() - lp) >= buf && d * (bar.c() - pc) > 0) {
                    b.retested = true;
                    List<Double> lineLevels = levels(st, b.line);
                    double room = Double.isNaN(minRoomPct) ? Double.NaN : roomPct(lineLevels, bar.c(), d, em);
                    boolean roomOk = Double.isNaN(room) || room > minRoomPct;    // sbr-v4: no level ahead = kept
                    if (!Double.isNaN(minRoomPct)) {
                        c.put("room_ok", roomOk);
                    }
                    boolean oiOk = !oiBlocks(s, minute, d);               // A-437 sbr-v6
                    if (!Double.isNaN(oiGate)) {
                        c.put("oi_gate_ok", oiOk);
                    }
                    dbg("RETEST of %.1f (%s) close %.1f: canEnter %s filled %d/%d room %s oi %s cond %s", lp, d > 0 ? "up" : "down",
                            bar.c(), canEnter, filled, maxEntriesPerDay, roomOk, oiOk, c);
                    int breakoutsFilled = filled - (fadeOwnSlot ? fadesFilled : 0);
                    if (entry == null && canEnter && breakoutsFilled < maxEntriesPerDay && roomOk && oiOk) {
                        lastEntryFade = false;
                        pendingLine = lp;
                        pendingBuf = buf;
                        pendingLevels = lineLevels;
                        pendingPx = bar.c();
                        pendingEm = em;
                        entry = new Entry(d > 0 ? OptionSide.CE : OptionSide.PE, Double.NaN, Double.NaN,
                                "BREAK_RETEST_" + (d > 0 ? "CE" : "PE"));
                        if (!sized(s, d, c)) {                                    // A-452 / A-453
                            entry = null;
                        }
                    }
                }
            }
            if (since > retestMin && b.closes >= holdBars) {
                b.done = true;
                if (!b.retested) {
                    dbg("NO RETEST of %.1f (%s) within %d min", lp, d > 0 ? "up" : "down", retestMin);
                }
            }
        }
        for (Line l : lines) {                                      // new breaks: a five-minute close a buffer beyond
            if (tradeable(l) && l.resistance != null) {
                int d = l.resistance ? 1 : -1;
                if (d * (bar.c() - l.price) > buf) {
                    l.broken = true;
                    breaks.add(new Break(l, d, minute));
                    dbg("BREAK %s of %.1f (touches %d, major %s, fixed %s): close %.1f, buffer %.1f", d > 0 ? "up" : "down",
                            l.price, l.touchPrices.size(), l.major, l.fixed, bar.c(), buf);
                }
            }
        }
        c.put("breaks_open", breaks.stream().anyMatch(b -> !b.done));
        return entry;
    }

    /**
     * A-452 sbr-v18..v21: the entry size for direction d (pendingLots: 2 full, 1 half when the 30-minute OI flow is crowded
     * or the ATM IV fell over 3 minutes); false when v21 skips it (both weak). Without these settings: 1 lot, true.
     */
    private boolean sized(FeatureSnapshot s, int d, Map<String, Boolean> c) {
        if (Double.isNaN(crowdedOiFlow) && !weakIvHalf) {
            return true;
        }
        boolean half = false;
        int weakCount = 0;
        if (!Double.isNaN(crowdedOiFlow)) {
            double f30 = s.options() == null ? Double.NaN : s.options().atmBand3Share30m();
            boolean crowded = Double.isFinite(f30) && d * f30 > crowdedOiFlow;
            c.put("oi_crowded", crowded);
            half |= crowded;
            weakCount += crowded ? 1 : 0;
        }
        if (weakIvHalf) {
            double iv = s.options() == null ? Double.NaN : s.options().atmIvChange3m();
            boolean weak = Double.isFinite(iv) && iv < 0;
            c.put("iv_weak", weak);
            half |= weak;
            weakCount += weak ? 1 : 0;
        }
        pendingLots = half ? 1 : 2;
        if (skipBothWeak && weakCount == 2) {                       // A-453 sbr-v21
            c.put("both_weak_skip", true);
            dbg("skipped: both weak (crowded OI and falling IV)");
            return false;
        }
        return true;
    }

    /** sbr-v2: the levels at entry: tradeable lines other than the traded one, then the known levels. */
    private List<Double> levels(StructureFeatures st, Line traded) {
        List<Double> out = new ArrayList<>();
        for (Line l : lines) {
            if (l != traded && tradeable(l)) {
                out.add(l.price);
            }
        }
        for (double x : new double[] {st.dayOpen(), st.orHigh(), st.orLow(), st.pdh(), st.pdl(), st.prevClose(), st.pwh(),
                st.pwl(), st.pmh(), st.pml()}) {
            if (Double.isFinite(x)) {
                out.add(x);
            }
        }
        if (extraLevels) {                                          // A-439
            for (double x : new double[] {st.prevOrHigh(), st.prevOrLow()}) {
                if (Double.isFinite(x)) {
                    out.add(x);
                }
            }
            if (st.historyLevels() != null) {
                for (Double x : st.historyLevels()) {
                    if (x != null && Double.isFinite(x)) {
                        out.add(x);
                    }
                }
            }
        }
        if (zoneMinTouches > 0) {
            out.addAll(zoneLevels(zoneMinTouches));
        }
        return out;
    }

    /** A-439: the unbroken tested zones with at least {@code minTouches} touches, at their mean. */
    List<Double> zoneLevels(int minTouches) {
        List<Double> out = new ArrayList<>();
        for (Zone z : zones) {
            if (!z.broken && z.prices.size() >= minTouches) {
                out.add(z.mean());
            }
        }
        return out;
    }

    /**
     * sbr-v4 (A-433): the distance, in % of {@code px}, to the level the exit would target (the nearest level at least
     * level_min_em x EM beyond {@code px} in direction d); NaN when there is none.
     */
    private double roomPct(List<Double> levels, double px, int d, double emNow) {
        double best = Double.NaN;
        for (double y : levels) {
            if (d * (y - px) >= levelMinEm * emNow && (Double.isNaN(best) || d * (y - px) < d * (best - px))) {
                best = y;
            }
        }
        return Double.isNaN(best) ? Double.NaN : d * (best - px) / px * 100;
    }

    /** The nearest level >= level_min_em x EM beyond the entry close in direction d (and beyond {@code past} when finite). */
    private double targetLevel(double d, double past) {
        double best = Double.NaN;
        for (double y : positionLevels) {
            if (d * (y - positionPx) >= levelMinEm * positionEm && (Double.isNaN(past) || d * (y - past) > 0)
                    && (Double.isNaN(best) || d * (y - positionPx) < d * (best - positionPx))) {
                best = y;
            }
        }
        return best;
    }

    private void add(double price, boolean major, double closeNow, double tol, Boolean role, boolean fixed) {
        Line best = null;
        for (Line l : lines) {
            if (!l.broken && Math.abs(price - l.price) <= tol
                    && (!sameSideMerge || role == null || l.resistance == null || l.resistance.equals(role))   // A-441
                    && (best == null || Math.abs(price - l.price) < Math.abs(price - best.price))) {
                best = l;
            }
        }
        if (best == null) {
            best = new Line();
            best.price = price;
            best.touchPrices.add(price);
            best.major = major;
            best.fixed = fixed;
            lines.add(best);
        } else {
            best.touchPrices.add(price);
            if (fixed) {
                best.price = price;
                best.fixed = true;
                best.resistance = null;
            } else if (!best.fixed) {
                best.price = best.touchPrices.stream().mapToDouble(Double::doubleValue).average().orElse(price);
            }
            best.major |= major;
        }
        if (tradeable(best) && best.resistance == null) {
            best.resistance = role != null ? role : closeNow < best.price;
            dbg("LINE %.1f now tradeable as %s (touches %d, major %s, fixed %s, close %.1f)", best.price,
                    best.resistance ? "RESISTANCE" : "SUPPORT", best.touchPrices.size(), best.major, best.fixed, closeNow);
        }
    }

    @Override
    protected int strikeOffset(Entry entry, FeatureSnapshot s) {
        String expiry = s.options() == null ? null : s.options().expiry();
        if (expiry == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(s.session(), LocalDate.parse(expiry)) <= 1 ? 1 : 0;
    }

    @Override
    protected String customExit(FeatureSnapshot s, PositionView position, LocalTime time) {
        if (position.openedAt() != null && !position.openedAt().equals(positionOpenedAt)) {
            positionOpenedAt = position.openedAt();
            posDir = position.side() == OptionSide.CE ? 1 : -1;      // A-438
            positionLine = pendingLine;
            positionBuf = pendingBuf;
            lastStopCheck = null;
            positionLevels = pendingLevels;
            positionPx = pendingPx;
            positionEm = pendingEm;
            armed = false;
            bestExt = Double.NaN;
            lastLevelMinute = null;
            level = levelExit ? targetLevel(position.side() == OptionSide.CE ? 1 : -1, Double.NaN) : Double.NaN;
            crossed.clear();
            if (!"off".equals(reclaimExit)) {
                double side = position.side() == OptionSide.CE ? 1 : -1;
                for (double y : positionLevels) {
                    if (side * (y - positionLine) > 0 && side * (y - positionPx) <= 0) {
                        crossed.add(y);
                    }
                }
            }
        }
        LocalTime minute = time.withSecond(0).withNano(0);
        double d = position.side() == OptionSide.CE ? 1 : -1;
        if (!Double.isNaN(fastStopEm) && !minute.equals(lastFastMinute) && Double.isFinite(positionLine)
                && Double.isFinite(positionEm)) {                                       // A-447 sbr-v17
            lastFastMinute = minute;
            double c1 = s.structure().lastMinuteClose();
            if (Double.isFinite(c1) && d * (c1 - positionLine) < -fastStopEm * positionEm) {
                return "FAST_STOP";
            }
        }
        if (levelExit && Double.isFinite(level) && !minute.equals(lastLevelMinute)) {      // this snapshot's one-minute bar
            lastLevelMinute = minute;
            double fav = d > 0 ? s.structure().lastMinuteHigh() : s.structure().lastMinuteLow();
            if (Double.isFinite(fav)) {
                if (!armed && d * (fav - level) >= -levelBandEm * positionEm) {
                    armed = true;
                    bestExt = fav;
                } else if (armed && d * (fav - bestExt) > 0) {
                    bestExt = fav;
                }
            }
        }
        boolean close5 = fiveMinuteClose(minute), closeN = barClose(minute, breakBarMin);
        if ((!close5 && !closeN) || minute.equals(lastStopCheck) || Double.isNaN(positionLine)) {
            return null;
        }
        lastStopCheck = minute;
        List<Bar5> series = breakBarMin == 5 ? bars : barsN;
        if (closeN && !series.isEmpty() && series.getLast().close().equals(minute)) {   // A-437: on the break bar
            Bar5 b = series.getLast();
            if (d * (b.c() - positionLine) < -positionBuf) {
                return "LINE_STOP";
            }
            if (!"off".equals(reclaimExit)) {
                if ("all".equals(reclaimExit)) {
                    for (double y : positionLevels) {
                        if (d * (y - positionLine) > 0 && d * (b.c() - y) > positionBuf && !crossed.contains(y)) {
                            crossed.add(y);
                        }
                    }
                }
                for (double y : crossed) {
                    if (d * (b.c() - y) < -positionBuf) {
                        return "LEVEL_RECLAIM";
                    }
                }
            }
        }
        if (close5 && levelExit && Double.isFinite(level) && !bars.isEmpty() && bars.getLast().close().equals(minute)) {
            Bar5 b = bars.getLast();
            if (d * (b.c() - level) > positionBuf) {                // the level is crossed: the next level beyond it
                level = targetLevel(d, level);
                armed = false;
                bestExt = Double.NaN;
            } else if (armed && d * (bestExt - b.c()) >= levelRejectEm * positionEm) {
                double f = flow15(s);
                if (!Double.isNaN(oiHold) && Double.isFinite(f) && d * f >= oiHold) {
                    level = targetLevel(d, level);                  // A-437 sbr-v6: the flow still supports, hold on
                    armed = false;
                    bestExt = Double.NaN;
                    return null;
                }
                return "LEVEL_REJECT";
            }
        }
        return null;
    }
}
