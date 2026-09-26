package com.autotrade.strategy.ecr;

import java.util.LinkedHashMap;
import java.util.Map;

import com.autotrade.features.snapshot.CasFeatures;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.strategy.OptionSide;

/**
 * The design's CAS CE score (PE mirrored): IEP direction 15, IEP velocity 10, weighted buy imbalance
 * 15, CAS breadth 15, low concentration 5, futures direction 10, futures acceleration 10,
 * futures/CAS alignment 10, option response 5, IV response 5. Each component is 0..1 for the side.
 *
 * <p>IEP direction uses the index-weighted constituent IEP return when per-stock auction data covers
 * enough of the index, else the indicative index against its reference (the indicative index is the
 * index computed from the constituents' IEPs). Imbalance, breadth and concentration exist only with
 * per-stock data; without it they are left out and the score renormalises over the rest.
 */
final class CasScorer {

    record Score(double value, Map<String, Double> components, boolean constituentData, double futuresAlignment) {
    }

    private final EcrExtensions.Cas config;

    CasScorer(EcrExtensions.Cas config) {
        this.config = config;
    }

    Score score(OptionSide side, FeatureSnapshot snapshot) {
        CasFeatures cas = snapshot.cas();
        int sign = side.sign();
        double atr = snapshot.structure().atr1m();
        double moveScale = atr > 0 ? config.moveScaleAtr() * atr : Double.NaN;
        boolean constituents = cas.constituentCoveragePct() >= config.minConstituentCoveragePct()
                && !Double.isNaN(cas.weightedIepReturnPct());
        Map<String, Double> c = new LinkedHashMap<>();
        // v5: a frozen indicative index (as recorded 15:15-~15:29) carries no auction information; its
        // components are then left out instead of scoring a false "no move".
        boolean indicativeLive = !config.indexFallbackNeedsMovingIndicative()
                || (!Double.isNaN(cas.indicativeChangeLong()) && cas.indicativeChangeLong() != 0);
        double iepReturn = constituents ? cas.weightedIepReturnPct()
                : indicativeLive ? cas.indicativeReturnPct() : Double.NaN;
        c.put("iep_direction", squash(sign * iepReturn, config.iepReturnScalePct()));
        c.put("iep_velocity", indicativeLive ? mean(squash(sign * cas.indicativeChangeMid(), moveScale),
                squash(sign * cas.indicativeAcceleration(), moveScale)) : Double.NaN);
        if (constituents) {
            c.put("weighted_buy_imbalance", mean(clamp01(0.5 + 0.5 * sign * cas.weightedImbalance()),
                    squash(sign * cas.weightedImbalanceChange(), config.imbalanceChangeScale())));
            double breadth = cas.casBreadthPct() / 100;
            c.put("cas_breadth", Double.isNaN(breadth) ? Double.NaN : side == OptionSide.CE ? breadth : 1 - breadth);
            c.put("low_concentration", Double.isNaN(cas.topConcentration()) ? Double.NaN
                    : clamp01(1 - cas.topConcentration()));
        }
        c.put("fut_direction", squash(sign * cas.futuresChange1m(), moveScale));
        c.put("fut_acceleration", squash(sign * snapshot.futures().acceleration1m(), moveScale));
        double alignment = indicativeLive ? alignment(sign, cas) : Double.NaN;
        c.put("fut_cas_alignment", alignment);
        double premiumChange = side == OptionSide.CE ? snapshot.premium().ceChange1mPct()
                : snapshot.premium().peChange1mPct();
        c.put("option_response", Double.isNaN(premiumChange) ? Double.NaN : premiumChange > 0 ? 1.0 : 0.0);
        String trend = snapshot.volatility().ivTrend() == null ? "UNKNOWN" : snapshot.volatility().ivTrend();
        c.put("iv_response", switch (trend) {
            case "RISING" -> 1.0;
            case "FLAT" -> 0.6;
            case "FALLING" -> 0.2;
            default -> Double.NaN;
        });
        double total = 0;
        double weights = 0;
        for (Map.Entry<String, Double> component : c.entrySet()) {
            Double weight = config.weights().get(component.getKey());
            if (weight != null && !Double.isNaN(component.getValue())) {
                total += weight * component.getValue();
                weights += weight;
            }
        }
        return new Score(weights > 0 ? 100 * total / weights : Double.NaN, c, constituents, alignment);
    }

    /**
     * Futures agreeing with the auction: 0 when the indicative move is against the side, else how much
     * of the indicative one-minute move futures matched (1 = fully, 0 = flat or opposite).
     */
    static double alignment(int sign, CasFeatures cas) {
        double indicative = cas.indicativeChangeMid();
        if (Double.isNaN(indicative) || Double.isNaN(cas.futuresFollowRatio())) {
            return Double.NaN;
        }
        if (sign * indicative <= 0) {
            return 0;
        }
        return clamp01(cas.futuresFollowRatio());
    }

    /** 0.5 + 0.5 × tanh(x / scale): 1 strongly favourable, 0 strongly against, NaN when unknown. */
    private static double squash(double x, double scale) {
        return Double.isNaN(x) || !(scale > 0) ? Double.NaN : 0.5 + 0.5 * Math.tanh(x / scale);
    }

    private static double mean(double a, double b) {
        if (Double.isNaN(a)) {
            return b;
        }
        return Double.isNaN(b) ? a : (a + b) / 2;
    }

    private static double clamp01(double value) {
        return Double.isNaN(value) ? Double.NaN : Math.max(0, Math.min(1, value));
    }
}
