package com.autotrade.strategy.ecr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sub-scores (0..1) and the three weighted scores for one side. Missing inputs score 0.5 (neutral)
 * rather than blocking, and every sub-score is kept so reports can show what drove a score.
 */
final class Scorer {

    private final EcrConfig config;

    Scorer(EcrConfig config) {
        this.config = config;
    }

    Map<String, Double> subScores(SideFeatures f) {
        EcrExtensions ext = config.ext();
        Map<String, Double> sub = new LinkedHashMap<>();
        if (ext == null) {
            sub.put("structure", mean(f.vwapOk(), f.emaAligned(), f.emaSlopeOk(), f.levelDistance() > 0,
                    f.closesBeyond() >= 1, f.swingIntact()));
        } else {
            // v4: the ladder counts ("if price progressively reclaims these levels").
            sub.put("structure", mean(f.vwapOk(), f.emaAligned(), f.emaSlopeOk(), f.levelDistance() > 0,
                    f.closesBeyond() >= 1, f.swingIntact(), f.ladderNet() > 0));
        }
        sub.put("futures", mean(stateScore(f), bool(f.futuresMomentum()), bool(f.futuresAcceleration()),
                bool(f.basisSupportive())));
        sub.put("momentum", mean(bool(f.futuresMomentum()), bool(f.futuresAcceleration()),
                clamp01(0.5 + 0.5 * orZero(f.momentumNormalised()))));
        double volume = mean(Double.isNaN(f.rvol()) ? 0.5 : Math.min(1, f.rvol() / config.volumeFullAtRvol()),
                bool(f.rvolRising()));
        sub.put("volume", volume);
        sub.put("oi", mean(f.againstWritersFlow(), f.supportWritersFlow(), f.wallWeakening() ? 1.0 : 0.5));
        sub.put("breadth", Double.isNaN(f.breadth()) ? 0.5 : clamp01((f.breadth() + 100) / 200));
        sub.put("premium", premium(f.premiumResponse()));
        double spread = Double.isNaN(f.spreadPct()) ? 0.5 : f.spreadPct() <= config.maxSpreadPct() ? 1.0 : 0.0;
        if (ext == null) {
            sub.put("iv_options", mean(premium(f.premiumResponse()),
                    Double.isNaN(f.ivChange3m()) ? 0.5 : f.ivChange3m() >= 0 ? 1.0 : 0.5, spread));
            sub.put("structure_runner", mean(f.closesBeyond() >= config.closesAboveMin(),
                    f.retestHeld() || !config.retestRequired(), f.trailHolds() && f.emaAligned(), f.swingIntact(),
                    f.trendSwings()));
        } else {
            // v4: the option itself confirms (premium higher highs, option volume, IV not collapsing,
            // straddle expansion) and acceptance includes travel, volume after the break and room.
            double ivTrend = switch (f.ivTrend()) {
                case "RISING", "FLAT" -> 1.0;
                case "FALLING" -> 0.0;
                default -> 0.5;
            };
            sub.put("iv_options", mean(premium(f.premiumResponse()), ivTrend, spread,
                    bool(f.premiumRising() || f.premiumNewHigh()), bool(f.optionVolumeExpanding()),
                    f.straddleCompressionEnded() ? 1.0 : 0.5));
            double volumeAfter = f.volumeAfterBreak();
            sub.put("structure_runner", mean(bool(f.closesBeyond() >= config.closesAboveMin()),
                    bool(f.retestHeld() || !config.retestRequired()), bool(f.trailHolds() && f.emaAligned()),
                    bool(f.swingIntact()), bool(f.trendSwings()),
                    bool(f.travelAtr() >= ext.minTravelAtr()),
                    Double.isNaN(volumeAfter) ? 0.5 : bool(volumeAfter >= ext.minVolumeAfterBreak()),
                    Double.isNaN(f.expectedReach()) ? 0.5 : bool(f.expectedReach() >= 1)));
        }
        return sub;
    }

    /** Regime-weighted confirmation score; regime weight names map onto the sub-scores above. */
    double confirmScore(Map<String, Double> sub, String regime) {
        Map<String, Double> weights = config.regimeWeights().getOrDefault(regime,
                config.regimeWeights().get("NORMAL"));
        double total = 0;
        double weightSum = 0;
        for (Map.Entry<String, Double> weight : weights.entrySet()) {
            String component = switch (weight.getKey()) {
                case "fut_volume" -> "volume";
                case "iv_greeks" -> "iv_options";
                default -> weight.getKey();
            };
            Double value = sub.get(component);
            if (value != null) {
                total += weight.getValue() * value;
                weightSum += weight.getValue();
            }
        }
        return weightSum > 0 ? 100 * total / weightSum : Double.NaN;
    }

    double runnerScore(Map<String, Double> sub) {
        double total = 0;
        double weightSum = 0;
        for (Map.Entry<String, Double> weight : config.runnerWeights().entrySet()) {
            String component = switch (weight.getKey()) {
                case "structure" -> "structure_runner";
                case "option_oi" -> "oi";
                default -> weight.getKey();
            };
            Double value = sub.get(component);
            if (value != null) {
                total += weight.getValue() * value;
                weightSum += weight.getValue();
            }
        }
        return weightSum > 0 ? 100 * total / weightSum : Double.NaN;
    }

    /** Futures price/OI state for this side, 0..1. v4 maps the design's +2..−2 scale directly. */
    private double stateScore(SideFeatures f) {
        if (config.ext() != null) {
            Double points = config.ext().futuresStateScore().get(f.futuresState());
            return points == null ? 0.5 : (points + 2) / 4;
        }
        Double value = config.futuresStateMap().get(f.futuresState());
        return value == null ? 0.5 : value;
    }

    private double premium(double ratio) {
        if (Double.isNaN(ratio)) {
            return 0.5;
        }
        if (ratio >= config.premiumResponseGood()) {
            return 1.0;
        }
        return ratio >= config.premiumResponsePoor() ? 0.6 : 0.2;
    }

    private static double mean(boolean... values) {
        double sum = 0;
        for (boolean value : values) {
            sum += value ? 1 : 0;
        }
        return sum / values.length;
    }

    private static double mean(double... values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double bool(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double orZero(double value) {
        return Double.isNaN(value) ? 0 : value;
    }
}
