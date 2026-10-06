package com.autotrade.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

class CostModelPlusTest {

    private static final CostModel V2 = CostModel.from(
            ThresholdConfig.load(Path.of("..", "config", "costs", "india-index-options-costs.v2.yaml")));

    @Test
    void plusBrokerageAppliesFromSeptember2026AndTheOldRateBefore() {
        CostBreakdown plus = V2.costs(LocalDate.of(2026, 9, 25), "NSE", Side.BUY, 100, 65);
        CostBreakdown basic = V2.costs(LocalDate.of(2026, 8, 1), "NSE", Side.BUY, 100, 65);
        assertThat(plus.brokerage()).isEqualTo(30.0);
        assertThat(basic.brokerage()).isEqualTo(20.0);
        // only the brokerage (and the GST on it) differs
        assertThat(plus.total() - basic.total()).isCloseTo(10 * 1.18, org.assertj.core.api.Assertions.within(1e-9));
    }
}
