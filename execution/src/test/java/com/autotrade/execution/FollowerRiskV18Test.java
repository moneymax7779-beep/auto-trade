package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** follower-risk v18 (user 22 Nov 2026): v17 with the daily loss limit at 22 % of the day's equity, nothing else. */
class FollowerRiskV18Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v18IsV17WithTheDailyLossAt22PctOfEquity() {
        FollowerRisk v17 = load("v17"), v18 = load("v18");
        assertThat(v18.version()).isEqualTo("follower-risk-v18");
        assertThat(v17.limits().dailyLossPct()).isEqualTo(10.0);
        assertThat(v18.limits().dailyLossPct()).isEqualTo(22.0);
        assertThat(v18.limits().maxPremiumPerTradePct()).isEqualTo(v17.limits().maxPremiumPerTradePct());
        assertThat(v18.limits().maxOpenPremiumPct()).isEqualTo(v17.limits().maxOpenPremiumPct());
        assertThat(v18.limits().brokerFundsBufferPct()).isEqualTo(v17.limits().brokerFundsBufferPct());
        assertThat(v18.fillLiveAllocation()).isEqualTo(v17.fillLiveAllocation());
        assertThat(v18.live()).isEqualTo(v17.live());
        assertThat(v18.stopRiskCountsRealised()).isEqualTo(v17.stopRiskCountsRealised());
        assertThat(v18.maxStopRiskPct()).isEqualTo(v17.maxStopRiskPct());
        assertThat(v18.dailyLossCountsOpen()).isEqualTo(v17.dailyLossCountsOpen());
        assertThat(v18.orders()).isEqualTo(v17.orders());
        assertThat(v18.maxOpenPositions()).isEqualTo(v17.maxOpenPositions());
        assertThat(v18.maxLotsPerUnderlying()).isEqualTo(v17.maxLotsPerUnderlying());
        assertThat(v18.combined()).isEqualTo(v17.combined());
        assertThat(v18.rvolBoostFactor()).isEqualTo(v17.rvolBoostFactor());
    }

    /** With no rupee limit on the account the limit is 22 % of equity; an account's own limit still replaces it. */
    @Test
    void theLimitFollowsTheEquityUnlessTheAccountSetsItsOwn() {
        var policy = new com.autotrade.account.CapitalPolicy(load("v18").limits());
        var mode = com.autotrade.account.TradeMode.LIVE;
        // 3 L equity: 66,000; a day at -65,000 may still enter, -66,000 may not
        assertThat(policy.checkEntry(mode, 300_000, 1_000, 0, -65_000, 1e9, Double.NaN).approved()).isTrue();
        assertThat(policy.checkEntry(mode, 300_000, 1_000, 0, -66_000, 1e9, Double.NaN).approved()).isFalse();
        // 4 L equity: 88,000
        assertThat(policy.checkEntry(mode, 400_000, 1_000, 0, -87_000, 1e9, Double.NaN).approved()).isTrue();
        // 2.5 L equity: 55,000
        assertThat(policy.checkEntry(mode, 250_000, 1_000, 0, -55_000, 1e9, Double.NaN).approved()).isFalse();
        // the account's own rupee limit wins
        assertThat(policy.checkEntry(mode, 400_000, 1_000, 0, -66_000, 1e9, 66_000).approved()).isFalse();
    }
}
