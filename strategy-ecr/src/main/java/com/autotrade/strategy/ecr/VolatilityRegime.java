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
            Result unknown = new Result("UNKNOWN", "NONE", Double.NaN);
            return ext.v6() == null ? unknown : floorForEvent(snapshot, unknown, ext.v6());
        }
        String regime = bucket(percentile, ext.volBuckets());
        EcrExtensions.V6 v6 = ext.v6();
        if (v6 == null) {
            if (v.realizedVolPercentile() >= ext.realizedEscalationPct()) {
                int index = ORDER.indexOf(regime);
                if (index >= 0 && index < ORDER.size() - 1) {
                    regime = ORDER.get(index + 1);
                    basis = basis + "+RV";
                }
            }
            return new Result(regime, basis, percentile);
        }
        // v6: the design's other VOL_REGIME inputs. Any one of them raises the regime one level (at most
        // one): realised vol or ATR extreme for the time of day, India VIX or ATM IV moving sharply,
        // futures RVOL in the design's expansion band, or the final hour before an expiry close.
        String shock = shock(snapshot, v, ext, v6);
        if (shock != null) {
            int index = ORDER.indexOf(regime);
            if (index < ORDER.size() - 1) {
                regime = ORDER.get(index + 1);
            }
            basis = basis + "+" + shock;
        }
        return floorForEvent(snapshot, new Result(regime, basis, percentile), v6);
    }

    /** The first escalation input that fires, or null. */
    private static String shock(FeatureSnapshot snapshot, VolatilityFeatures v, EcrExtensions ext, EcrExtensions.V6 v6) {
        if (v.realizedVolPercentile() >= ext.realizedEscalationPct()) {
            return "RV";
        }
        if (v.atrPercentile() >= v6.escalationAtrPercentile()) {
            return "ATR";
        }
        if (Math.abs(v.vixChange15m()) >= v6.escalationVixChange15m()) {
            return "VIX_MOVE";
        }
        if (Math.abs(snapshot.options().atmIvChange5m()) >= v6.escalationIvChange5m()) {
            return "IV_MOVE";
        }
        if (snapshot.futures().rvolTod() >= v6.escalationFuturesRvol()) {
            return "RVOL";
        }
        if (snapshot.regime().dteTradingDays() == 0 && snapshot.regime().minutesToExpiryClose() >= 0
                && snapshot.regime().minutesToExpiryClose() <= v6.escalationExpiryFinalMinutes()) {
            return "EXPIRY_FINAL_HOUR";
        }
        return null;
    }

    /** The design's "high-volatility event day": a scheduled event day is at least the floor regime. */
    private static Result floorForEvent(FeatureSnapshot snapshot, Result result, EcrExtensions.V6 v6) {
        if (snapshot.regime().marketEvent() == null) {
            return result;
        }
        int floor = ORDER.indexOf(v6.eventDayFloor());
        int current = ORDER.indexOf(result.regime());
        if (current < floor) {
            return new Result(v6.eventDayFloor(), result.basis() + "+EVENT", result.percentile());
        }
        return new Result(result.regime(), result.basis() + "+EVENT", result.percentile());
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
