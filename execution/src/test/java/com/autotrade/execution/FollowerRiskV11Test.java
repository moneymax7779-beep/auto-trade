package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * follower-risk v11 (user 11 Nov 2026, ZERODHA-1: full size from the allocation, no lot ceiling below it, the daily loss
 * counting open positions) is v10 with only those three changes.
 */
class FollowerRiskV11Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v11LiftsTheOneLotCapsAndCountsOpenLossNothingElse() {
        FollowerRisk v10 = load("v10"), v11 = load("v11");
        assertThat(v11.version()).isEqualTo("follower-risk-v11");
        assertThat(v11.live().maxLotsPerOrder()).isEqualTo(50);
        assertThat(v11.maxLotsPerUnderlying()).isEqualTo(50);
        assertThat(v11.dailyLossCountsOpen()).isTrue();
        assertThat(v10.dailyLossCountsOpen()).as("before v11: closed trades only").isFalse();

        assertThat(v11.limits()).isEqualTo(v10.limits());
        assertThat(v11.maxOpenPositions()).isEqualTo(v10.maxOpenPositions()).isEqualTo(3);
        assertThat(v11.live().maxOpenPositions()).isEqualTo(v10.live().maxOpenPositions()).isEqualTo(3);
        assertThat(v11.rvolBoostThreshold()).isEqualTo(v10.rvolBoostThreshold());
        assertThat(v11.rvolBoostFactor()).isEqualTo(v10.rvolBoostFactor());
        assertThat(v11.adverseBps()).isEqualTo(v10.adverseBps());
        assertThat(v11.orders()).isEqualTo(v10.orders());
        assertThat(v11.combined()).isEqualTo(v10.combined());
        assertThat(v11.live().strategies()).isEqualTo(v10.live().strategies());
        assertThat(v11.live().defaults()).isEqualTo(v10.live().defaults());
    }
}
