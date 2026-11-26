package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** follower-risk v21 (A-662, 26 Nov 2026): v20 with FE (oi-burst-cross@obc-v31lvoirhe) in place of F1, nothing else. */
class FollowerRiskV21Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v21IsV20WithFeInPlaceOfF1() {
        FollowerRisk before = load("v20"), after = load("v21");
        assertThat(after.version()).isEqualTo("follower-risk-v21");
        Set<String> added = new HashSet<>(after.live().strategies());
        added.removeAll(before.live().strategies());
        assertThat(added).containsExactly("oi-burst-cross@obc-v31lvoirhe");
        Set<String> removed = new HashSet<>(before.live().strategies());
        removed.removeAll(after.live().strategies());
        assertThat(removed).containsExactly("oi-burst-cross@obc-v31lvoirhh");
        assertThat(after.live().defaults()).isEqualTo(before.live().defaults());
        assertThat(after.live().maxLotsPerOrder()).isEqualTo(before.live().maxLotsPerOrder());
        assertThat(after.live().maxOpenPositions()).isEqualTo(before.live().maxOpenPositions());
        assertThat(after.stopRiskCountsRealised()).isEqualTo(before.stopRiskCountsRealised());
        assertThat(after.maxStopRiskPct()).isEqualTo(before.maxStopRiskPct());
        assertThat(after.limits()).isEqualTo(before.limits());
        assertThat(after.dailyLossCountsOpen()).isEqualTo(before.dailyLossCountsOpen());
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.maxLotsPerUnderlying()).isEqualTo(before.maxLotsPerUnderlying());
        assertThat(after.combined()).isEqualTo(before.combined());
        assertThat(after.fillLiveAllocation()).isEqualTo(before.fillLiveAllocation());
        assertThat(after.paperFill()).isEqualTo(before.paperFill());
    }
}
