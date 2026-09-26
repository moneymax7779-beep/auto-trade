package com.autotrade.features.snapshot;

/**
 * Gamma and theta of the tradable near-ATM options (features v6). Greeks are the feed's when it has
 * them, else Black–Scholes on the mid price; theta is per minute (per calendar day ÷ 1440).
 *
 * <ul>
 *   <li>{@code horizonMin}: the horizon of the breakeven and gamma P&amp;L values (minutes).</li>
 *   <li>{@code gammaPct}: ATM gamma × spot ÷ 100 = delta change for a 1% move (scale-free), average of
 *       the ATM call and put; {@code gammaRegime} LOW / NORMAL / HIGH / EXTREME by the features file's
 *       thresholds.</li>
 *   <li>{@code breakeven*}: spot move over the gamma horizon (5 min) at which ½·Γ·ΔS² pays the theta
 *       of that horizon: √(2·|Θ|·h / Γ), points.</li>
 *   <li>{@code gammaPnl*}: ½·Γ·ΔS² + Θ·h for the realised spot move over the horizon, rupees per unit:
 *       positive = long gamma has been paying.</li>
 *   <li>ATM and one-strike-in-the-money call and put: strike, delta, gamma, theta per minute, mid,
 *       spread percent.</li>
 * </ul>
 */
public record GammaFeatures(
        int horizonMin,
        double gammaPct,
        String gammaRegime,
        double breakevenCe,
        double breakevenPe,
        double gammaPnlCe,
        double gammaPnlPe,
        double atmCeStrike,
        double atmCeDelta,
        double atmCeGamma,
        double atmCeThetaPerMin,
        double atmCeMid,
        double atmCeSpreadPct,
        double itmCeStrike,
        double itmCeDelta,
        double itmCeGamma,
        double itmCeThetaPerMin,
        double itmCeMid,
        double itmCeSpreadPct,
        double atmPeStrike,
        double atmPeDelta,
        double atmPeGamma,
        double atmPeThetaPerMin,
        double atmPeMid,
        double atmPeSpreadPct,
        double itmPeStrike,
        double itmPeDelta,
        double itmPeGamma,
        double itmPeThetaPerMin,
        double itmPeMid,
        double itmPeSpreadPct) {

    public static final GammaFeatures EMPTY = new GammaFeatures(0, Double.NaN, "UNKNOWN", Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
}
