package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.CueFeatures;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.FuturesFeatures;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.features.snapshot.VolatilityFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * The market-phase rows with the user's relaxations (A-240, 26 Oct 2026, after A-239 found 0-3 trades a row in 28 tick
 * days; files market-phase-*.v2.yaml). Per index: NIFTY on 3-minute candles, SENSEX on 5-minute candles, from the minute
 * closes (a candle's high / low = its highest / lowest minute close). ATR = ATR14 of the candle: the engine's 3-minute
 * Wilder ATR14 for NIFTY, times sqrt(5/3) for SENSEX (an approximation of the 5-minute ATR14).
 * <p>
 * Price (any one): SQUEEZE / AVALANCHE: A-239's level break; a box breakout at a candle close (the previous N candles
 * within {@code box_max_atr} ATR, the close beyond the box by {@code breakout_buffer_atr} ATR); an anticipation entry (the
 * index within {@code anticipation_distance_atr} ATR short of an unbroken level and the fast-window ATM OI differential a
 * surge in the trade's direction: at least {@code anticipation_k} times the day's mean absolute value so far, the user's
 * fixed 1,000,000 being the NIFTY median in OI units). EXPANSION: A-239's support bounce, or a VWAP pullback (a minute
 * close inside VWAP +- band in the last {@code vwap_touch_window_min} minutes, the candle closing above the upper band, the
 * fast OI differential positive and above its value 3 minutes ago).
 * <p>
 * Columns: VIX below {@code vix_max} for calls; above {@code vix_min} and not down more than {@code vix_crush_pct} % in 15
 * minutes for puts. Futures time-of-day RVOL at least {@code min_rvol_tod}; futures build-up over the per-index window.
 * ATM +- 5 OI differential (absolute, features v15 band) over both per-index windows in the trade's direction. FII
 * positioning only sizes: half size (1 of {@code intended_lots} 2) unless the last file's FII long share moved the trade's
 * way; blocked only below {@code fii_block_below} % (calls) or above {@code fii_block_above} % (puts), the 5th / 95th
 * percentiles of Sep 2025-Aug 2026. At most {@code max_trades_per_day} trades per index per day.
 * <p>
 * v3 (A-241): box bounds that occur (the 10th percentile of the box width on 23 Jan-31 Aug bars), a 50 % emergency
 * premium stop, and no entries on an index named in {@code skip_own_expiry_underlyings} on its own expiry day.
 */
final class MarketPhaseRelaxed extends SetupStrategy {

    private final MarketPhase.Phase phase;
    private final boolean nifty;
    private final int candleMin;
    private final int boxCandles;
    private final double boxMaxAtr;
    private final double bufferAtr;
    private final double vwapBandAtr;
    private final int vwapTouchWindowMin;
    private final double anticipationDistanceAtr;
    private final double anticipationK;
    private final double anticipationStopAtr;
    private final int breakWindowMin;
    private final int bounceWindowMin;
    private final double touchAtr;
    private final double bounceAtr;
    private final int futuresWindowMin;
    private final int coiFastMin;
    private final int coiSlowMin;
    private final double minRvolTod;
    private final double vixMax;
    private final double vixMin;
    private final double vixCrushPct;
    private final double fiiBlockBelow;
    private final double fiiBlockAbove;
    private final int maxTradesPerDay;
    /** A-241 (v3): no entries on this index's own expiry day (dte 0); false when the file does not name it. */
    private final boolean skipOwnExpiry;

    private final ArrayDeque<double[]> futures = new ArrayDeque<>();
    /** The fast-window OI differential per minute, newest last (for "rising" and the day's mean). */
    private final ArrayDeque<Double> coiFast = new ArrayDeque<>();
    private double coiAbsSum;
    private int coiCount;
    private final Map<String, Integer> breaks = new LinkedHashMap<>();
    private int minute;
    private double prevSpot = Double.NaN;
    private int trades;
    private boolean fullSize;

