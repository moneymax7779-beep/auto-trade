package com.autotrade.strategy.ecr;

import java.util.List;

import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.VolatilityFeatures;

/**
 * The design's volatility regime engine: LOW / NORMAL / HIGH / EXTREME from a volatility percentile,
 * using the design's buckets (below 20, 20–70, 70–90, above 90).
 *
 * <p>The primary input is per underlying: India VIX is NIFTY-derived, so it leads for NIFTY while ATM
 * IV leads for SENSEX and BANKNIFTY (the design: "I would not treat VIX as the main volatility measure
 * for BANKNIFTY or SENSEX"). VIX uses the 252-day percentile, else the 60-day one; the other input is
 * the fallback. When realised volatility is itself extreme for the time of day (percentile at or above
 * {@code realized_escalation_pct}) the regime is raised one level, since markets moving more than
 * options price is the "Realized vol HIGH" case. UNKNOWN when no input is available.
 */
final class VolatilityRegime {

    static final List<String> ORDER = List.of("LOW", "NORMAL", "HIGH", "EXTREME");

    record Result(String regime, String basis, double percentile) {
    }

    private VolatilityRegime() {
    }

    static Result classify(FeatureSnapshot snapshot, EcrExtensions ext) {
        VolatilityFeatures v = snapshot.volatility();
        double vix = Double.isNaN(v.vixPercentile252d()) ? v.vixPercentile60d() : v.vixPercentile252d();
        String vixBasis = Double.isNaN(v.vixPercentile252d()) ? "VIX_60D" : "VIX_252D";
        boolean vixFirst = "VIX".equals(ext.volPrimary().getOrDefault(snapshot.underlying(), "IV"));
        double percentile;
        String basis;
        if (vixFirst && !Double.isNaN(vix)) {
            percentile = vix;
            basis = vixBasis;
        } else if (!Double.isNaN(v.atmIvPercentile())) {
            percentile = v.atmIvPercentile();
            basis = "IV_PCT";
        } else if (!Double.isNaN(vix)) {
            percentile = vix;
            basis = vixBasis;
        } else {
            return new Result("UNKNOWN", "NONE", Double.NaN);
        }
        String regime = bucket(percentile, ext.volBuckets());
        if (v.realizedVolPercentile() >= ext.realizedEscalationPct()) {
            int index = ORDER.indexOf(regime);
            if (index >= 0 && index < ORDER.size() - 1) {
                regime = ORDER.get(index + 1);
                basis = basis + "+RV";
            }
        }
        return new Result(regime, basis, percentile);
    }

    static String bucket(double percentile, List<EcrExtensions.Bucket> buckets) {
        for (EcrExtensions.Bucket bucket : buckets) {
            if (percentile >= bucket.from() && percentile < bucket.to()) {
                return bucket.name();
            }
        }
        return buckets.getLast().name();
    }
}
