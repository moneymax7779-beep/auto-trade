package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** paper-risk v6: a single-leg entry is sized so its first stop (structure via delta, or premium) loses Rs 10,000. */
class DeltaRiskSizingTest {

    private static RiskLimits load(String file) {
        return RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file)));
    }

    @Test
    void v6UsesTheNearerOfTheStructureAndPremiumStops() {
        RiskLimits v6 = load("paper-risk.v6.yaml");
        assertThat(v6.deltaRiskPerTrade()).isEqualTo(10_000);
        assertThat(v6.maxLossPerTrade()).isNaN();                               // v5's premium cap is not part of v6
        // 30 Sep opening drive: ask 345, stop 25 %, delta 0.50, level 27 points away -> 13.5 a unit at the level
        assertThat(v6.premiumCapForRisk(25, 345, 0.50, 27)).isCloseTo(10_000 / 13.5 * 345, within(1e-6));
        // a far structure stop: the premium stop (86.25 a unit) comes first -> Rs 40,000 of premium
        assertThat(v6.premiumCapForRisk(25, 345, 0.50, 400)).isCloseTo(40_000, within(1e-6));
        // no structure distance or no delta: the premium stop alone
        assertThat(v6.premiumCapForRisk(25, 345, 0.50, Double.NaN)).isCloseTo(40_000, within(1e-6));
        assertThat(v6.premiumCapForRisk(25, 345, Double.NaN, 27)).isCloseTo(40_000, within(1e-6));
        assertThat(v6.premiumCapForRisk(25, 345, -0.45, 20)).isCloseTo(10_000 / 9.0 * 345, within(1e-6));   // puts
    }

    @Test
    void v4AndV5DoNotSizeByDelta() {
        assertThat(load("paper-risk.v4.yaml").premiumCapForRisk(25, 345, 0.5, 27)).isInfinite();
        assertThat(load("paper-risk.v5.yaml").premiumCapForRisk(25, 345, 0.5, 27)).isInfinite();
    }
}