    MarketPhaseRelaxed(ThresholdConfig c, String underlying) {
        super(c);
        phase = MarketPhase.Phase.valueOf(c.getString("setup.phase"));
        nifty = !"SENSEX".equals(underlying);
        String ix = nifty ? "_nifty" : "_sensex";
        candleMin = c.getInt("setup.candle_min" + ix);
        boxCandles = c.getInt("setup.box_candles" + ix);
        boxMaxAtr = c.getDouble("setup.box_max_atr" + ix);
        bufferAtr = c.getDouble("setup.breakout_buffer_atr" + ix);
        vwapBandAtr = c.getDouble("setup.vwap_band_atr" + ix);
        futuresWindowMin = c.getInt("setup.futures_window_min" + ix);
        coiFastMin = c.getInt("setup.coi_fast_min" + ix);
        coiSlowMin = c.getInt("setup.coi_slow_min" + ix);
        vwapTouchWindowMin = c.getInt("setup.vwap_touch_window_min");
        anticipationDistanceAtr = c.getDouble("setup.anticipation_distance_atr");
        anticipationK = c.getDouble("setup.anticipation_k");
        anticipationStopAtr = c.getDouble("setup.anticipation_stop_atr");
        breakWindowMin = c.getInt("setup.break_window_min");
        bounceWindowMin = c.getInt("setup.bounce_window_min");
        touchAtr = c.getDouble("setup.touch_atr");
        bounceAtr = c.getDouble("setup.bounce_atr");
        minRvolTod = c.getDouble("setup.min_rvol_tod");
        vixMax = c.getDouble("setup.vix_max");
        vixMin = c.getDouble("setup.vix_min");
        vixCrushPct = c.getDouble("setup.vix_crush_pct");
        fiiBlockBelow = c.getDouble("setup.fii_block_below");
        fiiBlockAbove = c.getDouble("setup.fii_block_above");
        maxTradesPerDay = c.getInt("setup.max_trades_per_day");
        skipOwnExpiry = c.has("setup.skip_own_expiry_underlyings") && java.util.Set.of(
                c.getString("setup.skip_own_expiry_underlyings").replace(" ", "").split(",")).contains(underlying);
        if (!java.util.Set.of(3, 5, 10).contains(coiFastMin) || !java.util.Set.of(3, 5, 10).contains(coiSlowMin)) {
            throw new IllegalArgumentException(c.sourceName() + ": coi windows must be 3, 5 or 10 minutes");
        }
    }

    private static double net(OptionsFeatures o, int minutes) {
        return switch (minutes) {
            case 3 -> o.atmBandNet3m();
            case 5 -> o.atmBandNet5m();
            default -> o.atmBandNet10m();
        };
    }

    /** True at the snapshot carrying the close of this index's candle (09:15-based). */
    private boolean candleClose(LocalTime time) {
        int m = (int) Duration.between(LocalTime.of(9, 15), time).toMinutes();
        return m > 0 && m % candleMin == 0;
    }

