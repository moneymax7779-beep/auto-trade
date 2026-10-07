package com.autotrade.strategy.egb;

import java.time.LocalTime;

import com.autotrade.config.ThresholdConfig;

/** Typed view of expiry-swing-rider.vN.yaml. */
record ExpirySwingRiderConfig(
        String hash,
        String version,
        int dte,
        LocalTime entryFrom,
        LocalTime lastNewEntry,
        LocalTime flatBy,
        /** A swing reverses when a one-minute close is this % beyond the extreme of the current swing. */
        double reversalPct,
        double premiumBudget,
        int intendedLots,
        int strikeOffset,
        double premiumStopPct,
        /** How long after a reversal an entry may still be sent (the exit of the old side comes first). */
        int entryGraceMinutes,
        /** v3: no new entry for the rest of the day after this many losing trades in a row (0 / absent = off). */
        int stopAfterConsecutiveLosses) {

    static ExpirySwingRiderConfig from(ThresholdConfig c) {
        return new ExpirySwingRiderConfig(c.contentHash(), c.version(), c.getInt("scope.dte"),
                c.getTime("scope.entry_from"), c.getTime("scope.last_new_entry"), c.getTime("scope.flat_by"),
                c.getDouble("swing.reversal_pct"),
                c.getDouble("position.premium_budget"), c.getInt("position.intended_lots"), c.getInt("position.strike_offset"),
                c.getDouble("exits.premium_stop_pct"),
                c.has("position.entry_grace_minutes") ? c.getInt("position.entry_grace_minutes") : 2,
                c.has("risk.stop_after_consecutive_losses") ? c.getInt("risk.stop_after_consecutive_losses") : 0);
    }
}
