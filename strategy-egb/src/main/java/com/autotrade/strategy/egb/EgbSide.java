package com.autotrade.strategy.egb;

import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.GammaFeatures;
import com.autotrade.features.snapshot.RetestFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * The snapshot seen from one side: CE trades upward breaks of ORH/PDH, PE downward breaks of ORL/PDL,
 * and every favourable condition is phrased for that side.
 */
final class EgbSide {

    private final OptionSide side;
    private final FeatureSnapshot s;
    private final StructureFeatures st;
    private final GammaFeatures g;
    private final RetestFeatures rt;

    EgbSide(OptionSide side, FeatureSnapshot snapshot) {
        this.side = side;
        this.s = snapshot;
        this.st = snapshot.structure();
        this.g = snapshot.gamma();
        this.rt = snapshot.retest();
    }

    OptionSide side() {
        return side;
    }

    private boolean ce() {
        return side == OptionSide.CE;
    }

    private int sign() {
        return side.sign();
    }

    double spot() {
        return s.spot();
    }

    boolean orComplete() {
        return st.orComplete();
    }

    /** (spot − level) / ATR3m in this side's direction: positive = through the level. NaN for an unknown level. */
    double levelDistance(String level) {
        if (level == null) {
            return Double.NaN;
        }
        return switch (level) {
            case "ORH" -> st.distOrhAtr();
            case "PDH" -> st.distPdhAtr();
            case "ORL" -> -st.distOrlAtr();
            case "PDL" -> -st.distPdlAtr();
            default -> Double.NaN;
        };
    }

    double levelPrice(String level) {
        if (level == null) {
            return Double.NaN;
        }
        return switch (level) {
            case "ORH" -> st.orHigh();
            case "PDH" -> st.pdh();
            case "ORL" -> st.orLow();
            case "PDL" -> st.pdl();
            default -> Double.NaN;
        };
    }

