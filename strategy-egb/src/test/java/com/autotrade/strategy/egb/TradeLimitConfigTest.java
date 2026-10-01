package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** User's decision 2026-10-01: ebs-v2 / egb-v4 / odb-v3 have no trade-count limit; earlier files keep one. */
class TradeLimitConfigTest {

    private static ThresholdConfig file(String name) {
        return ThresholdConfig.load(Path.of("..", "config", "strategy", name));
    }

    @Test
    void newVersionsHaveNoLimitAndEarlierOnesKeepOne() {
        assertThat(StraddleConfig.from(file("expiry-breakout-straddle.v2.yaml")).maxTrades()).isZero();
        assertThat(StraddleConfig.from(file("expiry-breakout-straddle.v1.yaml")).maxTrades()).isEqualTo(1);
        assertThat(EgbConfig.from(file("expiry-gamma-breakout.v4.yaml")).maxCampaignsPerSide()).isZero();
        assertThat(EgbConfig.from(file("expiry-gamma-breakout.v3.yaml")).maxCampaignsPerSide()).isEqualTo(1);
        assertThat(OpeningDriveConfig.from(file("opening-drive.v3.yaml")).maxTrades()).isZero();
        assertThat(OpeningDriveConfig.from(file("opening-drive.v2.yaml")).maxTrades()).isEqualTo(1);
    }
}
