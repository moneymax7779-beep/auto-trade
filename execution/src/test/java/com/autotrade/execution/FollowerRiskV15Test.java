package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** follower-risk v15 (user 19 Nov 2026): v14 with the day's realised loss taken off the stop-risk room, nothing else. */
class FollowerRiskV15Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v15IsV14CountingTheDaysRealisedLoss() {
        FollowerRisk v14 = load("v14"), v15 = load("v15");
        assertThat(v15.version()).isEqualTo("follower-risk-v15");
        assertThat(v14.stopRiskCountsRealised()).isFalse();
        assertThat(v15.stopRiskCountsRealised()).isTrue();
        assertThat(v15.maxStopRiskPct()).isEqualTo(v14.maxStopRiskPct());
        assertThat(v15.limits()).isEqualTo(v14.limits());
        assertThat(v15.dailyLossCountsOpen()).isTrue();
        assertThat(v15.live()).isEqualTo(v14.live());
        assertThat(v15.orders()).isEqualTo(v14.orders());
        assertThat(v15.maxOpenPositions()).isEqualTo(v14.maxOpenPositions());
        assertThat(v15.maxLotsPerUnderlying()).isEqualTo(v14.maxLotsPerUnderlying());
        assertThat(v15.combined()).isEqualTo(v14.combined());
    }
}
