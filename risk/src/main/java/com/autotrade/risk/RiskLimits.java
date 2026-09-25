package com.autotrade.risk;

import java.time.LocalTime;

import com.autotrade.config.ThresholdConfig;

/** Typed view of a risk file (config/risk/*.yaml). */
public record RiskLimits(
        String hash,
        double capital,
        double dailyLossLimit,
        int maxOpenPositions,
        int maxLotsPerUnderlying,
        int maxOrdersPerMinute,
        LocalTime noNewEntriesAfter,
        LocalTime squareOffAt,
        int maxFeedAgeSec,
        double maxSpreadPct,
        int entryBufferTicks,
        int exitBufferTicks,
        double exitBufferPct,
        int entryTimeoutSec,
        int exitChaseSec,
        int exitChaseMax,
        double stopLimitOffsetPct,
        int reconcileEverySec) {

    public static RiskLimits from(ThresholdConfig c) {
        return new RiskLimits(c.contentHash(),
                c.getDouble("account.capital"), c.getDouble("account.daily_loss_limit"),
                c.getInt("account.max_open_positions"), c.getInt("account.max_lots_per_underlying"),
                c.getInt("account.max_orders_per_minute"),
                c.getTime("time.no_new_entries_after"), c.getTime("time.square_off_at"),
                c.getInt("data.max_feed_age_sec"), c.getDouble("data.max_spread_pct"),
                c.getInt("orders.entry_buffer_ticks"), c.getInt("orders.exit_buffer_ticks"),
                c.has("orders.exit_buffer_pct") ? c.getDouble("orders.exit_buffer_pct") : 0,
                c.getInt("orders.entry_timeout_sec"), c.getInt("orders.exit_chase_sec"),
                c.getInt("orders.exit_chase_max"), c.getDouble("orders.stop_limit_offset_pct"),
                c.getInt("orders.reconcile_every_sec"));
    }
}
