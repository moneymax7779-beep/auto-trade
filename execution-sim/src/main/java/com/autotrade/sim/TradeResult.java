package com.autotrade.sim;

/**
 * Outcome of a simulated long option trade, per the whole quantity, in rupees. MFE/MAE are per unit,
 * measured on the bid after entry. {@code status} is FILLED_AND_EXITED, NOT_FILLED or OPEN_AT_END.
 */
public record TradeResult(
        String status,
        String exitReason,
        FillModel.Fill entry,
        FillModel.Fill exit,
        double grossPnl,
        CostBreakdown entryCosts,
        CostBreakdown exitCosts,
        double netPnl,
        double maxFavourablePerUnit,
        double maxAdversePerUnit,
        long holdSeconds,
        String fillModel) {

    public double totalCosts() {
        return entryCosts.total() + exitCosts.total();
    }
}
