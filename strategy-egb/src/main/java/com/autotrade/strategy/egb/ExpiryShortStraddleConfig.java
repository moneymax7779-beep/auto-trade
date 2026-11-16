package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.List;

import com.autotrade.config.ThresholdConfig;

/** Typed view of expiry-short-straddle.vN.yaml (A-358). */
record ExpiryShortStraddleConfig(
        String hash,
        String version,
        int dte,
        List<String> underlyings,
        LocalTime entryFrom,
        LocalTime entryUntil,
        int lots,
        int strikeOffset,
        double stopPct,
        boolean breakEvenExit,
        int refusedRetryMin) {

    @SuppressWarnings("unchecked")
    static ExpiryShortStraddleConfig from(ThresholdConfig c) {
        return new ExpiryShortStraddleConfig(c.contentHash(), c.version(), c.getInt("scope.dte"),
                List.copyOf((List<String>) c.get("scope.underlyings")), c.getTime("scope.entry_from"),
                c.getTime("scope.entry_until"), c.getInt("position.lots"), c.getInt("position.strike_offset"),
                c.getDouble("exits.stop_pct"), c.getBoolean("exits.break_even_exit"), c.getInt("retry.refused_retry_min"));
    }
}
