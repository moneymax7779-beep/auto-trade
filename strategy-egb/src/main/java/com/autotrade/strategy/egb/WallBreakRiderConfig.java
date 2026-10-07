package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.OptionSide;

/** Typed view of wall-break-rider.vN.yaml (ledger A-057). */
record WallBreakRiderConfig(
        String hash,
        String version,
        int dte,
        LocalTime entryFrom,
        LocalTime lastNewEntry,
        LocalTime flatBy,
        /** consecutive snapshots (minutes) the wall-weakening signal must hold before an entry */
        int confirmMinutes,
        Map<OptionSide, Set<String>> rejectFuturesStates,
        double premiumBudget,
        int intendedLots,
        int strikeOffset,
        double premiumStopPct,
        double trailActivationPct,
        double trailGivebackPct,
        int timeStopMin,
        double timeStopMinGainPct) {

    static WallBreakRiderConfig from(ThresholdConfig c) {
        return new WallBreakRiderConfig(c.contentHash(), c.version(), c.getInt("scope.dte"),
                c.getTime("scope.entry_from"), c.getTime("scope.last_new_entry"), c.getTime("scope.flat_by"),
                c.getInt("signal.confirm_minutes"),
                Map.of(OptionSide.CE, Set.copyOf(strings(c, "signal.reject_futures_states.CE")),
                        OptionSide.PE, Set.copyOf(strings(c, "signal.reject_futures_states.PE"))),
                c.getDouble("position.premium_budget"), c.getInt("position.intended_lots"), c.getInt("position.strike_offset"),
                c.getDouble("exits.premium_stop_pct"), c.getDouble("exits.trail_activation_pct"),
                c.getDouble("exits.trail_giveback_pct"), c.getInt("exits.time_stop_min"),
                c.getDouble("exits.time_stop_min_gain_pct"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(ThresholdConfig c, String path) {
        return List.copyOf((List<String>) c.get(path));
    }
}
