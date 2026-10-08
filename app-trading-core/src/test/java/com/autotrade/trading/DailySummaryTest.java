package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class DailySummaryTest {

    @Test
    void theMessageIsTheDaysProfitOrLossFollowedByTheTrades() {
        List<DailySummary.Trade> trades = List.of(
                new DailySummary.Trade("11:23", "13:36", "expiry-trend-rider", "SENSEX 72000 PE 08 OCT 26", 2200, 156.89, 361.46, 448456, "X"),
                new DailySummary.Trade("14:01", "14:39", "expiry-trend-rider", "SENSEX 71500 PE 08 OCT 26", 2440, 103.59, 71.92, -77889, "Y"));
        String text = DailySummary.text(LocalDate.of(2026, 10, 8), 370567, trades);
        assertThat(text).isEqualTo("""
                🟢 Profit/Loss · Thu 08 Oct 2026 (PAPER)
                Profit +₹3,70,567 · 2 trades, 1 in profit (after charges)

                Trades:
                ✅ 1. 11:23–13:36  SENSEX 72000 PE (08 Oct)
                    Buy 2200 @ ₹156.89 → Sell @ ₹361.46  +₹4,48,456
                ❌ 2. 14:01–14:39  SENSEX 71500 PE (08 Oct)
                    Buy 2440 @ ₹103.59 → Sell @ ₹71.92  −₹77,889""");
        assertThat(text).doesNotContain("expiry-trend-rider").doesNotContain("Goal").doesNotContain("X)");
        assertThat(DailySummary.text(LocalDate.of(2026, 10, 9), -12000,
                List.of(new DailySummary.Trade("10:00", "10:05", "s", "NIFTY 22450 PE 13 OCT 26", 65, 100, 90, -12000, "STOP"))))
                .startsWith("🔴 Profit/Loss · Fri 09 Oct 2026 (PAPER)\nLoss −₹12,000 · 1 trade, 0 in profit");
        assertThat(DailySummary.text(LocalDate.of(2026, 10, 12), 0, List.of()))
                .isEqualTo("🟢 Profit/Loss · Mon 12 Oct 2026 (PAPER)\nProfit ₹0 (after charges)\n\nNo trades today.");
    }
}
