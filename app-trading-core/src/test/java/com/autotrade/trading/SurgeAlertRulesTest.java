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

    private static final SurgeAlertRules.Levels LEVELS = new SurgeAlertRules.Levels(List.of(
            new SurgeAlertRules.Mark("ORL", 22508.40, 22508.40), new SurgeAlertRules.Mark("PDL", 22595.40, 22595.40),
            new SurgeAlertRules.Mark("ORH", 22589.05, 22589.05),
            new SurgeAlertRules.Mark("support zone (2×)", 22280.0, 22290.0)), 22459.19, 22610.55, 22301.60);

    @Test
    void aBearishSurgeReadsAsBuyThePutWithAStopATargetAndAnHonestReliabilityLine() {
        Map<String, Object> s = surge("12:59", "bearish", 13.6, 52065);
        SurgeAlertRules.Plan plan = SurgeAlertRules.plan(s, LEVELS, "2026-10-06", java.time.LocalDate.of(2026, 10, 6));
        assertThat(plan.call()).isFalse();
        assertThat(plan.stop()).as("the nearest level above, 11+ pts away: VWAP").isEqualTo(22459.19);
        assertThat(plan.target()).as("the nearest level below: the zone's upper edge").isEqualTo(22290.0);
        String text = SurgeAlertRules.message("NIFTY", s, LEVELS, "Today so far: bearish flow right 3/5 at +15m", plan);
        assertThat(text).startsWith("🔴 BUY PUT · NIFTY 22,300 PE · exp 6 Oct\n")
                .contains("Pay ≈ ₹123.25 (ask; bid 123.10) · 1 lot (65) ≈ ₹8,011",
                        "Index 22,325.85 at 12:59 · flow known 13:00",
                        "Stop: exit if index rises above 22,459 (VWAP) · +133 pts",
                        "Target: 22,290 (support zone (2×)) · −36 pts · reward:risk 0.3",
                        "⚠ Reward smaller than risk", "⚠ Expires today",
                        "Why: futures 13.6× normal volume · fut OI +0.12% flat · calls +7.3% buying · puts −2.9% long unwinding",
                        "Above: ORL 22,508 (+183)", "today bearish 3/5", "PAPER, your call");
        s.put("move5", -23.0);
        s.put("move15", 12.6);
        s.put("best15", 30.4);
        s.put("worst15", -40.0);
        assertThat(SurgeAlertRules.followUp("NIFTY", s, plan)).startsWith("↳ BUY PUT NIFTY 22,300 PE (12:59)")
                .contains("+15m +12.6", "✅ target reached");
    }

    @Test
    void aBullishSurgeBuysTheCallAndFallsBackToTheDayExtremes() {
        Map<String, Object> s = surge("10:26", "bullish", 9.0, 48000);
        SurgeAlertRules.Plan plan = SurgeAlertRules.plan(s, new SurgeAlertRules.Levels(List.of(), null, 22400.0, 22200.0),
                "2026-10-13", java.time.LocalDate.of(2026, 10, 6));
        assertThat(plan.stop()).isEqualTo(22200.0);
        assertThat(plan.stopName()).isEqualTo("day low");
        assertThat(plan.target()).isEqualTo(22400.0);
        String text = SurgeAlertRules.message("NIFTY", s, null, null, plan);
        assertThat(text).startsWith("🟢 BUY CALL · NIFTY 22,300 CE · exp 13 Oct").contains("Pay ≈ ₹186.45")
                .doesNotContain("Expires today");
        s.put("move5", -10.0);
        s.put("move15", -130.0);
        s.put("best15", 5.0);
        s.put("worst15", -140.0);
        assertThat(SurgeAlertRules.followUp("NIFTY", s, plan)).contains("❌ stop hit");
    }

    @Test
    void aCallsStopSitsBelowTheSupportZoneNotAtItsTop() {
        Map<String, Object> s = surge("12:38", "bullish", 12.1, 45630);
        s.put("spot", 22709.45);
        SurgeAlertRules.Plan plan = SurgeAlertRules.plan(s, new SurgeAlertRules.Levels(List.of(
                new SurgeAlertRules.Mark("support zone (10×)", 22683.85, 22698.40),
                new SurgeAlertRules.Mark("support zone (7×)", 22670.15, 22679.95)), 22641.0, 22711.0, 22569.0),
                "2026-10-06", java.time.LocalDate.of(2026, 10, 6));
        assertThat(plan.stop()).as("below the nearest support zone (its low edge)").isEqualTo(22683.85);
        assertThat(plan.stopName()).isEqualTo("support zone (10×)");
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
