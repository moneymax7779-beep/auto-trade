package com.autotrade.features.snapshot;

/**
 * Near-ATM option chain of the nearest expiry. OI changes are weighted sums over ATM ± N strikes
 * (heavier near ATM); IVs are fractions (0.12 = 12%).
 */
public record OptionsFeatures(
        String expiry,
        double strikeStep,
        double atmStrike,
        int contractsSeen,
        double ceOiChange1m,
        double ceOiChange3m,
        double ceOiChange5m,
        double ceOiChange10m,
        double peOiChange1m,
        double peOiChange3m,
        double peOiChange5m,
        double peOiChange10m,
        String ceOiFlow,
        String peOiFlow,
        double callBarrierStrike,
        double callBarrierScore,
        double putSupportStrike,
        double putSupportScore,
        boolean callWallWeakening,
        boolean putFloorWeakening,
        double straddle,
        double straddleChange5m,
        double atmCeIv,
        double atmPeIv,
        double atmIv,
        double atmIvChange1m,
        double atmIvChange3m,
        double atmIvChange5m,
        double ivSkew,
        String ivSource,
        double atmCeDelta,
        double atmCeGamma,
        double atmCeVega,
        double atmCeTheta,
        double atmPeDelta,
        double premiumResponseCe,
        double premiumResponsePe,
        double atmCeSpreadPct,
        double atmPeSpreadPct) {
}
