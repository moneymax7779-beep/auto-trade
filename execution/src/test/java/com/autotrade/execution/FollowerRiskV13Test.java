package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * follower-risk v13 (review 16 Nov 2026, D4) is v11 with the trader's manual orders allowed LIVE: {@code manual} in the
 * LIVE strategies and their defaults, nothing else changed. The "allow manual LIVE" messages pointed at v8, which is v7
 * plus manual, so moving a v11 account to it dropped the daily loss that counts open positions, a position and the
 * strategies added since.
 */
class FollowerRiskV13Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v13IsV11WithManualOrdersAllowedLive() {
        FollowerRisk v11 = load("v11"), v13 = load("v13");
        assertThat(v13.version()).isEqualTo("follower-risk-v13");
        List<String> strategies = new ArrayList<>(v11.live().strategies());
        strategies.add(ManualOrders.STRATEGY);
        assertThat(v13.live().strategies()).containsExactlyInAnyOrderElementsOf(strategies);
        List<String> defaults = new ArrayList<>(v11.live().defaults());
        defaults.add(ManualOrders.STRATEGY);
        assertThat(v13.live().defaults()).as("an account that has not chosen still places manual buys")
                .containsExactlyInAnyOrderElementsOf(defaults);
        assertThat(v13.live().maxLotsPerOrder()).isEqualTo(v11.live().maxLotsPerOrder());
        assertThat(v13.live().maxOpenPositions()).isEqualTo(v11.live().maxOpenPositions());

        assertThat(v13.dailyLossCountsOpen()).as("v11's open-loss stop is kept").isTrue();
        assertThat(v13.limits()).isEqualTo(v11.limits());
        assertThat(v13.maxOpenPositions()).isEqualTo(v11.maxOpenPositions());
        assertThat(v13.maxLotsPerUnderlying()).isEqualTo(v11.maxLotsPerUnderlying());
        assertThat(v13.rvolBoostThreshold()).isEqualTo(v11.rvolBoostThreshold());
        assertThat(v13.rvolBoostFactor()).isEqualTo(v11.rvolBoostFactor());
        assertThat(v13.convictionRvolFull()).isNaN();
        assertThat(v13.adverseBps()).isEqualTo(v11.adverseBps());
        assertThat(v13.orders()).isEqualTo(v11.orders());
        assertThat(v13.combined()).isEqualTo(v11.combined());
    }
}
