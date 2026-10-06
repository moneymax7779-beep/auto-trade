package com.autotrade.strategy.egb;

import java.time.LocalTime;

import com.autotrade.config.ThresholdConfig;

/** Typed view of trend-pullback-scalper.vN.yaml. */
record TrendPullbackScalperConfig(
        String hash,
        String version,
        LocalTime entryFrom,
        LocalTime lastNewEntry,
        LocalTime flatBy,
        int timeStopMin,
        int emaPeriod,
        int emaLookbackMin,
        int extremeWithinMin,
        double pullbackMinAtr,
        double pullbackMaxAtr,
        double turnAtr,
        double targetPctExpiry,
        double targetPct,
        double premiumStopPctExpiry,
        double premiumStopPct,
        double premiumBudget,
        int intendedLots,
        int strikeOffset) {

    static TrendPullbackScalperConfig from(ThresholdConfig c) {
        return new TrendPullbackScalperConfig(c.contentHash(), c.version(),
                c.getTime("scope.entry_from"), c.getTime("scope.last_new_entry"), c.getTime("scope.flat_by"),
                c.getInt("scope.time_stop_min"),
                c.getInt("trend.ema_period"), c.getInt("trend.ema_lookback_min"), c.getInt("trend.extreme_within_min"),
                c.getDouble("pullback.min_atr"), c.getDouble("pullback.max_atr"), c.getDouble("pullback.turn_atr"),
                c.getDouble("exits.target_pct_expiry"), c.getDouble("exits.target_pct"),
                c.getDouble("exits.premium_stop_pct_expiry"), c.getDouble("exits.premium_stop_pct"),
                c.getDouble("position.premium_budget"), c.getInt("position.intended_lots"),
                c.getInt("position.strike_offset"));
    }
}
