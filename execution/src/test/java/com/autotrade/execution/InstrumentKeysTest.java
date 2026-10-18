package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InstrumentKeysTest {

    @Test
    void replayKeysFollowFromTheTokenAndLiveKeysStay() {
        assertThat(InstrumentKeys.normalize("ZT|3000056994", 3000056994L)).isEqualTo("NSE_FO|56994");
        assertThat(InstrumentKeys.normalize("ZT|6001125704", 6001125704L)).isEqualTo("BSE_FO|1125704");
        assertThat(InstrumentKeys.normalize("NSE_FO|47168", 3000047168L)).isEqualTo("NSE_FO|47168");
        assertThat(InstrumentKeys.normalize("NSE_INDEX|Nifty 50", 7000026000L)).isEqualTo("NSE_INDEX|Nifty 50");
        assertThat(InstrumentKeys.normalize(null, 42)).isNull();
    }
}
