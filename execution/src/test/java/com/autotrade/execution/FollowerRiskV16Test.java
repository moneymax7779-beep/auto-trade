package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** follower-risk v16 (user 22 Nov 2026): v15 with ma-cross-open choosable for LIVE (not a default), nothing else. */
class FollowerRiskV16Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v16IsV15WithMaCrossOpenChoosableForLive() {
        FollowerRisk v15 = load("v15"), v16 = load("v16");
        assertThat(v16.version()).isEqualTo("follower-risk-v16");
        Set<String> added = new HashSet<>(v16.live().strategies());
        added.removeAll(v15.live().strategies());
        assertThat(added).containsExactly("ma-cross-open");
        assertThat(v16.live().strategies()).containsAll(v15.live().strategies());
        assertThat(v16.live().defaults()).isEqualTo(v15.live().defaults());
        assertThat(v16.live().maxLotsPerOrder()).isEqualTo(v15.live().maxLotsPerOrder());
        assertThat(v16.live().maxOpenPositions()).isEqualTo(v15.live().maxOpenPositions());
        assertThat(v16.stopRiskCountsRealised()).isEqualTo(v15.stopRiskCountsRealised());
        assertThat(v16.maxStopRiskPct()).isEqualTo(v15.maxStopRiskPct());
        assertThat(v16.limits()).isEqualTo(v15.limits());
        assertThat(v16.dailyLossCountsOpen()).isEqualTo(v15.dailyLossCountsOpen());
        assertThat(v16.orders()).isEqualTo(v15.orders());
        assertThat(v16.maxLotsPerUnderlying()).isEqualTo(v15.maxLotsPerUnderlying());
        assertThat(v16.combined()).isEqualTo(v15.combined());
    }
}
