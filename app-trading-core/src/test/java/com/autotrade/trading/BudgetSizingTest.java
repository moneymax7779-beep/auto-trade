package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BudgetSizingTest {

    @Test
    void theFullPlanCostsTheBudgetAtTheEntryAsk() {
        // NIFTY 23400 PE at 39.35, lot 65, plan of 4 lots: one plan unit costs 4 × 65 × 39.35 = 10,231
        BudgetSizing sizing = BudgetSizing.at(500_000, 4, 65, 39.35);
        assertThat(sizing.lots(4)).isEqualTo(195);                  // 4 × 48.87 → 195 lots ≈ ₹4,98,750
        assertThat(sizing.lots(1)).isEqualTo(48);                   // the early tranche
        assertThat(sizing.lots(2)).isEqualTo(97);                   // the confirm tranche
    }

    @Test
    void withoutABudgetOrAPriceTheStrategysLotsAreKept() {
        assertThat(BudgetSizing.at(Double.NaN, 4, 65, 39.35).lots(2)).isEqualTo(2);
        assertThat(BudgetSizing.at(500_000, 4, 65, Double.NaN).lots(2)).isEqualTo(2);
        assertThat(BudgetSizing.at(500_000, 0, 65, 39.35).lots(2)).isEqualTo(2);
        assertThat(BudgetSizing.at(1_000, 4, 20, 400).lots(1)).as("never below one lot").isEqualTo(1);
    }
}