    /** The highest (lowest) minute close from {@code fromBack} to {@code toBack} minutes ago; NaN when none. */
    private double extreme(int fromBack, int toBack, boolean high) {
        double v = Double.NaN;
        for (int back = fromBack; back <= toBack; back++) {
            double x = close(back);
            if (Double.isFinite(x)) {
                v = Double.isNaN(v) ? x : high ? Math.max(v, x) : Math.min(v, x);
            }
        }
        return v;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        FuturesFeatures f = s.futures();
        OptionsFeatures o = s.options();
        VolatilityFeatures v = s.volatility();
        boolean calls = phase != MarketPhase.Phase.AVALANCHE;
        double spot = close(0);
        double atr = st.atr3m() * (nifty ? 1.0 : Math.sqrt(5.0 / 3.0));
        minute++;

        futures.addLast(new double[] {f.price(), f.oi()});
        while (futures.size() > futuresWindowMin + 1) {
            futures.removeFirst();
        }
        double futPrice = Double.NaN, futOi = Double.NaN;
        if (futures.size() == futuresWindowMin + 1) {
            futPrice = futures.getLast()[0] - futures.getFirst()[0];
            futOi = futures.getLast()[1] - futures.getFirst()[1];
        }
        double fast = net(o, coiFastMin), slow = net(o, coiSlowMin);
        double fast3Ago = coiFast.size() >= 3 ? coiFast.toArray(new Double[0])[coiFast.size() - 3] : Double.NaN;
        double meanAbs = coiCount >= 10 ? coiAbsSum / coiCount : Double.NaN;   // the day's mean before this minute
        coiFast.addLast(fast);
        while (coiFast.size() > 5) {
            coiFast.removeFirst();
        }
        if (Double.isFinite(fast)) {
            coiAbsSum += Math.abs(fast);
            coiCount++;
        }

        // ---- price: any one setup
        Entry plan = null;
        boolean level = false, box = false, anticipation = false, vwap = false, bounce = false;
        double sign = calls ? 1 : -1;
        if (Double.isFinite(spot) && atr > 0) {
            if (phase == MarketPhase.Phase.EXPANSION) {
                plan = bounce(st, spot, atr);
                bounce = plan != null;
                if (plan == null && candleClose(time)) {
                    double vw = st.vwapSpotProxy(), band = vwapBandAtr * atr;
                    double low = extreme(1, vwapTouchWindowMin, false);
                    boolean touched = Double.isFinite(vw) && low >= vw - band && low <= vw + band;
                    boolean rising = fast > 0 && Double.isFinite(fast3Ago) && fast > fast3Ago;
                    vwap = touched && spot > vw + band && rising;
                    if (vwap) {
                        plan = new Entry(OptionSide.CE, vw - band, Double.NaN, "VWAP_PULLBACK");
                    }
                }
            } else {
                Map<String, Double> levels = calls ? MarketPhase.resistances(st) : MarketPhase.supports(st);
                if (Double.isFinite(prevSpot) && !time.isBefore(LocalTime.of(9, 30))) {
                    for (Map.Entry<String, Double> e : levels.entrySet()) {
                        double l = e.getValue();
                        if (Double.isFinite(l) && sign * (prevSpot - l) <= 0 && sign * (spot - l) > 0) {
                            breaks.putIfAbsent(e.getKey(), minute);
                        }
                    }
                }
                double best = Double.NaN;
                for (Map.Entry<String, Integer> b : breaks.entrySet()) {
                    Double l = levels.get(b.getKey());
                    if (l != null && minute - b.getValue() <= breakWindowMin && sign * (spot - l) > 0
                            && (Double.isNaN(best) || Math.abs(spot - l) < Math.abs(spot - best))) {
                        best = l;
                    }
                }
                level = Double.isFinite(best);
                if (level) {
                    plan = new Entry(calls ? OptionSide.CE : OptionSide.PE, best, Double.NaN, "LEVEL_BREAK");
                }
                if (plan == null && candleClose(time)) {
                    int span = boxCandles * candleMin;            // the N candles before the current one
                    double hi = extreme(candleMin, candleMin + span - 1, true);
                    double lo = extreme(candleMin, candleMin + span - 1, false);
                    boolean tight = Double.isFinite(hi) && Double.isFinite(lo) && hi - lo <= boxMaxAtr * atr;
                    box = tight && (calls ? spot > hi + bufferAtr * atr : spot < lo - bufferAtr * atr)
                            && f.rvolTod() >= 1.5;
                    if (box) {
                        plan = new Entry(calls ? OptionSide.CE : OptionSide.PE, calls ? hi : lo, Double.NaN, "BOX_BREAK");
                    }
                }
                if (plan == null && Double.isFinite(meanAbs) && meanAbs > 0) {
                    boolean surge = sign * fast >= anticipationK * meanAbs;
                    for (double l : levels.values()) {
                        double short_ = sign * (l - spot);                  // distance still to go to the level
                        if (Double.isFinite(l) && short_ >= 0 && short_ <= anticipationDistanceAtr * atr && surge) {
                            anticipation = true;
                            plan = new Entry(calls ? OptionSide.CE : OptionSide.PE, spot - sign * anticipationStopAtr * atr,
                                    Double.NaN, "ANTICIPATION");
                            break;
                        }
                    }
                }
            }
        }
        prevSpot = spot;
        boolean price = plan != null;

        // ---- the other columns
        double vix = v.vix();
        boolean vixOk = calls ? vix < vixMax : vix > vixMin && v.vixChange15m() >= -vixCrushPct / 100.0 * vix;
        boolean volume = f.rvolTod() >= minRvolTod;
        boolean buildUp = switch (phase) {
            case SQUEEZE -> futPrice > 0 && futOi < 0;
            case EXPANSION -> futPrice > 0 && futOi > 0;
            case AVALANCHE -> futPrice < 0 && futOi > 0;
        };
        boolean coi = sign * fast > 0 && sign * slow > 0;
        CueFeatures cues = s.cues();
        double ratio = cues == null ? Double.NaN : cues.fiiIndexFutLongPct();
        double change = cues == null ? Double.NaN : cues.fiiIndexFutLongPctChange();
        boolean fiiExtreme = calls ? ratio < fiiBlockBelow : ratio > fiiBlockAbove;   // NaN: not extreme
        fullSize = sign * change > 0;                                                  // NaN: half size

        c.put("price_level", price);
        c.put("level_break", level);
        c.put("support_bounce", bounce);
        c.put("box_break", box);
        c.put("vwap_pullback", vwap);
        c.put("anticipation", anticipation);
        c.put("vix", vixOk);
        c.put("futures_volume", volume);
        c.put("futures_oi", buildUp);
        c.put("atm_band_coi", coi);
        c.put("fii_not_extreme", !fiiExtreme);
        c.put("fii_full_size", fullSize);
        if (skipOwnExpiry && s.regime().dteTradingDays() == 0) {
            c.put("not_own_expiry", false);                                    // A-241: state updated, no entry
            return null;
        }
        if (!canEnter || trades >= maxTradesPerDay || !(price && vixOk && volume && buildUp && coi && !fiiExtreme)) {
            return null;
        }
        trades++;
        return plan;
    }

    @Override
    protected int entryLots(Entry entry) {
        return fullSize ? 2 : 1;
    }

    /** A-239's support bounce (EXPANSION). */
    private Entry bounce(StructureFeatures st, double spot, double atr) {
        if (!(spot > close(1))) {
            return null;
        }
        double low = extreme(1, bounceWindowMin, false);
        if (!Double.isFinite(low)) {
            return null;
        }
        for (double l : MarketPhase.supports(st).values()) {
            if (Double.isFinite(l) && Math.abs(low - l) <= touchAtr * atr && spot - l >= bounceAtr * atr) {
                return new Entry(OptionSide.CE, l - touchAtr * atr, Double.NaN, "SUPPORT_BOUNCE");
            }
        }
        return null;
    }
}
