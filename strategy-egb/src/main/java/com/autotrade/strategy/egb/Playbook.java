package com.autotrade.strategy.egb;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.FuturesFeatures;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.features.snapshot.VolatilityFeatures;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;

/**
 * The user's four-strategy options-buying playbook (A-244, 26 Oct 2026; rules fixed in the ledger before the replay).
 * Per index: NIFTY on 3-minute candles, SENSEX on 5-minute candles, built from the minute closes; ATR = the engine's
 * 3-minute ATR14 (x sqrt(5/3) for SENSEX); "5 points" = 5 NIFTY / 15 SENSEX points. Entries only 09:20-10:30 and
 * 13:00-15:00 (the playbook's "golden windows"), at most {@code max_trades_per_day} per index.
 * <p>
 * The engine exits whole positions, so "book 50 % at 1R, trail the rest" runs as two files of one strategy: the T1 part
 * (its own budget share, exits at the first target) and the RUNNER part (moves its stop to the entry once the first
 * target is reached, then trails). Both decide the same entry on the same snapshot.
 * <ul>
 * <li><b>SQUEEZE</b> (ATM call): a candle closes above a resistance (ORH, PDH, prior ORH, PWH) its previous candle
 *     closed at or below, in the top 25 % of its range; futures RVOL >= 1.5; near-ATM call OI down over 5 minutes and
 *     put OI not down. Stop: the higher of the level - 5 points and the candle low. T1 at 1R; runner: stop to entry,
 *     exit below the previous completed 5-minute candle's low or at the nearer of the next resistance and the highest
 *     call-OI strike.</li>
 * <li><b>AVALANCHE</b> (ATM put): the mirror below a support, bottom 25 %; RVOL >= 1.5; VIX up on the day and over 15
 *     minutes; ATM +- 5 (put - call) OI change over 5 minutes <= -1,500,000 (OI units). Stop: level + 5 points. T1 (40 %)
 *     at 1R; runner (60 %): stop to entry, exit when a 3-minute premium candle closes below its 9-EMA. No 1R within 10
 *     minutes: exit.</li>
 * <li><b>VWAP</b> (call 1 strike ITM): higher lows (and not lower highs); a minute close inside VWAP +- 0.15 ATR in the
 *     current or previous candle; the candle closes above the band; futures RVOL < 1 (volume fading on the pullback);
 *     ATM +- 5 OI change over 5 minutes > 0 and the 3-minute change above its value 3 minutes ago. Stop: a candle close
 *     below VWAP. T1 at the last swing high (1R when it is not above the entry); runner: stop to entry, 5-minute-low
 *     trail, target PDH.</li>
 * <li><b>BOX</b> (ATM call or put): the previous {@code box_candles} candles within {@code box_max_atr} ATR; a candle
 *     closes beyond the box by 0.05 ATR on RVOL >= 1.5 with the 3-minute ATM +- 5 OI change >= +1,000,000 (calls) or
 *     <= -1,000,000 (puts). Stop: the box midpoint; at +20 % premium the stop moves to the entry; target: the next
 *     floor pivot (from the previous day's H / L / C) beyond the entry.</li>
 * </ul>
 * Time-out for every part before its first target: 15 minutes with the index inside a 0.15 ATR band (AVALANCHE: 10
 * minutes without 1R).
 * <p>
 * A-246 ("all-weather" matrix, 27 Oct 2026), no time-out:
 * <ul>
 * <li><b>MAC_SQUEEZE</b> (ATM call): the previous {@code box_candles} candles within {@code box_max_atr} ATR; a candle
 *     closes above the box high on RVOL >= 1.5 with near-ATM call OI down over 5 minutes. Stop: box high - 5 points.
 *     T1 at 1R; runner: stop to entry, 5-minute-low trail.</li>
 * <li><b>ORL_RETEST</b> (ATM put): an earlier candle today closed below ORL; this candle's highest minute close reached
 *     ORL - 0.25 ATR and it closes below ORL, on RVOL < 1 with VIX up over 15 minutes. Stop: ORL + 5 points; at +20 %
 *     premium the stop moves to cost; target: the nearest of PDL / previous ORL / PWL below the entry.</li>
 * <li><b>PROBE</b> (never trades): at 10:15 records the stand-aside flags: futures OI flat (|change since the first
 *     print| <= 1 % of OI) and futures volume below normal (mean RVOL 09:16-10:15 < 1).</li>
 * </ul>
 */
