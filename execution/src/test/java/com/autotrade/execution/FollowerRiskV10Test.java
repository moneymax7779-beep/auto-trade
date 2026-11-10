package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** A-372: follower-risk v10 is v9 with three open positions instead of two (risk engine and LiveGuard), nothing else. */
class FollowerRiskV10Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v10RaisesOnlyTheOpenPositionLimitToThree() {
        FollowerRisk v9 = load("v9"), v10 = load("v10");
        assertThat(v10.version()).isEqualTo("follower-risk-v10");
        assertThat(v10.maxOpenPositions()).isEqualTo(3);
        assertThat(v10.live().maxOpenPositions()).isEqualTo(3);
        assertThat(v9.maxOpenPositions()).isEqualTo(2);
        assertThat(v9.live().maxOpenPositions()).isEqualTo(2);

        assertThat(v10.limits()).isEqualTo(v9.limits());
        assertThat(v10.maxLotsPerUnderlying()).isEqualTo(v9.maxLotsPerUnderlying()).isEqualTo(2);
        assertThat(v10.rvolBoostThreshold()).isEqualTo(v9.rvolBoostThreshold());
        assertThat(v10.rvolBoostFactor()).isEqualTo(v9.rvolBoostFactor());
        assertThat(v10.adverseBps()).isEqualTo(v9.adverseBps());
        assertThat(v10.orders()).isEqualTo(v9.orders());
        assertThat(v10.combined()).isEqualTo(v9.combined());
        assertThat(v10.live().maxLotsPerOrder()).isEqualTo(v9.live().maxLotsPerOrder()).isEqualTo(1);
        assertThat(v10.live().strategies()).isEqualTo(v9.live().strategies());
        assertThat(v10.live().defaults()).isEqualTo(v9.live().defaults());
    }
}
