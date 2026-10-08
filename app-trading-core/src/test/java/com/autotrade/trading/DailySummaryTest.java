package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class DailySummaryTest {

    private static final LocalDate OCT_8 = LocalDate.of(2026, 10, 8);

    @Test
    void theResultLeadsEachTradeAndTheExpiryShowsOnlyWhenItIsNotThatDay() {
        List<DailySummary.Trade> trades = List.of(
                new DailySummary.Trade("10:30", "10:40", "break-retest", "NIFTY 22450 PE 13 OCT 26", 2210, 125.50, 144.51, 41364, "TARGET"),
                new DailySummary.Trade("11:23", "13:36", "expiry-trend-rider", "SENSEX 72000 PE 08 OCT 26", 2200, 156.89, 361.46, 448456, "X"),
                new DailySummary.Trade("14:01", "14:39", "expiry-trend-rider", "SENSEX 71500 PE 08 OCT 26", 2440, 103.36, 72.67, -75471, "Y"));
        assertThat(DailySummary.text(OCT_8, 414349, trades)).isEqualTo("""
                🟢 Profit +₹4,14,349 · Thu 08 Oct (PAPER)
                3 trades: 2 won, 1 lost · after charges

                ✅ +₹41,364  NIFTY 22450 PE · exp 13 Oct
                     10:30–10:40 · 2,210 qty · 125.50 → 144.51
                ✅ +₹4,48,456  SENSEX 72000 PE
                     11:23–13:36 · 2,200 qty · 156.89 → 361.46
                ❌ −₹75,471  SENSEX 71500 PE
                     14:01–14:39 · 2,440 qty · 103.36 → 72.67""");
    }

    @Test
    void aLosingDayAndADayWithoutTrades() {
        assertThat(DailySummary.text(LocalDate.of(2026, 10, 9), -12000,
                List.of(new DailySummary.Trade("10:00", "10:05", "s", "NIFTY 22450 PE 13 OCT 26", 65, 100, 90, -12000, "STOP"))))
                .startsWith("🔴 Loss −₹12,000 · Fri 09 Oct (PAPER)\n1 trade: 0 won, 1 lost");
        assertThat(DailySummary.text(LocalDate.of(2026, 10, 12), 0, List.of()))
                .isEqualTo("🟢 Profit ₹0 · Mon 12 Oct (PAPER)\nNo trades today.");
        assertThat(DailySummary.contract("SENSEX 72400 PE 08 OCT 26", OCT_8)).isEqualTo("SENSEX 72400 PE");
    }
}