final class Playbook extends SetupStrategy {

    enum Kind { SQUEEZE, AVALANCHE, VWAP, BOX, MAC_SQUEEZE, ORL_RETEST, PROBE }

    enum Role { T1, RUNNER, FULL }

    private final Kind kind;
    private final Role role;
    private final boolean nifty;
    private final int candleMin;
    private final double bufferPts;
    private final int boxCandles;
    private final double boxMaxAtr;
    private final LocalTime gapFrom;
    private final LocalTime gapUntil;
    private final int maxTradesPerDay;
    private final double avalancheCoiMax;
    private final double boxCoiMin;

    private int trades;
    private boolean closedBelowOrl;
    private double rvolSum;
    private int rvolCount;
    private double prevCandleClose = Double.NaN;
    private final List<Double> coi3History = new ArrayList<>();
    // the open trade
    private double entrySpot = Double.NaN, oneR = Double.NaN, target2 = Double.NaN, atrAtEntry = Double.NaN;
    private LocalTime entryTime;
    private boolean t1Hit, beArmed;
    private int minutesHeld;
    private final List<Double> premiumCloses = new ArrayList<>();
    private double ema9 = Double.NaN;
    private double vwapStop = Double.NaN;

    Playbook(ThresholdConfig c, String underlying) {
        super(c);
        kind = Kind.valueOf(c.getString("setup.kind"));
        role = Role.valueOf(c.getString("setup.role"));
        nifty = !"SENSEX".equals(underlying);
        String ix = nifty ? "_nifty" : "_sensex";
        candleMin = c.getInt("setup.candle_min" + ix);
        bufferPts = c.getDouble("setup.buffer_points" + ix);
        boolean boxed = kind == Kind.BOX || kind == Kind.MAC_SQUEEZE;
        boxCandles = boxed ? c.getInt("setup.box_candles" + ix) : 0;
        boxMaxAtr = boxed ? c.getDouble("setup.box_max_atr" + ix) : Double.NaN;
        gapFrom = c.getTime("setup.gap_from");
        gapUntil = c.getTime("setup.gap_until");
        maxTradesPerDay = c.getInt("setup.max_trades_per_day");
        avalancheCoiMax = kind == Kind.AVALANCHE ? c.getDouble("setup.coi_5m_max") : Double.NaN;
        boxCoiMin = kind == Kind.BOX ? c.getDouble("setup.coi_3m_min_abs") : Double.NaN;
        boolean full = kind == Kind.BOX || kind == Kind.ORL_RETEST || kind == Kind.PROBE;
        if (full != (role == Role.FULL)) {
            throw new IllegalArgumentException(c.sourceName() + ": BOX, ORL_RETEST and PROBE run as FULL, the others as T1 or RUNNER");
        }
    }

