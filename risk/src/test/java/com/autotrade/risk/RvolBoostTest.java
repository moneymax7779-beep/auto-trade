package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** risk v17 (A-106): 1.5 x the budget at futures RVOL >= 1.5; v12 never boosts; unknown RVOL never boosts. */
class RvolBoostTest {

    private static RiskLimits load(String file) {
        return RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file)));
    }

    @Test
    void v17BoostsFromRvolOneAndAHalfAndV12Never() {
        RiskLimits v17 = load("paper-risk.v17.yaml"), v12 = load("paper-risk.v12.yaml");
        assertThat(v17.rvolBoost(1.5)).isEqualTo(1.5);
        assertThat(v17.rvolBoost(3.2)).isEqualTo(1.5);
        assertThat(v17.rvolBoost(1.49)).isEqualTo(1.0);
        assertThat(v17.rvolBoost(Double.NaN)).isEqualTo(1.0);
        assertThat(v12.rvolBoost(5.0)).isEqualTo(1.0);
        assertThat(v17.withEquity(1_000_000).rvolBoost(2.0)).isEqualTo(1.5);   // survives the equity rescale
    }
}
