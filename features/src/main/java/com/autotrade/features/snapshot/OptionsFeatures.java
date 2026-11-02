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
        double atmPeSpreadPct,
        /**
         * OI change at the call barrier (put support) strike over the longest wall-weakening lookback (10 min), percent;
         * NaN without history. Unlike callWallWeakening it does not need OI to fall every minute, so it also works on
         * BSE, whose option OI updates every 2-3 minutes (A-057/A-058).
         */
        double callWallOiChangePct,
        double putFloorOiChangePct,
        /**
         * v12 (A-090): robust z-score of the 5-minute near-ATM call (put) OI change, as a share of near-ATM OI, against
         * the stored bars' baseline for the same days-to-expiry and 15-minute bucket; NaN when off or without baseline.
         */
        double ceOiChange5mZ,
        double peOiChange5mZ,
        /**
         * A-157 / A-158: (put OI change − call OI change) over the ATM ± 2 strikes in the last 1 / 3 / 5 minutes, as a share
         * of those strikes' OI at the window start (unweighted); positive = puts written faster (up). NaN when a contract
         * lacks either value.
         */
        double atmBandShare1m,
        double atmBandShare3m,
        double atmBandShare5m,
        /** A-161: the two sides of atmBandShare3m: Δ call OI and Δ put OI over 3 minutes, each / the band's OI then. */
        double atmBandCallShare3m,
        double atmBandPutShare3m,
        /** A-257: atmBandShare over the last 2 minutes (same band and definition). */
        double atmBandShare2m,
        /**
         * A-272: the OI change of the calls / of the puts over the ATM ± 5 strikes in the 2 minutes ending now, in contracts
         * (not a share); strikes without an OI at both ends are left out; NaN when none has both.
         */
        double atmBand5CallChange2m,
        double atmBand5PutChange2m,
        /** A-275: the same over the last 5 minutes (BSE publishes OI every 2½-3 minutes: a 2-minute window often has none). */
        double atmBand5CallChange5m,
        double atmBand5PutChange5m,
        /**
         * A-283: the synthetic spot of this expiry, strike + call mid - put mid at the strike nearest the previous value
         * (first: nearest ATM) with both sides quoted (bid and ask > 0) in the last 60 s; NaN when none. The index feed
         * freezes 15:15-15:28 on the exchange's closing session, the options do not.
         */
        double syntheticSpot,
        /**
         * A-301: the standing OI walls near spot: the put OI of the strikes from spot down to ATM - 5 strikes, divided by
         * that plus the call OI of the strikes from spot up to ATM + 5 (latest OI); above 0.5 the put wall below outweighs
         * the call wall above. NaN when either side has no OI.
         */
        double wallPutShare) {
}
