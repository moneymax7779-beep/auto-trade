package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Surge alerts: agreeing flow only, volume floors, one per index and direction per cooldown, readable text. */
class SurgeAlertRulesTest {

    private static SurgeAlertRules rules() {
        return new SurgeAlertRules(new SurgeAlertRules.Settings(10, Map.of("SENSEX", 1000L), 10,
                LocalTime.of(9, 20), LocalTime.of(15, 15)));
    }

    private static Map<String, Object> surge(String t, String flow, double x, long vol) {
        Map<String, Object> s = new HashMap<>();
        s.put("t", t);
        s.put("from", LocalTime.parse(t).plusMinutes(1).toString());
        s.put("flow", flow);
        s.put("x", x);
        s.put("vol", vol);
        s.put("spot", 22325.85);
        s.put("atm", 22300.0);
        s.put("futures", Map.of("pct", 0.12, "label", "flat", "px", 51.8, "oi", 22165));
        s.put("CE", Map.of("pct", 7.3, "label", "buying", "bid", 186.15, "ask", 186.45));
        s.put("PE", Map.of("pct", -2.9, "label", "long unwinding", "bid", 123.10, "ask", 123.25));
        return s;
    }

    @Test
    void onlyAgreeingFlowAboveTheFloorsInsideTheWindow() {
        SurgeAlertRules r = rules();
        assertThat(r.accept("NIFTY", surge("12:59", "mixed", 20, 52065))).isFalse();
        assertThat(r.accept("NIFTY", surge("12:59", "bearish", 9.9, 52065))).isFalse();
        assertThat(r.accept("SENSEX", surge("12:59", "bearish", 30, 900))).isFalse();      // thin SENSEX futures
        assertThat(r.accept("NIFTY", surge("09:18", "bearish", 30, 52065))).isFalse();     // before 09:20
        assertThat(r.accept("NIFTY", surge("12:59", "bearish", 13.6, 52065))).isTrue();
    }

    @Test
    void oneAlertPerDirectionPerCooldownUnlessItFlipsOrDoubles() {
        SurgeAlertRules r = rules();
        assertThat(r.accept("NIFTY", surge("12:50", "bearish", 12, 40000))).isTrue();
        assertThat(r.accept("NIFTY", surge("12:55", "bearish", 15, 40000))).isFalse();     // inside 10 min
        assertThat(r.accept("NIFTY", surge("12:56", "bullish", 11, 40000))).isTrue();      // the flow flipped
        assertThat(r.accept("NIFTY", surge("12:57", "bearish", 26, 40000))).isTrue();      // more than doubled
        assertThat(r.accept("NIFTY", surge("13:08", "bearish", 11, 40000))).isTrue();      // cooldown over
        assertThat(r.accept("SENSEX", surge("12:58", "bearish", 12, 4000))).isTrue();      // per index
    }

    @Test
    void messageCarriesTheContextAndTheFollowUpTheOutcome() {
        Map<String, Object> s = surge("12:59", "bearish", 13.6, 52065);
        String text = SurgeAlertRules.message("NIFTY", s, new SurgeAlertRules.Levels(List.of(
                        new SurgeAlertRules.Mark("ORL", 22508.40, 22508.40), new SurgeAlertRules.Mark("PDL", 22595.40, 22595.40),
                        new SurgeAlertRules.Mark("ORH", 22589.05, 22589.05),
                        new SurgeAlertRules.Mark("support zone (2×)", 22280.0, 22290.0)), 22459.19, 22610.55, 22301.60),
                "Today so far: bearish flow right 3/5 at +15m");
        assertThat(text).contains("NIFTY BEARISH surge · 12:59 (flow known 13:00)", "Futures 52,065 = 13.6× normal",
                "OI +0.12% flat", "Calls ±2 +7.3% buying", "Puts ±2 −2.9% long unwinding",
                "Above: ORL 22,508 (+183), ORH 22,589 (+263)", "Below: support zone (2×) 22,280–22,290 (−36)",
                "VWAP 22,459 (−133) · day 22,302–22,611", "ATM 22,300: CE 186.15/186.45 · PE 123.10/123.25", "right 3/5",
                "not a trade signal");
        s.put("move5", -23.0);
        s.put("move15", 12.6);
        s.put("best15", 30.4);
        s.put("worst15", -40.0);
        assertThat(SurgeAlertRules.followUp("NIFTY", s)).contains("+15m +12.6", "AGAINST the flow");
    }

    @Test
    void hitRateCountsOnlyWhatWasKnown() {
        Map<String, Object> early = surge("12:00", "bearish", 12, 40000);
        early.put("move15", -20.0);
        Map<String, Object> late = surge("12:50", "bearish", 12, 40000);
        late.put("move15", 10.0);
        assertThat(SurgeAlertRules.hitRate(List.of(early, late), "bearish", LocalTime.of(12, 30)))
                .isEqualTo("Today so far: bearish flow right 1/1 at +15m");
        assertThat(SurgeAlertRules.fmt(9909740, 0)).isEqualTo("99,09,740");
    }
}
