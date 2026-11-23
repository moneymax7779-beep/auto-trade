package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * follower-risk v14 (user 19 Nov 2026, account-management review step 2): v11 with the stop-risk cap
 * (capital.max_stop_risk_pct_of_daily_loss 100) and nothing else changed; v11 has the cap off.
 */
class FollowerRiskV14Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v14IsV11WithTheStopRiskCap() {
        FollowerRisk v11 = load("v11"), v14 = load("v14");
        assertThat(v14.version()).isEqualTo("follower-risk-v14");
        assertThat(v11.capsStopRisk()).isFalse();
        assertThat(v14.capsStopRisk()).isTrue();
        assertThat(v14.maxStopRiskPct()).isEqualTo(100);
        assertThat(v14.limits()).isEqualTo(v11.limits());
        assertThat(v14.dailyLossCountsOpen()).isTrue();
        assertThat(v14.live()).isEqualTo(v11.live());
        assertThat(v14.orders()).isEqualTo(v11.orders());
        assertThat(v14.maxOpenPositions()).isEqualTo(v11.maxOpenPositions());
        assertThat(v14.maxLotsPerUnderlying()).isEqualTo(v11.maxLotsPerUnderlying());
        assertThat(v14.combined()).isEqualTo(v11.combined());
        assertThat(v14.convictionRvolFull()).isNaN();
    }

    @Test
    void aZeroOrNegativeCapIsATypoNotAnOff() {
        FollowerRisk v14 = load("v14");
        assertThatThrownBy(() -> new FollowerRisk(v14.version(), v14.limits(), 3, 50, Double.NaN, 1, 0, v14.orders(), null,
                v14.live(), true, Double.NaN, 0, false, false, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
