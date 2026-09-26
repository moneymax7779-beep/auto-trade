package com.autotrade.features.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The gamma-panel formulas against the hand check in docs/CALIBRATION-egb.md. */
class GammaMathTest {

    // 15 Sep 13:00 NIFTY 23300 CE: gamma 0.0033, theta −0.1619 per minute = −233.1 per calendar day
    private static final OptionChainState.Quote ATM = new OptionChainState.Quote(53.875, 0.30, 0.53, 0.0033, 5.0,
            -0.1619 * 1440, 0.28, "FEED");

    @Test
    void breakevenIsTheMoveWhereHalfGammaMoveSquaredPaysTheta() {
        double be = OptionChainState.breakeven(ATM, 5);
        assertThat(be).isCloseTo(Math.sqrt(2 * 0.1619 * 5 / 0.0033), within(1e-9));
        assertThat(be).isCloseTo(22.15, within(0.01));
        // exactly at the breakeven move the gamma P&L is zero
        assertThat(OptionChainState.gammaPnl(ATM, be, 5)).isCloseTo(0, within(1e-9));
        assertThat(OptionChainState.gammaPnl(ATM, 10, 5)).isCloseTo(0.5 * 0.0033 * 100 - 0.1619 * 5, within(1e-9));
        assertThat(OptionChainState.gammaPnl(ATM, 40, 5)).isGreaterThan(0);
    }

    @Test
    void unknownGreeksGiveNaN() {
        OptionChainState.Quote none = new OptionChainState.Quote(50, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, 0.3, "NONE");
        assertThat(OptionChainState.breakeven(none, 5)).isNaN();
        assertThat(OptionChainState.gammaPnl(none, 10, 5)).isNaN();
    }

    @Test
    void regimeThresholdsMustBeThreeAscendingValues() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new com.autotrade.features.config.FeatureExtensions.V6(
                30, 15, 30, 5, java.util.List.of(0.6, 0.8))).hasMessageContaining("three ascending");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new com.autotrade.features.config.FeatureExtensions.V6(
                30, 15, 30, 5, java.util.List.of(0.8, 0.6, 0.9))).hasMessageContaining("three ascending");
        assertThat(new com.autotrade.features.config.FeatureExtensions.V6(30, 15, 30, 5, java.util.List.of(0.633, 0.779,
                0.818)).gammaRegimeThresholds()).hasSize(3);
    }
}
