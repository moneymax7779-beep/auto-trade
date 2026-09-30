package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** paper-risk v5: an entry buys no more premium than its own stop can lose within Rs 10,000. */
class PerTradeLossCapTest {

    private static RiskLimits load(String file) {
        return RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file)));
    }

    @Test
    void v5CapsPremiumByTheStopAndV4DoesNot() {
        RiskLimits v5 = load("paper-risk.v5.yaml");
        assertThat(v5.maxLossPerTrade()).isEqualTo(10_000);
        assertThat(v5.premiumCapForStop(20)).isCloseTo(50_000, within(1e-6));     // straddle
        assertThat(v5.premiumCapForStop(25)).isCloseTo(40_000, within(1e-6));     // ECR, opening drive
        assertThat(v5.premiumCapForStop(30)).isCloseTo(33_333.33, within(0.01));  // EGB
        assertThat(v5.premiumCapForStop(Double.NaN)).isInfinite();                // no stop: no cap from this rule
        RiskLimits v4 = load("paper-risk.v4.yaml");
        assertThat(v4.maxLossPerTrade()).isNaN();
        assertThat(v4.premiumCapForStop(20)).isInfinite();
    }
}
