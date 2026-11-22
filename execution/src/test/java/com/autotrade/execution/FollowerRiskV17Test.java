package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** follower-risk v17 (user 22 Nov 2026): v16 with live.fill_allocation, nothing else; and the fill factor itself. */
class FollowerRiskV17Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v17IsV16FillingTheLiveAllocation() {
        FollowerRisk v16 = load("v16"), v17 = load("v17");
        assertThat(v17.version()).isEqualTo("follower-risk-v17");
        assertThat(v16.fillLiveAllocation()).isFalse();
        assertThat(v17.fillLiveAllocation()).isTrue();
        assertThat(v17.live()).isEqualTo(v16.live());
        assertThat(v17.limits()).isEqualTo(v16.limits());
        assertThat(v17.stopRiskCountsRealised()).isEqualTo(v16.stopRiskCountsRealised());
        assertThat(v17.maxStopRiskPct()).isEqualTo(v16.maxStopRiskPct());
        assertThat(v17.dailyLossCountsOpen()).isEqualTo(v16.dailyLossCountsOpen());
        assertThat(v17.orders()).isEqualTo(v16.orders());
        assertThat(v17.maxOpenPositions()).isEqualTo(v16.maxOpenPositions());
        assertThat(v17.maxLotsPerUnderlying()).isEqualTo(v16.maxLotsPerUnderlying());
        assertThat(v17.combined()).isEqualTo(v16.combined());
        assertThat(v17.rvolBoostFactor()).isEqualTo(v16.rvolBoostFactor());
        assertThat(v17.rvolBoostThreshold()).isEqualTo(v16.rvolBoostThreshold());
    }

    /** The live six's file budgets (etr 5 L, mac / obc / wbr / mco 2.5 L, sbr 0.5 L) at the 5 L reference. */
    private static final Map<String, Double> SET = Map.of("expiry-trend-rider", 500_000.0, "ma-cross", 250_000.0,
            "oi-burst-cross", 250_000.0, "wall-break-rider", 250_000.0, "session-break-retest", 50_000.0,
            "ma-cross-open", 250_000.0);

    @Test
    void theSelectedStrategiesFillTheEquityNeverLessThanToday() {
        // all six: their budgets add up to 3.1 x the reference, so nothing changes
        assertThat(FollowerSimulator.fillFactor(SET, SET.keySet(), "ma-cross", 500_000)).isEqualTo(1.0);
        // mac + obc: exactly 100 % already
        assertThat(FollowerSimulator.fillFactor(SET, List.of("ma-cross", "oi-burst-cross"), "ma-cross", 500_000))
                .isEqualTo(1.0);
        // mac alone: the whole equity
        assertThat(FollowerSimulator.fillFactor(SET, List.of("ma-cross"), "ma-cross", 500_000)).isEqualTo(2.0);
        // mac + sbr: 3 L of budgets fill 5 L, both scaled by 5 / 3
        assertThat(FollowerSimulator.fillFactor(SET, List.of("ma-cross", "session-break-retest"), "session-break-retest",
                500_000)).isEqualTo(500_000.0 / 300_000.0);
        // a selected strategy the signal does not run (ecr, out of the live set) counts for nothing
        assertThat(FollowerSimulator.fillFactor(SET, List.of("ma-cross", "early-confirm-runner"), "ma-cross", 500_000))
                .isEqualTo(2.0);
        // a strategy not selected, or not in the set, or no budgets published: unchanged
        assertThat(FollowerSimulator.fillFactor(SET, List.of("oi-burst-cross"), "ma-cross", 500_000)).isEqualTo(1.0);
        assertThat(FollowerSimulator.fillFactor(Map.of(), List.of("ma-cross"), "ma-cross", 500_000)).isEqualTo(1.0);
        assertThat(FollowerSimulator.fillFactor(SET, List.of("ma-cross"), "ma-cross", Double.NaN)).isEqualTo(1.0);
    }

    @Test
    void theSetBudgetsAreReadFromTheContext() {
        assertThat(FollowerSimulator.setBudgets("{\"budget:ma-cross\": 250000.0, \"budget:oi-burst-cross\": 250000.0, "
                + "\"strategy_lots\": 40.0, \"budget:bad\": null}"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("ma-cross", 250_000.0, "oi-burst-cross", 250_000.0));
        assertThat(FollowerSimulator.setBudgets(null)).isEmpty();
    }
}