    /** Consecutive 3-minute closes through the level in this side's direction. */
    int closesBeyond(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level) {
            case "ORH" -> st.orhClosesAbove();
            case "PDH" -> st.pdhClosesAbove();
            case "ORL" -> st.orlClosesBelow();
            case "PDL" -> st.pdlClosesBelow();
            default -> 0;
        };
    }

    String retestState(String level) {
        if (level == null) {
            return "NONE";
        }
        return switch (level) {
            case "ORH" -> rt.orhState();
            case "PDH" -> rt.pdhState();
            case "ORL" -> rt.orlState();
            case "PDL" -> rt.pdlState();
            default -> "NONE";
        };
    }

    double pullbackVolumeRatio(String level) {
        if (level == null) {
            return Double.NaN;
        }
        return switch (level) {
            case "ORH" -> rt.orhPullbackVolumeRatio();
            case "PDH" -> rt.pdhPullbackVolumeRatio();
            case "ORL" -> rt.orlPullbackVolumeRatio();
            case "PDL" -> rt.pdlPullbackVolumeRatio();
            default -> Double.NaN;
        };
    }

    /** The design's directional bias: VWAP side, EMA9 over EMA20, EMA9 sloping the trade's way, trend swings. */
    /** VWAP side, EMA9/EMA20 aligned and EMA9 sloping the trade's way, plus swing structure when required. */
    boolean structureBias(boolean requireTrendSwings) {
        return vwapSide() && emaAligned() && emaSlopeFavourable() && (!requireTrendSwings || trendSwings());
    }

    private boolean vwapSide() {
        return favourable(s.spot() - st.vwapSpotProxy());
    }

    private boolean emaAligned() {
        return favourable(st.ema9() - st.ema20());
    }

    boolean emaSlopeFavourable() {
        return favourable(st.ema9Slope());
    }

    /** Higher lows for CE, lower highs for PE. */
    boolean trendSwings() {
        return ce() ? st.higherLows() : st.lowerHighs();
    }

    boolean futuresMomentumFavourable() {
        return favourable(s.futures().momentum1m());
    }

    boolean accelerationFavourable() {
        return favourable(s.futures().acceleration1m());
    }

    /**
     * The design's "acceleration before the breakout", 0..1: futures momentum and acceleration, RVOL
     * slope rising, trend swings, EMA alignment with slope, VWAP side, weighted mover breadth at or above
     * {@code breadthMin}, and long gamma paying over the gamma horizon. Unknown components count as not met.
     */
    double accelerationIndex(double breadthMin) {
        double breadth = sign() * s.breadth().moverBreadth();
        double gammaPnl = ce() ? g.gammaPnlCe() : g.gammaPnlPe();
        boolean[] parts = {
                futuresMomentumFavourable(), accelerationFavourable(), s.futures().rvolSlope() > 0, trendSwings(),
                emaAligned() && emaSlopeFavourable(), vwapSide(), breadth >= breadthMin, gammaPnl > 0};
        int met = 0;
        for (boolean part : parts) {
            met += part ? 1 : 0;
        }
        return met / (double) parts.length;
    }

    boolean breakoutBar(double bodyMin, double closeTop, double wickMax) {
        double location = ce() ? st.lastBarCloseLocation() : 1 - st.lastBarCloseLocation();
        return st.lastBarBodyRatio() >= bodyMin && location >= 1 - closeTop && breakoutWick() <= wickMax;
    }

    /** The last 3-minute bar's wick against the trade (upper for CE, lower for PE), as a share of its range. */
    double breakoutWick() {
        return ce() ? st.lastBarUpperWickRatio() : st.lastBarLowerWickRatio();
    }

    double breakoutVolumeRatio() {
        return s.levels().breakoutBarVolumeRatio();
    }

    /** Room to the next ladder level ahead in ATR3m; +∞ when no level lies ahead. */
    double roomAtr() {
        double room = ce() ? s.levels().roomAbovePoints() : s.levels().roomBelowPoints();
        double atr = st.atr3m();
        if (Double.isNaN(room)) {
            return Double.POSITIVE_INFINITY;
        }
        return atr > 0 ? room / atr : Double.NaN;
    }

    double premiumResponse() {
        return ce() ? s.options().premiumResponseCe() : s.options().premiumResponsePe();
    }

    /** Raw futures price/OI state (FRESH_LONG, SHORT_COVERING, NEUTRAL, LONG_UNWIND, FRESH_SHORT). */
    String futuresState() {
        return s.futures().oiState();
    }

    /** Spot on the trade's side of EMA9 ("EMA9 catches price" after a retest). */
    boolean onTradeSideOfEma9() {
        return favourable(s.spot() - st.ema9());
    }

    /** The last 3-minute close is through EMA9 against the trade. */
    boolean closedThroughEma9() {
        double diff = st.lastBarClose() - st.ema9();
        return !Double.isNaN(diff) && sign() * diff < 0;
    }

    int vwapCrosses() {
        return s.compression().vwapCrosses();
    }

    /**
     * Runner trail on the underlying: the last confirmed 3-minute swing low (high for PE) once it has
     * formed beyond the traded level; broken by a 3-minute close through it.
     */
    boolean trailBroken(String level) {
        double swing = ce() ? st.lastSwingLow() : st.lastSwingHigh();
        double lvl = levelPrice(level);
        if (Double.isNaN(swing) || Double.isNaN(lvl) || sign() * (swing - lvl) <= 0) {
            return false;
        }
        return sign() * (st.lastBarClose() - swing) < 0;
    }

    /**
     * Directional breakeven for the contract bought (strike offset 0 = ATM, 1 = one ITM): the favourable
     * spot move x over {@code minutes} at which Δ·x + ½·Γ·x² pays |Θ|·t, i.e.
     * x = (√(Δ² + 2·Γ·|Θ|·t) − Δ) / Γ. NaN when the Greeks are unknown.
     */
    double directionalBreakeven(int strikeOffset, int minutes) {
        double delta = Math.abs(strikeOffset == 1 ? (ce() ? g.itmCeDelta() : g.itmPeDelta())
                : (ce() ? g.atmCeDelta() : g.atmPeDelta()));
        double gamma = strikeOffset == 1 ? (ce() ? g.itmCeGamma() : g.itmPeGamma())
                : (ce() ? g.atmCeGamma() : g.atmPeGamma());
        double theta = Math.abs(strikeOffset == 1 ? (ce() ? g.itmCeThetaPerMin() : g.itmPeThetaPerMin())
                : (ce() ? g.atmCeThetaPerMin() : g.atmPeThetaPerMin()));
        if (Double.isNaN(delta) || Double.isNaN(theta) || !(gamma > 0)) {
            return Double.NaN;
        }
        double decay = theta * minutes;
        return (Math.sqrt(delta * delta + 2 * gamma * decay) - delta) / gamma;
    }

    /**
     * ATM (0) or one strike ITM (1), whichever has |delta| closest to {@code targetDelta} with a spread
     * at most {@code maxSpreadPct}; −1 when neither qualifies.
     */
    int chooseStrikeOffset(double targetDelta, double maxSpreadPct) {
        double atmDelta = ce() ? g.atmCeDelta() : g.atmPeDelta();
        double atmSpread = ce() ? g.atmCeSpreadPct() : g.atmPeSpreadPct();
        double itmDelta = ce() ? g.itmCeDelta() : g.itmPeDelta();
        double itmSpread = ce() ? g.itmCeSpreadPct() : g.itmPeSpreadPct();
        boolean atmOk = !Double.isNaN(atmDelta) && atmSpread <= maxSpreadPct;
        boolean itmOk = !Double.isNaN(itmDelta) && itmSpread <= maxSpreadPct;
        if (atmOk && itmOk) {
            return Math.abs(Math.abs(itmDelta) - targetDelta) < Math.abs(Math.abs(atmDelta) - targetDelta) ? 1 : 0;
        }
        return atmOk ? 0 : itmOk ? 1 : -1;
    }

    private boolean favourable(double value) {
        return !Double.isNaN(value) && sign() * value > 0;
    }
}
