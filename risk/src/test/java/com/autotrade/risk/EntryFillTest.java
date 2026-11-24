package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

/** signal-source v3 (24 Nov 2026): {@code orders.entry_fill}. */
class EntryFillTest {

    private static RiskLimits load(String file) {
        return RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file)));
    }

    @Test
    void onlySignalSourceV3FillsEntriesAtTheDecision() {
        RiskLimits v3 = load("signal-source.v3.yaml");
        assertThat(v3.entryFillsAtDecision()).isTrue();
        assertThat(v3.entrySliceDepthPct()).isZero();
        assertThat(v3.maxParticipationPct()).as("the size cap stays").isEqualTo(2);
        assertThat(v3.withEquity(1e6).entryFillsAtDecision()).as("kept when the capital follows equity").isTrue();
        assertThat(load("signal-source.v2.yaml").entryFillsAtDecision()).isFalse();
        assertThat(load("paper-risk.v44.yaml").entryFillsAtDecision()).isFalse();
    }

    @Test
    void anUnknownEntryFillIsRefused() {
        assertThat(RiskLimits.entryFill("depth")).isFalse();
        assertThatThrownBy(() -> RiskLimits.entryFill("instant")).isInstanceOf(IllegalArgumentException.class);
    }
}
