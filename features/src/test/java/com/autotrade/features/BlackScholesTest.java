package com.autotrade.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.autotrade.features.greeks.BlackScholes;

class BlackScholesTest {

    @Test
    void matchesTextbookValues() {
        // Hull: S=100, K=100, T=1, r=5%, sigma=20%
        BlackScholes.Greeks call = BlackScholes.greeks(true, 100, 100, 1, 0.05, 0.2);
        BlackScholes.Greeks put = BlackScholes.greeks(false, 100, 100, 1, 0.05, 0.2);

        assertThat(call.price()).isCloseTo(10.4506, within(1e-3));
        assertThat(put.price()).isCloseTo(5.5735, within(1e-3));
        assertThat(call.delta()).isCloseTo(0.6368, within(1e-3));
        assertThat(put.delta()).isCloseTo(-0.3632, within(1e-3));
        assertThat(call.gamma()).isCloseTo(0.01876, within(1e-4));
        assertThat(call.vega()).isCloseTo(0.3752, within(1e-3)); // per vol point
    }

    @Test
    void impliedVolRoundTrips() {
        double years = 4.0 / 365;
        double premium = BlackScholes.price(true, 23114.7, 23100, years, 0.065, 0.1043);

        assertThat(BlackScholes.impliedVol(true, premium, 23114.7, 23100, years, 0.065)).isCloseTo(0.1043, within(1e-5));
    }

    @Test
    void impliedVolIsNaNBelowIntrinsic() {
        assertThat(BlackScholes.impliedVol(true, 10, 23200, 23100, 0.01, 0.065)).isNaN();
    }
}
