package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.FuturesFeatures;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.features.snapshot.VolatilityFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * The three trade rows of the user's market-phase table (A-239, 26 Oct 2026; NIFTY BANK column dropped by the user).
 * Decided on every 1-minute close; every condition of the row must hold on the same close; one trade per index per day.
 * <ul>
 * <li><b>SQUEEZE</b> (ATM call): the index broke above a resistance (ORH, PDH, prior ORH, PWH) within the last
 *     {@code break_window_min} closes and is still above it; VIX below {@code vix_max} and up over 15 minutes; futures
 *     price up and OI down over 15 minutes (short covering) with time-of-day RVOL at least {@code min_rvol_tod}; ATM ± 5
 *     call OI falling and put OI rising over 3 minutes; FII index-futures long share up in the last published file.</li>
 * <li><b>EXPANSION</b> (ITM call): within the last {@code bounce_window_min} closes the index came within
 *     {@code touch_atr} ATR(3m) of a support (ORL, PDL, prior ORL, PWL) without closing more than that below it, and now
 *     closes {@code bounce_atr} ATR or more above it and above the previous close; VIX flat (15-minute change within
 *     {@code vix_flat_pct} % of its level); futures price up and OI up over 15 minutes (long build-up) with RVOL at least
 *     {@code min_rvol_tod}; ATM ± 5 (put − call) OI change positive over 3 and 5 minutes with put OI rising; FII long
 *     share up.</li>
 * <li><b>AVALANCHE</b> (ATM put): SQUEEZE's mirror below a support (ORL, PDL, prior ORL, PWL), with VIX spiking
 *     (up over 15 minutes and {@code vix_spike_day_pct} % or more above the previous close, no level cap); futures
 *     price down and OI up (short build-up); put OI falling and call OI rising; FII long share down.</li>
 * </ul>
 * The table's two no-trade rows (fakeout: low volume, OI against the move; churn: no break, flat OI) cannot pass any of
 * these rows, so they need no code. Exits: the index closes back through the level (stop), the time stop, flat_by, and
 * the resting premium stop.
 */
final class MarketPhase extends SetupStrategy {

    enum Phase { SQUEEZE, EXPANSION, AVALANCHE }

    private static final int FUTURES_WINDOW_MIN = 15;

    private final Phase phase;
    private final int breakWindowMin;
    private final int bounceWindowMin;
    private final double touchAtr;
    private final double bounceAtr;
    private final double vixMax;
    private final double vixFlatPct;
    private final double vixSpikeDayPct;
    private final double minRvolTod;
    /** Futures {price, OI} per minute close, newest last. */
    private final ArrayDeque<double[]> futures = new ArrayDeque<>();
    /** Level name → the minute its break (SQUEEZE / AVALANCHE) was first seen. */
    private final Map<String, Integer> breaks = new LinkedHashMap<>();
    private int minute;
    private double prevSpot = Double.NaN;
    private boolean taken;

    MarketPhase(ThresholdConfig c) {
        super(c);
        phase = Phase.valueOf(c.getString("setup.phase"));
        minRvolTod = c.getDouble("setup.min_rvol_tod");
        breakWindowMin = phase == Phase.EXPANSION ? 0 : c.getInt("setup.break_window_min");
        bounceWindowMin = phase == Phase.EXPANSION ? c.getInt("setup.bounce_window_min") : 0;
        touchAtr = phase == Phase.EXPANSION ? c.getDouble("setup.touch_atr") : Double.NaN;
        bounceAtr = phase == Phase.EXPANSION ? c.getDouble("setup.bounce_atr") : Double.NaN;
        vixMax = phase == Phase.SQUEEZE ? c.getDouble("setup.vix_max") : Double.NaN;
        vixFlatPct = phase == Phase.EXPANSION ? c.getDouble("setup.vix_flat_pct") : Double.NaN;
        vixSpikeDayPct = phase == Phase.AVALANCHE ? c.getDouble("setup.vix_spike_day_pct") : Double.NaN;
    }

