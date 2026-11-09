package com.autotrade.execution;

import java.nio.file.Path;

import com.autotrade.account.CapitalLimits;
import com.autotrade.config.ThresholdConfig;

/**
 * A follower's risk file (config/risk/follower-risk.vN.yaml): capital limits, position and lot limits, the RVOL boost
 * (v2), the simulated fill model and, from v3, the broker order rules ({@code orders}: null before v3, when only
 * simulated fills are possible). Limits a file does not set are off (v1: no position, lot or boost rules). From v7,
 * {@code live}: the limits a LIVE account trades under (null: the file cannot be used LIVE).
 */
public record FollowerRisk(String version, CapitalLimits limits, int maxOpenPositions, int maxLotsPerUnderlying,
                           double rvolBoostThreshold, double rvolBoostFactor, double adverseBps, OrderRules orders,
                           Combined combined, LiveLimits live) {

    /**
     * v7 (the Kite LIVE pilot, user 2 Nov 2026): a LIVE account's hard limits, checked again in front of the broker
     * connection ({@link LiveGuard}): lots per buy order, contracts held at once, and the only strategies it may enter.
     */
    /**
     * {@code strategies}: what may trade LIVE on an account (v9: what the owner may choose from); {@code defaults}: what
     * trades LIVE on an account the owner has not chosen for (live.default_strategies; a file without them: all of
     * {@code strategies}). See {@link LiveStrategies}.
     */
    public record LiveLimits(int maxLotsPerOrder, int maxOpenPositions, java.util.Set<String> strategies,
                             java.util.Set<String> defaults) {

        public LiveLimits {
            if (maxLotsPerOrder < 1 || maxOpenPositions < 1 || strategies == null || strategies.isEmpty()) {
                throw new IllegalArgumentException("live: max_lots_per_order, max_open_positions >= 1 and strategies");
            }
            strategies = java.util.Set.copyOf(strategies);
            defaults = defaults == null ? strategies : java.util.Set.copyOf(defaults);
            if (!strategies.containsAll(defaults)) {
                throw new IllegalArgumentException("live: default_strategies must be among live.strategies");
            }
        }

        public LiveLimits(int maxLotsPerOrder, int maxOpenPositions, java.util.Set<String> strategies) {
            this(maxLotsPerOrder, maxOpenPositions, strategies, null);
        }
    }

    /**
     * v4 (L6): limits across a tenant's broker-routed accounts (DRY_RUN and LIVE; PAPER accounts trade no real book).
     * {@code maxOpenPremiumPct}: their premium in use + this entry <= this % of their combined allocated capital;
     * {@code maxLotsPerContract}: lots of one contract held across them; {@code shareLiquidity}: a decision's usable
     * depth (liquidity_lots) is split across the tenant's broker-routed accounts that follow it, pro rata to allocation.
     */
    public record Combined(double maxOpenPremiumPct, long maxLotsPerContract, boolean shareLiquidity) {
    }

    public static FollowerRisk load(Path file) {
        ThresholdConfig c = ThresholdConfig.load(file);
        return new FollowerRisk(c.version(), new CapitalLimits(c.getDouble("capital.daily_loss_pct"),
                c.getDouble("capital.max_premium_per_trade_pct"), c.getDouble("capital.max_open_premium_pct"),
                c.getDouble("capital.broker_funds_buffer_pct")),
                c.has("positions.max_open_positions") ? c.getInt("positions.max_open_positions") : Integer.MAX_VALUE,
                c.has("positions.max_lots_per_underlying") ? c.getInt("positions.max_lots_per_underlying") : Integer.MAX_VALUE,
                c.has("sizing.rvol_boost_threshold") ? c.getDouble("sizing.rvol_boost_threshold") : Double.NaN,
                c.has("sizing.rvol_boost_factor") ? c.getDouble("sizing.rvol_boost_factor") : 1.0,
                c.has("simulation.adverse_bps") ? c.getDouble("simulation.adverse_bps") : 0, OrderRules.from(c),
                c.has("combined.max_open_premium_pct") ? new Combined(c.getDouble("combined.max_open_premium_pct"),
                        c.getInt("combined.max_lots_per_contract"), c.getBoolean("combined.share_liquidity")) : null,
                c.has("live.max_lots_per_order") ? new LiveLimits(c.getInt("live.max_lots_per_order"),
                        c.getInt("live.max_open_positions"), names(c.get("live.strategies")),
                        c.has("live.default_strategies") ? names(c.get("live.default_strategies")) : null) : null);
    }

    private static java.util.Set<String> names(Object list) {
        return ((java.util.List<?>) list).stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    /** The budget multiplier for an entry at this RVOL (risk v17: the factor at or above the threshold, else 1). */
    public double boost(double rvolTod) {
        return !Double.isNaN(rvolBoostThreshold) && rvolTod >= rvolBoostThreshold ? rvolBoostFactor : 1.0;
    }
}
