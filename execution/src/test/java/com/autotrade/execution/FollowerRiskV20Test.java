package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** follower-risk v20 (user 25 Nov 2026): v19 with F1 (oi-burst-cross@obc-v31lvoirhh) choosable for LIVE, nothing else. */
class FollowerRiskV20Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v20IsV19WithF1ChoosableForLiveByItsFullId() {
        FollowerRisk v19 = load("v19"), v20 = load("v20");
        assertThat(v20.version()).isEqualTo("follower-risk-v20");
        Set<String> added = new HashSet<>(v20.live().strategies());
        added.removeAll(v19.live().strategies());
        assertThat(added).containsExactly("oi-burst-cross@obc-v31lvoirhh");
        assertThat(v20.live().strategies()).containsAll(v19.live().strategies());
        assertThat(v20.live().defaults()).isEqualTo(v19.live().defaults());
        assertThat(v20.live().maxLotsPerOrder()).isEqualTo(v19.live().maxLotsPerOrder());
        assertThat(v20.live().maxOpenPositions()).isEqualTo(v19.live().maxOpenPositions());
        assertThat(v20.stopRiskCountsRealised()).isEqualTo(v19.stopRiskCountsRealised());
        assertThat(v20.maxStopRiskPct()).isEqualTo(v19.maxStopRiskPct());
        assertThat(v20.limits()).isEqualTo(v19.limits());
        assertThat(v20.dailyLossCountsOpen()).isEqualTo(v19.dailyLossCountsOpen());
        assertThat(v20.orders()).isEqualTo(v19.orders());
        assertThat(v20.maxLotsPerUnderlying()).isEqualTo(v19.maxLotsPerUnderlying());
        assertThat(v20.combined()).isEqualTo(v19.combined());
        assertThat(v20.fillLiveAllocation()).isEqualTo(v19.fillLiveAllocation());
        assertThat(v20.paperFill()).isEqualTo(v19.paperFill());
    }
}
