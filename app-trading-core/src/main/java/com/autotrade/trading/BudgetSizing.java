package com.autotrade.trading;

/**
 * Sizes a strategy's lots to a premium budget. The strategy decides in units of its full plan
 * ({@code intendedLots}, e.g. 4 lots as 1 + 2 + 1); the executor fixes one scale per position at the
 * entry, so the full plan would cost the budget at the entry ask, and applies it to every tranche.
 */
record BudgetSizing(double scale) {

    /** The scale at an entry; NaN (no scaling) without a budget or a usable price. */
    static BudgetSizing at(double budget, int intendedLots, int lotSize, double ask) {
        if (!(budget > 0) || intendedLots <= 0 || lotSize <= 0 || !(ask > 0)) {
            return new BudgetSizing(Double.NaN);
        }
        return new BudgetSizing(budget / (intendedLots * (double) lotSize * ask));
    }

    /** The lots to send for a tranche of {@code strategyLots} (at least 1). */
    int lots(int strategyLots) {
        if (Double.isNaN(scale)) {
            return strategyLots;
        }
        return Math.max(1, (int) Math.floor(strategyLots * scale));
    }
}
