package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AlertMonitorOpeningsTest {

    private static Map<String, Object> position(String strategy, String symbol, long qty, double avg, String opened) {
        return Map.of("strategy", strategy, "underlying", "NIFTY", "symbol", symbol, "quantity", qty, "lotSize", 65,
                "averageCost", avg, "opened", opened, "side", symbol.contains(" CE ") ? "CE" : "PE");
    }

    @Test
    void aFilledPositionIsReportedOnceWhenItOpensNotWhenItCloses() {
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> open = List.of(position("expiry-trend-rider", "NIFTY 22700 CE 06 OCT 26", 2470, 49.45, "12:39:00"));
        assertThat(AlertMonitor.newOpenings(open, seen, false))
                .containsExactly("🟢 BUY CALL (auto, PAPER) · NIFTY 22700 CE 06 OCT 26 · 38 lots (2,470) @ ₹49.45 = ₹1,22,142"
                        + " · expiry-trend-rider · 12:39");
        assertThat(AlertMonitor.newOpenings(open, seen, false)).as("next minute, same position").isEmpty();
    }

    @Test
    void aStraddleIsOneTradeAndAnUnfilledEntryWaits() {
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> open = List.of(
                position("expiry-breakout-straddle", "NIFTY 23300 CE 22 SEP 26", 5980, 42.20, "13:54:00"),
                position("expiry-breakout-straddle", "NIFTY 23300 PE 22 SEP 26", 5980, 42.15, "13:54:00"),
                position("opening-drive", "NIFTY 22950 PE 29 SEP 26", 0, 0, "09:16:00"));
        assertThat(AlertMonitor.newOpenings(open, seen, false)).containsExactly(
                "🟡 BUY STRADDLE (auto, PAPER) · NIFTY 23300 CE 22 SEP 26 + NIFTY 23300 PE 22 SEP 26 · 92 lots (5,980) each"
                        + " @ ₹42.20 / ₹42.15 = ₹5,04,413 · expiry-breakout-straddle · 13:54");
    }

    @Test
    void theFirstLookAtASessionRemembersWithoutReporting() {
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> open = List.of(position("expiry-trend-rider", "NIFTY 22700 CE 06 OCT 26", 2470, 49.45, "12:39:00"));
        assertThat(AlertMonitor.newOpenings(open, seen, true)).isEmpty();
        assertThat(AlertMonitor.newOpenings(open, seen, false)).isEmpty();
    }

    @Test
    void anExitSaysWhatWasSoldHowItWentAndWhy() {
        assertThat(AlertMonitor.closeMessage("expiry-trend-rider", "NIFTY 22700 CE 06 OCT 26", "CE", "38 lots (2470)",
                49.45, 31.37, "SWING_LOW_LOST", -44913, "14:27"))
                .isEqualTo("❌ EXIT CALL (auto, PAPER) · NIFTY 22700 CE 06 OCT 26 · 38 lots (2470) · ₹49.45 → ₹31.37 (−36.6%)"
                        + " · swing low lost · net −₹44,913 · expiry-trend-rider · 14:27");
    }
}
