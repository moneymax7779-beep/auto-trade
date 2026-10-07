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
        double deltaRiskPerTrade,
        /** v8: true when capital follows the account's equity ({@code account.capital_mode: equity}). */
        boolean equityMode,
        /** v8: the equity the file's budgets refer to; {@link #capital} / this scales them. */
        double startingCapital,
        /** v8: the daily loss limit as a share of equity (NaN = the fixed rupee limit). */
        double dailyLossPct,
        /** v8: the share of a strategy's scaled budget it may use (1 = all). */
        double premiumBudgetFraction,
        /** v10: equity counts live sessions from this date on (null = all of them); earlier results are left behind. */
        java.time.LocalDate equityFrom) {

    /** v8: these limits with the capital set to {@code equity}; the daily loss limit follows when it is a share. */
    public RiskLimits withEquity(double equity) {
        double dailyLoss = Double.isNaN(dailyLossPct) ? dailyLossLimit : equity * dailyLossPct / 100.0;
        return new RiskLimits(hash, equity, dailyLoss, maxOpenPositions, maxLotsPerUnderlying, maxOrdersPerMinute,
                noNewEntriesAfter, squareOffAt, maxFeedAgeSec, maxSpreadPct, entryBufferTicks, exitBufferTicks,
                exitBufferPct, entryTimeoutSec, exitChaseSec, exitChaseMax, stopLimitOffsetPct, reconcileEverySec,
                casEntryFrom, casEntryTo, maxLossPerTrade, deltaRiskPerTrade, equityMode, startingCapital, dailyLossPct,
                premiumBudgetFraction, equityFrom);
    }

    /** v8: what a strategy file's rupee budget is multiplied by: equity / starting capital × the budget fraction. */
    public double budgetScale() {
        if (!equityMode || !(startingCapital > 0)) {
            return 1.0;
        }
        return capital / startingCapital * premiumBudgetFraction;
    }

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
                c.has("sizing.delta_risk_per_trade") ? c.getDouble("sizing.delta_risk_per_trade") : Double.NaN,
                c.has("account.capital_mode") && "equity".equals(c.getString("account.capital_mode")),
                c.has("account.starting_capital") ? c.getDouble("account.starting_capital") : c.getDouble("account.capital"),
                c.has("account.daily_loss_pct") ? c.getDouble("account.daily_loss_pct") : Double.NaN,
                c.has("sizing.premium_budget_pct") ? c.getDouble("sizing.premium_budget_pct") / 100.0 : 1.0,
                c.has("account.equity_from") ? java.time.LocalDate.parse(String.valueOf(c.get("account.equity_from"))) : null);
    }
}
