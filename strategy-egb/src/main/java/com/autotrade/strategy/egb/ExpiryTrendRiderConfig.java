package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.OptionSide;

/** Typed view of expiry-trend-rider.vN.yaml. */
record ExpiryTrendRiderConfig(
        String hash,
        String version,
        int dte,
        LocalTime entryFrom,
        LocalTime lastNewEntry,
        LocalTime flatBy,
        double breadthMin,
        Map<OptionSide, Set<String>> rejectFuturesStates,
        double ivChange3mMin,
        double premiumBudget,
        int intendedLots,
        int maxAdds,
        int strikeOffset,
        double premiumStopPct) {

    static ExpiryTrendRiderConfig from(ThresholdConfig c) {
        return new ExpiryTrendRiderConfig(c.contentHash(), c.version(), c.getInt("scope.dte"),
                c.getTime("scope.entry_from"), c.getTime("scope.last_new_entry"), c.getTime("scope.flat_by"),
                c.getDouble("trend.breadth_min"),
                Map.of(OptionSide.PE, Set.copyOf(strings(c, "trend.reject_futures_states.PE")),
                        OptionSide.CE, Set.copyOf(strings(c, "trend.reject_futures_states.CE"))),
                c.getDouble("trend.iv_change_3m_min"),
                c.getDouble("position.premium_budget"), c.getInt("position.intended_lots"),
                c.getInt("position.max_adds"), c.getInt("position.strike_offset"),
                c.getDouble("exits.premium_stop_pct"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(ThresholdConfig c, String path) {
        return List.copyOf((List<String>) c.get(path));
    }
}
