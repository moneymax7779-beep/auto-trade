package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.OptionSide;

/** Typed view of opening-drive.vN.yaml. */
record OpeningDriveConfig(
        String hash,
        String version,
        LocalTime triggerFrom,
        LocalTime triggerUntil,
        LocalTime timeExit,
        Map<OptionSide, List<String>> levels,
        int minPreviousSessionMinutes,
        int maxEntryAttempts,
        int strikeOffset,
        double premiumBudget,
        double expiryDayPremiumBudget,
        double premiumStopPct,
        boolean structureStop,
        double trailActivatePct,
        double trailGivebackPct) {

    static OpeningDriveConfig from(ThresholdConfig c) {
        return new OpeningDriveConfig(c.contentHash(), c.version(), c.getTime("scope.trigger_from"),
                c.getTime("scope.trigger_until"), c.getTime("scope.time_exit"),
                Map.of(OptionSide.CE, strings(c, "scope.levels.CE"), OptionSide.PE, strings(c, "scope.levels.PE")),
                c.getInt("scope.min_previous_session_minutes"), c.getInt("scope.max_entry_attempts"),
                c.getInt("position.strike_offset"), c.getDouble("position.premium_budget"),
                c.has("position.expiry_day_premium_budget") ? c.getDouble("position.expiry_day_premium_budget") : Double.NaN,
                c.getDouble("exits.premium_stop_pct"), c.getBoolean("exits.structure_stop"),
                c.getDouble("exits.trail_activate_pct"), c.getDouble("exits.trail_giveback_pct"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(ThresholdConfig c, String path) {
        return List.copyOf((List<String>) c.get(path));
    }
}
