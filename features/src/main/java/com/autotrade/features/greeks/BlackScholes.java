package com.autotrade.features.greeks;

/**
 * Black–Scholes on the spot with no dividend yield, used only when the broker feed has no IV or
 * Greeks. Vega is per one volatility point (0.01) and theta per calendar day, matching the feed.
 */
public final class BlackScholes {

    public record Greeks(double price, double delta, double gamma, double vega, double theta) {
    }

    private static final double MIN_VOL = 0.005;
    private static final double MAX_VOL = 5.0;

    private BlackScholes() {
    }

    public static Greeks greeks(boolean call, double spot, double strike, double years, double rate, double vol) {
        double sqrtT = Math.sqrt(years);
        double d1 = (Math.log(spot / strike) + (rate + 0.5 * vol * vol) * years) / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;
        double discount = Math.exp(-rate * years);
        double pdf = Math.exp(-0.5 * d1 * d1) / Math.sqrt(2 * Math.PI);
        double price;
        double delta;
        double theta;
        if (call) {
            price = spot * cdf(d1) - strike * discount * cdf(d2);
            delta = cdf(d1);
            theta = -spot * pdf * vol / (2 * sqrtT) - rate * strike * discount * cdf(d2);
        } else {
            price = strike * discount * cdf(-d2) - spot * cdf(-d1);
            delta = cdf(d1) - 1;
            theta = -spot * pdf * vol / (2 * sqrtT) + rate * strike * discount * cdf(-d2);
        }
        double gamma = pdf / (spot * vol * sqrtT);
        double vega = spot * pdf * sqrtT / 100.0;
        return new Greeks(price, delta, gamma, vega, theta / 365.0);
    }

    public static double price(boolean call, double spot, double strike, double years, double rate, double vol) {
        return greeks(call, spot, strike, years, rate, vol).price();
    }

    /** Implied volatility by bisection; NaN when the price is outside the no-arbitrage bounds. */
    public static double impliedVol(boolean call, double premium, double spot, double strike, double years,
                                    double rate) {
        double intrinsic = call ? Math.max(0, spot - strike * Math.exp(-rate * years))
                : Math.max(0, strike * Math.exp(-rate * years) - spot);
        if (!(premium > intrinsic) || premium >= (call ? spot : strike)) {
            return Double.NaN;
        }
        double low = MIN_VOL;
        double high = MAX_VOL;
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (low + high);
            if (price(call, spot, strike, years, rate, mid) > premium) {
                high = mid;
            } else {
                low = mid;
            }
            if (high - low < 1e-7) {
                break;
            }
        }
        return 0.5 * (low + high);
    }

    /** Standard normal CDF (Abramowitz–Stegun 7.1.26 via erf; error below 1.5e-7). */
    static double cdf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x) / Math.sqrt(2));
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        double erf = 1 - poly * Math.exp(-x * x / 2);
        return x >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
    }
}
