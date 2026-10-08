package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class DailySummaryTest {

    @Test
    void theMessageCarriesTheDayEquityGoalPaceStrategiesAndTrades() {
        List<DailySummary.Trade> trades = List.of(
                new DailySummary.Trade("11:23", "expiry-trend-rider", "SENSEX 72000 PE 08 OCT 26", 2200, 156.89, 361.46, 448456, "SWING_HIGH_RECLAIMED"),
                new DailySummary.Trade("14:01", "expiry-trend-rider", "SENSEX 71500 PE 08 OCT 26", 2440, 103.59, 71.92, -77889, "SWING_HIGH_RECLAIMED"),
                new DailySummary.Trade("10:30", "break-retest", "NIFTY 22450 PE 13 OCT 26", 2210, 125.50, 144.51, 41364, "TARGET"));
        String text = DailySummary.text(LocalDate.of(2026, 10, 8), 411931, 9000, 560121, 972052, 605000, 2, true, trades, 2, 13);
        assertThat(text).startsWith("🟢 Daily P&L · auto-trade PAPER · Thu 08 Oct 2026");
        assertThat(text).contains("Day: +₹4,11,931 (+73.5%) · 3 trades, 2 wins · costs ₹9,000");
        assertThat(text).contains("Equity: ₹5,60,121 → ₹9,72,052");
        assertThat(text).contains("Goal pace (10%/session, session 2): ₹6,05,000 · ahead +₹3,67,052");
        assertThat(text).contains("• expiry-trend-rider: 2 trades, 1 win, +₹3,70,567");
        assertThat(text).contains("14:01 expiry-trend-rider · SENSEX 71500 PE 08 OCT 26 · 2440 @ ₹103.59 → ₹71.92 · −₹77,889 (SWING_HIGH_RECLAIMED)");
        assertThat(text).endsWith("Not filled: 2 · refused by risk/capital: 13");
        assertThat(DailySummary.text(LocalDate.of(2026, 10, 9), 0, 0, 500000, 500000, 550000, 1, true, List.of(), 0, 0))
                .startsWith("🟢").contains("No trades today.").contains("behind −₹50,000");
    }
}
