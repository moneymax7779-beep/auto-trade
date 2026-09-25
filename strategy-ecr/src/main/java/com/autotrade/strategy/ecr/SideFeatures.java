package com.autotrade.strategy.ecr;

import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.FuturesFeatures;
import com.autotrade.features.snapshot.OptionsFeatures;
import com.autotrade.features.snapshot.StructureFeatures;
import com.autotrade.strategy.OptionSide;

/**
 * The snapshot seen from one side, so CE and PE share one rule set: "beyond" means above the ORH
 * for CE and below the ORL for PE; every favourable condition is phrased for that side.
 */
final class SideFeatures {

    private final OptionSide side;
    private final FeatureSnapshot s;
    private final StructureFeatures st;
    private final FuturesFeatures fu;
    private final OptionsFeatures op;

    SideFeatures(OptionSide side, FeatureSnapshot snapshot) {
        this.side = side;
        this.s = snapshot;
        this.st = snapshot.structure();
        this.fu = snapshot.futures();
        this.op = snapshot.options();
    }

    private boolean ce() {
        return side == OptionSide.CE;
    }

    /** (spot − level) / ATR for CE at the ORH, (level − spot) / ATR for PE at the ORL: positive = through the level. */
    double levelDistance() {
        return ce() ? st.distOrhAtr() : -st.distOrlAtr();
    }

    int closesBeyond() {
        return ce() ? st.orhClosesAbove() : st.orlClosesBelow();
    }

    boolean retestHeld() {
        return ce() ? st.orhRetestHeld() : st.orlRetestHeld();
    }

    boolean vwapOk() {
        return favourable(s.spot() - st.vwapSpotProxy());
    }

    boolean emaAligned() {
        return favourable(st.ema9() - st.ema20());
    }

    boolean emaSlopeOk() {
        return favourable(st.ema9Slope());
    }

    /** The last opposite swing (low for CE, high for PE) has not been taken out; true before one exists. */
    boolean swingIntact() {
        double swing = ce() ? st.lastSwingLow() : st.lastSwingHigh();
        return Double.isNaN(swing) || favourable(s.spot() - swing);
    }

    /** Higher lows for CE, lower highs for PE. */
    boolean trendSwings() {
        return ce() ? st.higherLows() : st.lowerHighs();
    }

    boolean futuresMomentum() {
        return favourable(fu.momentum1m());
    }

    boolean futuresAcceleration() {
        return favourable(fu.acceleration1m());
    }

    double momentumNormalised() {
        return side.sign() * fu.momentum1mNorm();
    }

    boolean basisSupportive() {
        return !Double.isNaN(fu.basisChange3m()) && side.sign() * fu.basisChange3m() >= 0;
    }

    boolean rvolRising() {
        return fu.rvolSlope() > 0;
    }

    double rvol() {
        return fu.rvolTod();
    }

    /** Weighted breadth in this side's favour: +100 = every constituent moving this way. */
    double breadth() {
        return side.sign() * s.breadth().momentumBreadth();
    }

    /** Futures price/OI state from this side's view (FRESH_SHORT is FRESH_LONG for PE). */
    String futuresState() {
        String state = fu.oiState();
        if (ce() || state == null) {
            return state;
        }
        return switch (state) {
            case "FRESH_LONG" -> "FRESH_SHORT";
            case "FRESH_SHORT" -> "FRESH_LONG";
            case "SHORT_COVERING" -> "LONG_UNWIND";
            case "LONG_UNWIND" -> "SHORT_COVERING";
            default -> state;
        };
    }

    /** Raw futures state, for the runner exit lists that name states explicitly per side. */
    String rawFuturesState() {
        return fu.oiState();
    }

    /** Writers against this side are covering (CE: call OI unwinding; PE: put OI unwinding). */
    double againstWritersFlow() {
        String flow = ce() ? op.ceOiFlow() : op.peOiFlow();
        return flowScore(flow, false);
    }

    /** Writers on this side's floor are adding (CE: put OI building; PE: call OI building). */
    double supportWritersFlow() {
        String flow = ce() ? op.peOiFlow() : op.ceOiFlow();
        return flowScore(flow, true);
    }

    boolean wallWeakening() {
        return ce() ? op.callWallWeakening() : op.putFloorWeakening();
    }

    double premiumResponse() {
        return ce() ? op.premiumResponseCe() : op.premiumResponsePe();
    }

    double spreadPct() {
        return ce() ? op.atmCeSpreadPct() : op.atmPeSpreadPct();
    }

    double ivChange3m() {
        return op.atmIvChange3m();
    }

    /** The breakout bar closes strongly in this side's direction. */
    boolean breakoutBar(EcrConfig config) {
        double location = ce() ? st.lastBarCloseLocation() : 1 - st.lastBarCloseLocation();
        double wick = ce() ? st.lastBarUpperWickRatio() : st.lastBarLowerWickRatio();
        return st.lastBarBodyRatio() >= config.bodyRatioMin()
                && location >= 1 - config.closeLocationTop()
                && wick <= config.wickMax()
                && st.lastBarRangeVsAvg() >= config.rangeVsAvgMin();
    }

    /** The last 3-minute close is on this side of EMA9 (the runner trail). */
    boolean trailHolds() {
        return favourable(st.lastBarClose() - st.ema9());
    }

    double spot() {
        return s.spot();
    }

    private boolean favourable(double value) {
        return !Double.isNaN(value) && side.sign() * value > 0;
    }

    /** 1 = favourable, 0 = against, 0.5 = flat/fading/unknown. */
    private static double flowScore(String flow, boolean buildIsGood) {
        if (flow == null) {
            return 0.5;
        }
        return switch (flow) {
            case "ACCELERATING_BUILD", "BUILD" -> buildIsGood ? 1.0 : 0.0;
            case "ACCELERATING_UNWIND", "UNWIND" -> buildIsGood ? 0.0 : 1.0;
            default -> 0.5;
        };
    }
}
