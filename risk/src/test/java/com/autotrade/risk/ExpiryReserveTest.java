package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** Risk v14 (A-084): the expiry-day reserve for the non-expiring index. */
class ExpiryReserveTest {

    private static RiskLimits load(String file) {
        return RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file)));
    }

    @Test
    void v14KeepsHalfTheCapitalForTheExpiringIndexAndFollowsTheEquity() {
        RiskLimits v14 = load("paper-risk.v14.yaml");
        assertThat(v14.expiryReservePct()).isEqualTo(50);
        assertThat(v14.nonExpiringPremiumCap()).isEqualTo(250_000);
        assertThat(v14.withEquity(1_000_000).nonExpiringPremiumCap()).isEqualTo(500_000);
    }

    @Test
    void v12AndV13HaveNoReserve() {
        assertThat(load("paper-risk.v12.yaml").nonExpiringPremiumCap()).isInfinite();
        assertThat(load("paper-risk.v13.yaml").nonExpiringPremiumCap()).isInfinite();
    }
}
