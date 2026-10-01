package com.autotrade.risk;

import java.time.LocalTime;

import com.autotrade.config.ThresholdConfig;

/** Typed view of a risk file (config/risk/*.yaml); the CAS entry window is risk v3 (null before). */
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
        int reconcileEverySec,
        LocalTime casEntryFrom,
        LocalTime casEntryTo,
        /** v5: the most one trade may lose at its own stop, in rupees (NaN = no cap, v1-v4). */
        double maxLossPerTrade,
        /** v6: rupee risk per single-leg entry at its first stop, from delta and the structure stop (NaN = off). */
        double deltaRiskPerTrade) {

    /**
     * The most premium an entry may buy so that its stop ({@code stopPct} below the price paid) loses
     * no more than {@link #maxLossPerTrade}; infinite without a cap or a stop.
     */
    public double premiumCapForStop(double stopPct) {
        if (Double.isNaN(maxLossPerTrade) || !(stopPct > 0)) {
            return Double.POSITIVE_INFINITY;
        }
        return maxLossPerTrade / (stopPct / 100.0);
    }

    /**
     * v6: the most premium a single-leg entry may buy so that its first stop loses {@link #deltaRiskPerTrade}.
     * Risk per unit is the smaller of the premium stop ({@code stopPct} of the ask) and |delta| × the index
     * points to the structure stop (when both are known); infinite when v6 is off or nothing is known.
     */
    public double premiumCapForRisk(double stopPct, double ask, double delta, double stopPoints) {
        if (Double.isNaN(deltaRiskPerTrade) || !(ask > 0)) {
            return Double.POSITIVE_INFINITY;
        }
        double perUnit = stopPct > 0 ? ask * stopPct / 100.0 : Double.POSITIVE_INFINITY;
        if (Double.isFinite(delta) && delta != 0 && stopPoints > 0) {
            perUnit = Math.min(perUnit, Math.abs(delta) * stopPoints);
        }
        return Double.isFinite(perUnit) ? deltaRiskPerTrade / perUnit * ask : Double.POSITIVE_INFINITY;
    }


    /** True inside the closing-auction entry window (risk v3), where entries after the cutoff are allowed. */
    public boolean inCasEntryWindow(LocalTime time) {
        return casEntryFrom != null && !time.isBefore(casEntryFrom) && time.isBefore(casEntryTo);
    }

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
                c.getInt("orders.reconcile_every_sec"),
                c.has("time.cas_entry_window") ? LocalTime.parse(c.getString("time.cas_entry_window").split("-")[0]) : null,
                c.has("time.cas_entry_window") ? LocalTime.parse(c.getString("time.cas_entry_window").split("-")[1]) : null,
                c.has("account.max_loss_per_trade") ? c.getDouble("account.max_loss_per_trade") : Double.NaN,
                c.has("sizing.delta_risk_per_trade") ? c.getDouble("sizing.delta_risk_per_trade") : Double.NaN);
    }
}
