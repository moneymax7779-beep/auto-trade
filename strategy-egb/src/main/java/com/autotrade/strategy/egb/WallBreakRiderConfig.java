package com.autotrade.strategy.egb;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.OptionSide;

/** Typed view of wall-break-rider.vN.yaml (ledger A-057; v3 keys A-327, each off when absent). */
record WallBreakRiderConfig(
        String hash,
        String version,
        /** Days to expiry the strategy trades on; -1 = every day (A-338). */
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
        double timeStopMinGainPct,
        /** v2: the wall's OI must have fallen at least this % over 10 minutes with the index moving toward it (NaN = v1's weakening flags). */
        double wallOiDropPct,
        /** v3: no entry when the index moved more than this many ATR(3m) toward the trade over priorMoveMin (NaN = off). */
        double maxPriorMoveAtr,
        int priorMoveMin,
        /** v3: after a losing trade, no entry on the opposite side for this many minutes from its exit (0 = off). */
        int noFlipAfterLossMin,
        /** v3: once the index crosses the wall strike recorded at entry, a 3-minute close back through it exits. */
        boolean wallRecrossExit,
        /** A-328 v4: no entry when the index is more than this many ATR(3m) from VWAP on the trade's side (NaN = off). */
        double maxVwapDistAtr,
        /** A-328 v6: no entry when the entry wall is less than this many ATR(3m) from the index (NaN = off). */
        double minWallDistAtr,
        /** A-328 v7: at most this many filled entries a day (0 = no limit). */
        int maxTradesPerDay,
        /** A-329 v8: after a winning trade today, no new entry from this time (null = off). */
        LocalTime reentryAfterWinUntil,
        /** A-329 v9: no entry when the ATM IV of the trade's side is at least this (a fraction, 0.35 = 35 %; NaN = off). */
        double maxEntryIv,
        /** A-339 baseline: false = no wall-OI condition, the index only has to move toward the wall over 3 min (default true). */
        boolean requireWallOiDrop,
        /** A-340: false = the VWAP-side condition is off (default true). */
        boolean requireVwapSide,
        /** A-341: no entry when the ATM straddle is more than this many ATR(3m) (NaN = off). */
        double maxStraddleAtr) {

    static WallBreakRiderConfig from(ThresholdConfig c) {
        return new WallBreakRiderConfig(c.contentHash(), c.version(), c.getInt("scope.dte"),
                c.getTime("scope.entry_from"), c.getTime("scope.last_new_entry"), c.getTime("scope.flat_by"),
                c.getInt("signal.confirm_minutes"),
                Map.of(OptionSide.CE, Set.copyOf(strings(c, "signal.reject_futures_states.CE")),
                        OptionSide.PE, Set.copyOf(strings(c, "signal.reject_futures_states.PE"))),
                c.getDouble("position.premium_budget"), c.getInt("position.intended_lots"), c.getInt("position.strike_offset"),
                c.getDouble("exits.premium_stop_pct"), c.getDouble("exits.trail_activation_pct"),
                c.getDouble("exits.trail_giveback_pct"), c.getInt("exits.time_stop_min"),
                c.getDouble("exits.time_stop_min_gain_pct"),
                c.has("signal.wall_oi_drop_pct") ? c.getDouble("signal.wall_oi_drop_pct") : Double.NaN,
                c.has("signal.max_prior_move_atr") ? c.getDouble("signal.max_prior_move_atr") : Double.NaN,
                c.has("signal.prior_move_min") ? c.getInt("signal.prior_move_min") : 30,
                c.has("signal.no_flip_after_loss_min") ? c.getInt("signal.no_flip_after_loss_min") : 0,
                c.has("exits.wall_recross_exit") && Boolean.TRUE.equals(c.get("exits.wall_recross_exit")),
                c.has("signal.max_vwap_dist_atr") ? c.getDouble("signal.max_vwap_dist_atr") : Double.NaN,
                c.has("signal.min_wall_dist_atr") ? c.getDouble("signal.min_wall_dist_atr") : Double.NaN,
                c.has("signal.max_trades_per_day") ? c.getInt("signal.max_trades_per_day") : 0,
                c.has("signal.reentry_after_win_until") ? c.getTime("signal.reentry_after_win_until") : null,
                c.has("signal.max_entry_iv") ? c.getDouble("signal.max_entry_iv") : Double.NaN,
                !c.has("signal.require_wall_oi_drop") || Boolean.TRUE.equals(c.get("signal.require_wall_oi_drop")),
                !c.has("signal.require_vwap_side") || Boolean.TRUE.equals(c.get("signal.require_vwap_side")),
                c.has("signal.max_straddle_atr") ? c.getDouble("signal.max_straddle_atr") : Double.NaN);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(ThresholdConfig c, String path) {
        return List.copyOf((List<String>) c.get(path));
    }
}
