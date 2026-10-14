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
        double trailGivebackPct,
        int maxTrades,
        /** v4 (A-072): minutes after a losing trade closes before a new entry (0 = no cool-down, v1-v3). */
        int reentryCooldownAfterLossMin,
        /** v5 (A-097): a level counts only if the day opened at least this % of it away (0 = any distance, v1-v4). */
        double minOpenDistancePct,
        /** v6 (A-098): an entry needs mover breadth at least this far its way (+ calls, - puts; NaN = off, v1-v5). */
        double breadthMinAgree) {

    /** v1-v3 shape: no cool-down after a loss. */
    OpeningDriveConfig(String hash, String version, LocalTime triggerFrom, LocalTime triggerUntil, LocalTime timeExit,
                       Map<OptionSide, List<String>> levels, int minPreviousSessionMinutes, int maxEntryAttempts,
                       int strikeOffset, double premiumBudget, double expiryDayPremiumBudget, double premiumStopPct,
                       boolean structureStop, double trailActivatePct, double trailGivebackPct, int maxTrades) {
        this(hash, version, triggerFrom, triggerUntil, timeExit, levels, minPreviousSessionMinutes, maxEntryAttempts,
                strikeOffset, premiumBudget, expiryDayPremiumBudget, premiumStopPct, structureStop, trailActivatePct,
                trailGivebackPct, maxTrades, 0, 0, Double.NaN);
    }

    static OpeningDriveConfig from(ThresholdConfig c) {
        return new OpeningDriveConfig(c.contentHash(), c.version(), c.getTime("scope.trigger_from"),
                c.getTime("scope.trigger_until"), c.getTime("scope.time_exit"),
                Map.of(OptionSide.CE, strings(c, "scope.levels.CE"), OptionSide.PE, strings(c, "scope.levels.PE")),
                c.getInt("scope.min_previous_session_minutes"), c.getInt("scope.max_entry_attempts"),
                c.getInt("position.strike_offset"), c.getDouble("position.premium_budget"),
                c.has("position.expiry_day_premium_budget") ? c.getDouble("position.expiry_day_premium_budget") : Double.NaN,
                c.getDouble("exits.premium_stop_pct"), c.getBoolean("exits.structure_stop"),
                c.getDouble("exits.trail_activate_pct"), c.getDouble("exits.trail_giveback_pct"),
                c.has("scope.max_trades") ? c.getInt("scope.max_trades") : 1,
                c.has("scope.reentry_cooldown_after_loss_min") ? c.getInt("scope.reentry_cooldown_after_loss_min") : 0,
                c.has("scope.min_open_distance_pct") ? c.getDouble("scope.min_open_distance_pct") : 0,
                c.has("filters.breadth_min_agree") ? c.getDouble("filters.breadth_min_agree") : Double.NaN);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(ThresholdConfig c, String path) {
        return List.copyOf((List<String>) c.get(path));
    }
}