    static Map<String, Double> resistances(StructureFeatures st) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (st.orComplete()) {
            m.put("ORH", st.orHigh());
        }
        m.put("PDH", st.pdh());
        m.put("pORH", st.prevOrHigh());
        m.put("PWH", st.pwh());
        return m;
    }

    static Map<String, Double> supports(StructureFeatures st) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (st.orComplete()) {
            m.put("ORL", st.orLow());
        }
        m.put("PDL", st.pdl());
        m.put("pORL", st.prevOrLow());
        m.put("PWL", st.pwl());
        return m;
    }

    @Override
    protected Entry evaluate(FeatureSnapshot s, LocalTime time, boolean canEnter, Map<String, Boolean> c) {
        StructureFeatures st = s.structure();
        FuturesFeatures f = s.futures();
        OptionsFeatures o = s.options();
        VolatilityFeatures v = s.volatility();
        double spot = close(0), atr = st.atr3m();
        minute++;
        futures.addLast(new double[] {f.price(), f.oi()});
        while (futures.size() > FUTURES_WINDOW_MIN + 1) {
            futures.removeFirst();
        }
        double futPriceChange = Double.NaN, futOiChange = Double.NaN;
        if (futures.size() == FUTURES_WINDOW_MIN + 1) {
            double[] then = futures.getFirst(), now = futures.getLast();
            futPriceChange = now[0] - then[0];
            futOiChange = now[1] - then[1];
        }

        // price and level
        Entry plan = null;
        boolean price;
        if (phase == Phase.EXPANSION) {
            plan = bounce(st, spot, atr);
            price = plan != null;
        } else {
            boolean up = phase == Phase.SQUEEZE;
            Map<String, Double> levels = up ? resistances(st) : supports(st);
            if (Double.isFinite(prevSpot) && Double.isFinite(spot) && !time.isBefore(LocalTime.of(9, 30))) {
                for (Map.Entry<String, Double> e : levels.entrySet()) {
                    double level = e.getValue();
                    boolean crossed = Double.isFinite(level) && (up ? prevSpot <= level && spot > level
                            : prevSpot >= level && spot < level);
                    if (crossed) {
                        breaks.putIfAbsent(e.getKey(), minute);    // the first break of the day counts
                    }
                }
            }
            double best = Double.NaN;                              // the nearest level broken recently, still beyond
            for (Map.Entry<String, Integer> b : breaks.entrySet()) {
                Double level = levels.get(b.getKey());
                if (level == null || minute - b.getValue() > breakWindowMin) {
                    continue;
                }
                boolean beyond = up ? spot > level : spot < level;
                if (beyond && (Double.isNaN(best) || Math.abs(spot - level) < Math.abs(spot - best))) {
                    best = level;
                }
            }
            price = Double.isFinite(best);
            if (price) {
                plan = new Entry(up ? OptionSide.CE : OptionSide.PE, best, Double.NaN, phase.name());
            }
        }
        prevSpot = spot;

        // VIX
        double vix = v.vix(), vix15 = v.vixChange15m(), vixDay = v.vixChangeDay();
        boolean vixOk = switch (phase) {
            case SQUEEZE -> vix < vixMax && vix15 > 0;
            case EXPANSION -> vix > 0 && Math.abs(vix15) <= vixFlatPct / 100.0 * vix;
            case AVALANCHE -> vix15 > 0 && vix - vixDay > 0 && vixDay / (vix - vixDay) * 100.0 >= vixSpikeDayPct;
        };

        // futures volume and OI
        boolean volume = f.rvolTod() >= minRvolTod;                  // NaN compares false
        boolean buildUp = switch (phase) {
            case SQUEEZE -> futPriceChange > 0 && futOiChange < 0;
            case EXPANSION -> futPriceChange > 0 && futOiChange > 0;
            case AVALANCHE -> futPriceChange < 0 && futOiChange > 0;
        };

        // ATM +-5 OI change differential (features v15)
        boolean coi = switch (phase) {
            case SQUEEZE -> o.atmBandCallShare3m() < 0 && o.atmBandPutShare3m() > 0;
            case EXPANSION -> o.atmBandShare3m() > 0 && o.atmBandShare5m() > 0 && o.atmBandPutShare3m() > 0;
            case AVALANCHE -> o.atmBandPutShare3m() < 0 && o.atmBandCallShare3m() > 0;
        };

        // FII context (features v13 cues: the previous session's participant-wise OI)
        double fii = s.cues() == null ? Double.NaN : s.cues().fiiIndexFutLongPctChange();
        boolean fiiOk = phase == Phase.AVALANCHE ? fii < 0 : fii > 0;

        c.put("price_level", price);
        c.put("vix", vixOk);
        c.put("futures_volume", volume);
        c.put("futures_oi", buildUp);
        c.put("atm_band_coi", coi);
        c.put("fii", fiiOk);
        if (!canEnter || taken || !(price && vixOk && volume && buildUp && coi && fiiOk)) {
            return null;
        }
        taken = true;
        return plan;
    }

    /** EXPANSION's price condition: a clean bounce off a support in the window; the plan stops below that support. */
    private Entry bounce(StructureFeatures st, double spot, double atr) {
        if (!(atr > 0) || !Double.isFinite(spot) || !(spot > close(1))) {
            return null;
        }
        double low = Double.POSITIVE_INFINITY;
        for (int back = 1; back <= bounceWindowMin; back++) {
            double v = close(back);
            if (Double.isFinite(v)) {
                low = Math.min(low, v);
            }
        }
        if (!Double.isFinite(low)) {
            return null;
        }
        for (double level : supports(st).values()) {
            boolean touched = Double.isFinite(level) && Math.abs(low - level) <= touchAtr * atr;
            if (touched && spot - level >= bounceAtr * atr) {
                return new Entry(OptionSide.CE, level - touchAtr * atr, Double.NaN, phase.name());
            }
        }
        return null;
    }
}
