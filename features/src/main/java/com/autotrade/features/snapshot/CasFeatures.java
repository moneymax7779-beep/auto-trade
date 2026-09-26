package com.autotrade.features.snapshot;

/**
 * Closing-auction session (from 15:15). Index level: the indicative index and its return against the
 * reference (mean spot over 15:00–15:15, the index counterpart of the stocks' 15:00–15:15 VWAP
 * reference), its velocity over the configured windows and acceleration, the futures-minus-indicative
 * basis and how far futures follow the indicative move, and whether the indicative value crosses the
 * opening range (a CAS cross is not a breakout).
 *
 * <p>Constituent level (needs per-stock auction data, which only the Upstox feed carries): index-weighted
 * IEP return against each stock's reference price, weighted normalised imbalance (−1 sell … +1 buy,
 * imbalance / (2 × equilibrium quantity + |imbalance|)) and its change, CAS breadth (weight share
 * whose IEP is above reference), top-N concentration of the dominant side, equilibrium turnover
 * (₹ crore) and the weight coverage of stocks with auction data. NaN without that data.
 */
public record CasFeatures(
        String feedPhase,
        double indicativeIndex,
        double referenceIndex,
        double indicativeReturnPct,
        double indicativeVsLastContinuous,
        double indicativeChangeShort,
        double indicativeChangeMid,
        double indicativeChangeLong,
        double indicativeAcceleration,
        double futuresVsIndicative,
        double casBasisChange1m,
        double futuresChange1m,
        double futuresFollowRatio,
        boolean orhCross,
        boolean orlCross,
        double weightedIepReturnPct,
        double weightedImbalance,
        double weightedImbalanceChange,
        double casBreadthPct,
        double topConcentration,
        double auctionTurnoverCr,
        double constituentCoveragePct,
        int auctionStocks) {

    /** Only the v2 fields (indicative index, its gap to the last continuous spot, futures basis). */
    public static CasFeatures basic(String feedPhase, double indicative, double vsLastContinuous, double futuresBasis) {
        double n = Double.NaN;
        return new CasFeatures(feedPhase, indicative, n, n, vsLastContinuous, n, n, n, n, futuresBasis, n, n, n,
                false, false, n, n, n, n, n, n, n, 0);
    }
}
