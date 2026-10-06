package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.risk.RiskLimits;

class EquityLedgerTest {

    @Test
    void equityIsStartingCapitalPlusRealisedNetAndNeverNegative() {
        RiskLimits v8 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v8.yaml")));
        assertThat(EquityLedger.apply(v8, 350_000).capital()).isEqualTo(850_000);
        assertThat(EquityLedger.apply(v8, -105_361).capital()).isEqualTo(394_639);
        assertThat(EquityLedger.apply(v8, -900_000).capital()).isEqualTo(0);
        RiskLimits v4 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v4.yaml")));
        assertThat(EquityLedger.apply(v4, 350_000)).as("fixed mode ignores the ledger").isSameAs(v4);
    }
}