    private boolean candleClose(LocalTime time) {
        int m = (int) Duration.between(LocalTime.of(9, 15), time).toMinutes();
        return m > 0 && m % candleMin == 0;
    }

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
        double spot = close(0);
        double atr = st.atr3m() * (nifty ? 1.0 : Math.sqrt(5.0 / 3.0));
        double coi3Ago = coi3History.size() >= 3 ? coi3History.get(coi3History.size() - 3) : Double.NaN;
        coi3History.add(o.atmBandNet3m());
        boolean window = !(!time.isBefore(gapFrom) && time.isBefore(gapUntil));
        c.put("golden_window", window);
        if (kind == Kind.PROBE) {
            probe(f, time, c);
            return null;
        }
        if (!candleClose(time) || !Double.isFinite(spot) || !(atr > 0)) {
            return null;
        }
        double prev = prevCandleClose;
        prevCandleClose = spot;
        double hi = extreme(0, candleMin - 1, true), lo = extreme(0, candleMin - 1, false);
        double pos = hi > lo ? (spot - lo) / (hi - lo) : Double.NaN;
        boolean brokeBefore = closedBelowOrl;                        // a break on an earlier candle, not this one
        closedBelowOrl |= st.orComplete() && spot < st.orLow();
        Entry e = switch (kind) {
            case SQUEEZE -> squeeze(st, f, o, spot, prev, lo, pos, c);
            case AVALANCHE -> avalanche(st, f, o, v, spot, prev, hi, pos, c);
            case VWAP -> vwap(st, f, o, spot, atr, coi3Ago, c);
            case BOX -> box(st, f, o, spot, atr, c);
            case MAC_SQUEEZE -> macSqueeze(f, o, spot, atr, c);
            case ORL_RETEST -> orlRetest(st, f, v, spot, hi, atr, brokeBefore, c);
            case PROBE -> null;
        };
        if (e == null || !canEnter || !window || trades >= maxTradesPerDay) {
            return null;
        }
        trades++;
        entrySpot = spot;
        atrAtEntry = atr;
        entryTime = time;
        t1Hit = false;
        beArmed = false;
        minutesHeld = 0;
        premiumCloses.clear();
        ema9 = Double.NaN;
        double sign = e.side() == OptionSide.CE ? 1 : -1;
        oneR = Double.isFinite(e.stop()) ? spot + sign * Math.abs(spot - e.stop()) : Double.NaN;
        if (kind == Kind.VWAP) {
            double swing = st.lastSwingHigh();
            double first = Double.isFinite(swing) && swing > spot ? swing : spot + Math.abs(spot - vwapStop);
            oneR = first;
            target2 = st.pdh() > spot ? st.pdh() : Double.NaN;
        }
        double t1 = role == Role.T1 ? oneR : role == Role.FULL ? e.target() : Double.NaN;
        return new Entry(e.side(), e.stop(), t1, e.reason());
    }

    private static Map<String, Double> levels(StructureFeatures st, boolean up) {
        return up ? MarketPhase.resistances(st) : MarketPhase.supports(st);
    }

    /** The level this candle crossed (its previous candle closed at or on the other side); NaN when none. */
    private static double crossed(Map<String, Double> levels, double prev, double spot, boolean up) {
        double best = Double.NaN;
        for (double l : levels.values()) {
            boolean x = Double.isFinite(l) && Double.isFinite(prev) && (up ? prev <= l && spot > l : prev >= l && spot < l);
            if (x && (Double.isNaN(best) || Math.abs(spot - l) < Math.abs(spot - best))) {
                best = l;
            }
        }
        return best;
    }

    private Entry squeeze(StructureFeatures st, FuturesFeatures f, OptionsFeatures o, double spot, double prev, double lo,
                          double pos, Map<String, Boolean> c) {
        double level = crossed(levels(st, true), prev, spot, true);
        boolean price = Double.isFinite(level) && pos >= 0.75;
        boolean volume = f.rvolTod() >= 1.5;
        boolean oi = o.ceOiChange5m() < 0 && o.peOiChange5m() >= 0;
        c.put("break_top25", price);
        c.put("futures_volume", volume);
        c.put("call_oi_covering", oi);
        if (!(price && volume && oi)) {
            return null;
        }
        double stop = Math.max(level - bufferPts, lo);
        if (!(stop < spot)) {
            return null;
        }
        double next = Double.NaN;                                     // runner target: the nearer of the two
        for (double l : levels(st, true).values()) {
            if (Double.isFinite(l) && l > spot && (Double.isNaN(next) || l < next)) {
                next = l;
            }
        }
        double barrier = o.callBarrierStrike();
        if (Double.isFinite(barrier) && barrier > spot && (Double.isNaN(next) || barrier < next)) {
            next = barrier;
        }
        target2 = next;
        return new Entry(OptionSide.CE, stop, Double.NaN, "SQUEEZE");
    }

    private Entry avalanche(StructureFeatures st, FuturesFeatures f, OptionsFeatures o, VolatilityFeatures v, double spot,
                            double prev, double hi, double pos, Map<String, Boolean> c) {
        double level = crossed(levels(st, false), prev, spot, false);
        boolean price = Double.isFinite(level) && pos <= 0.25;
        boolean volume = f.rvolTod() >= 1.5;
        boolean vix = v.vixChangeDay() > 0 && v.vixChange15m() > 0;
        boolean oi = o.atmBandNet5m() <= avalancheCoiMax;
        c.put("break_bottom25", price);
        c.put("futures_volume", volume);
        c.put("vix_rising", vix);
        c.put("coi_5m_deep_negative", oi);
        if (!(price && volume && vix && oi)) {
            return null;
        }
        target2 = Double.NaN;
        return new Entry(OptionSide.PE, level + bufferPts, Double.NaN, "AVALANCHE");
    }

    private Entry vwap(StructureFeatures st, FuturesFeatures f, OptionsFeatures o, double spot, double atr, double coi3Ago,
                       Map<String, Boolean> c) {
        double vw = st.vwapSpotProxy(), band = 0.15 * atr;
        boolean trend = st.higherLows() && !st.lowerHighs();
        double low = extreme(0, 2 * candleMin - 1, false);
        boolean touched = Double.isFinite(vw) && low >= vw - band && low <= vw + band;
        boolean lift = spot > vw + band;
        boolean fading = f.rvolTod() < 1.0;
        boolean oi = o.atmBandNet5m() > 0 && o.atmBandNet3m() > coi3Ago;
        c.put("uptrend", trend);
        c.put("vwap_touch_lift", touched && lift);
        c.put("volume_fading", fading);
        c.put("coi_positive_rising", oi);
        if (!(trend && touched && lift && fading && oi)) {
            return null;
        }
        vwapStop = vw;
        return new Entry(OptionSide.CE, Double.NaN, Double.NaN, "VWAP");
    }

    private Entry box(StructureFeatures st, FuturesFeatures f, OptionsFeatures o, double spot, double atr,
                      Map<String, Boolean> c) {
        int span = boxCandles * candleMin;
        boolean full = closes.size() >= candleMin + span;              // the whole lookback is today's (not 3 minutes at 09:21)
        double hi = extreme(candleMin, candleMin + span - 1, true), lo = extreme(candleMin, candleMin + span - 1, false);
        boolean tight = full && Double.isFinite(hi) && Double.isFinite(lo) && hi - lo <= boxMaxAtr * atr;
        boolean up = spot > hi + 0.05 * atr, down = spot < lo - 0.05 * atr;
        boolean volume = f.rvolTod() >= 1.5;
        double coi = o.atmBandNet3m();
        boolean oiUp = coi >= boxCoiMin, oiDown = coi <= -boxCoiMin;
        c.put("box_tight", tight);
        c.put("box_break", tight && (up || down));
        c.put("futures_volume", volume);
        c.put("coi_3m_surge", (up && oiUp) || (down && oiDown));
        if (!(tight && volume && ((up && oiUp) || (down && oiDown)))) {
            return null;
        }
        double mid = (hi + lo) / 2;
        double pp = (st.pdh() + st.pdl() + st.prevClose()) / 3, range = st.pdh() - st.pdl();
        double[] pivots = {pp, 2 * pp - st.pdl(), pp + range, 2 * pp - st.pdh(), pp - range};
        double target = Double.NaN;
        for (double p : pivots) {
            boolean beyond = up ? p > spot : p < spot;
            if (Double.isFinite(p) && beyond && (Double.isNaN(target) || Math.abs(p - spot) < Math.abs(target - spot))) {
                target = p;
            }
        }
        return new Entry(up ? OptionSide.CE : OptionSide.PE, mid, target, up ? "BOX_UP" : "BOX_DOWN");
    }

    private Entry macSqueeze(FuturesFeatures f, OptionsFeatures o, double spot, double atr, Map<String, Boolean> c) {
        int span = boxCandles * candleMin;
        boolean full = closes.size() >= candleMin + span;
        double hi = extreme(candleMin, candleMin + span - 1, true), lo = extreme(candleMin, candleMin + span - 1, false);
        boolean tight = full && Double.isFinite(hi) && Double.isFinite(lo) && hi - lo <= boxMaxAtr * atr;
        boolean up = tight && spot > hi;
        boolean volume = f.rvolTod() >= 1.5;
        boolean oi = o.ceOiChange5m() < 0;
        c.put("box_tight", tight);
        c.put("box_break_up", up);
        c.put("futures_volume", volume);
        c.put("call_oi_falling", oi);
        if (!(up && volume && oi)) {
            return null;
        }
        target2 = Double.NaN;                                          // the runner trails only
        return new Entry(OptionSide.CE, hi - bufferPts, Double.NaN, "MAC_SQUEEZE");
    }

    private Entry orlRetest(StructureFeatures st, FuturesFeatures f, VolatilityFeatures v, double spot, double candleHigh,
                            double atr, boolean brokeBefore, Map<String, Boolean> c) {
        double orl = st.orComplete() ? st.orLow() : Double.NaN;
        boolean retest = brokeBefore && Double.isFinite(orl) && candleHigh >= orl - 0.25 * atr && spot < orl;
        boolean quiet = f.rvolTod() < 1.0;
        boolean vix = v.vixChange15m() > 0;
        c.put("orl_broken_earlier", brokeBefore);
        c.put("orl_retest_failed", retest);
        c.put("low_volume", quiet);
        c.put("vix_rising", vix);
        if (!(retest && quiet && vix)) {
            return null;
        }
        double target = Double.NaN;
        for (Map.Entry<String, Double> l : MarketPhase.supports(st).entrySet()) {
            double p = l.getValue();
            if (!"ORL".equals(l.getKey()) && Double.isFinite(p) && p < spot && (Double.isNaN(target) || p > target)) {
                target = p;
            }
        }
        return new Entry(OptionSide.PE, orl + bufferPts, target, "ORL_RETEST");
    }

    /** Stand-aside flags, recorded at 10:15 (A-246 S). */
    private void probe(FuturesFeatures f, LocalTime time, Map<String, Boolean> c) {
        if (time.isAfter(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(10, 15)) && Double.isFinite(f.rvolTod())) {
            rvolSum += f.rvolTod();
            rvolCount++;
        }
        if (time.getHour() == 10 && time.getMinute() == 15) {
            boolean flat = f.oi() > 0 && Math.abs(f.oiChangeDay()) <= 0.01 * f.oi();
            boolean low = rvolCount > 0 && rvolSum / rvolCount < 1.0;
            c.put("sa_known", f.oi() > 0 && rvolCount > 0);
            c.put("sa_flat_oi", flat);
            c.put("sa_low_volume", low);
            c.put("sa_stand_aside", flat || low);
        }
    }

    @Override
    protected int entryLots(Entry entry) {
        return 1;
    }

    @Override
    protected String customExit(FeatureSnapshot s, PositionView position, LocalTime time) {
        double spot = close(0);
        if (!Double.isFinite(spot) || Double.isNaN(entrySpot)) {
            return null;
        }
        minutesHeld++;
        double sign = position.side() == OptionSide.CE ? 1 : -1;
        if (!t1Hit && Double.isFinite(oneR) && sign * (spot - oneR) >= 0) {
            t1Hit = true;
        }
        // premium 3-minute candles from the entry (AVALANCHE runner's 9-EMA)
        if (minutesHeld % 3 == 0 && position.bid() > 0) {
            double p = position.bid();
            premiumCloses.add(p);
            ema9 = Double.isNaN(ema9) ? p : ema9 + (2.0 / 10) * (p - ema9);
        }
        if (kind == Kind.VWAP && candleClose(time) && spot < s.structure().vwapSpotProxy()) {
            return "VWAP_STOP";
        }
        if (kind == Kind.BOX) {
            beArmed |= position.bid() >= position.averagePremium() * 1.20;
            if (beArmed && sign * (spot - entrySpot) < 0) {
                return "BE_STOP";
            }
        }
        if (kind == Kind.ORL_RETEST) {                                  // the premium stop moves to cost after +20 %
            beArmed |= position.bid() >= position.averagePremium() * 1.20;
            return beArmed && position.bid() > 0 && position.bid() <= position.averagePremium() ? "BE_STOP" : null;
        }
        if (!t1Hit) {
            if (kind == Kind.AVALANCHE && minutesHeld >= 10) {
                return "TIME_10";
            }
            boolean timeOut = kind == Kind.SQUEEZE || kind == Kind.VWAP || kind == Kind.BOX;   // A-246 kinds have none
            if (timeOut && minutesHeld >= 15) {
                double hi = extreme(0, 14, true), lo = extreme(0, 14, false);
                if (hi - lo <= 0.15 * atrAtEntry) {
                    return "TIME_OUT";
                }
            }
            return null;
        }
        if (role != Role.RUNNER) {
            return null;
        }
        if (sign * (spot - entrySpot) < 0) {
            return "BE_STOP";
        }
        if (Double.isFinite(target2) && sign * (spot - target2) >= 0) {
            return "TARGET2";
        }
        if (kind == Kind.AVALANCHE) {
            return minutesHeld % 3 == 0 && premiumCloses.size() >= 2 && position.bid() < ema9 ? "EMA9" : null;
        }
        // the low of the previous completed 5-minute candle (09:15-based; at a candle close, the one before it)
        int m = (int) Duration.between(LocalTime.of(9, 15), time).toMinutes();
        int start = m % 5 == 0 ? 5 : m % 5;
        double prevLow = extreme(start, start + 4, false);
        return Double.isFinite(prevLow) && spot < prevLow ? "TRAIL_5M" : null;
    }
}
