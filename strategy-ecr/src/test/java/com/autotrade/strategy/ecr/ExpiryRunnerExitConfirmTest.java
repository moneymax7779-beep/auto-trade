package com.autotrade.strategy.ecr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** ecr-v8h: an expiry-runner exit needs its condition on 2 consecutive snapshots; earlier files keep 1. */
class ExpiryRunnerExitConfirmTest {

    private static EcrConfig load(String file) {
        return EcrConfig.from(ThresholdConfig.load(Path.of("..", "config", "strategy", file)));
    }

    @Test
    void v8hWaitsForTwoSnapshotsAndV8KeepsOne() {
        assertThat(load("early-confirm-runner.v8h.yaml").expiryRunnerExitConfirm()).isEqualTo(2);
        assertThat(load("early-confirm-runner.v8.yaml").expiryRunnerExitConfirm()).isEqualTo(1);
        assertThat(load("early-confirm-runner.v4.yaml").expiryRunnerExitConfirm()).isEqualTo(1);
    }
}
