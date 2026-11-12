package com.autotrade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * follower-risk v12 (A-378, the PAPER forward test of A-377's X1 flag) is v11 with only sizing.conviction_rvol_full: an
 * entry at futures RVOL >= 2.5 is taken at full size whatever the size step. Earlier files leave it off.
 */
class FollowerRiskV12Test {

    private static FollowerRisk load(String v) {
        return FollowerRisk.load(Path.of("..", "config", "risk", "follower-risk." + v + ".yaml"));
    }

    @Test
    void v12AddsOnlyTheFullSizeRvolThreshold() {
        FollowerRisk v11 = load("v11"), v12 = load("v12");
        assertThat(v12.version()).isEqualTo("follower-risk-v12");
        assertThat(v12.convictionRvolFull()).isEqualTo(2.5);
        assertThat(v12.fullSizeAt(2.5)).isTrue();
        assertThat(v12.fullSizeAt(2.49)).isFalse();
        assertThat(v12.fullSizeAt(Double.NaN)).as("no RVOL published").isFalse();
        assertThat(v11.convictionRvolFull()).isNaN();
        assertThat(v11.fullSizeAt(10)).as("off before v12").isFalse();

        assertThat(v12.limits()).isEqualTo(v11.limits());
        assertThat(v12.maxOpenPositions()).isEqualTo(v11.maxOpenPositions());
        assertThat(v12.maxLotsPerUnderlying()).isEqualTo(v11.maxLotsPerUnderlying());
        assertThat(v12.rvolBoostThreshold()).isEqualTo(v11.rvolBoostThreshold());
        assertThat(v12.rvolBoostFactor()).isEqualTo(v11.rvolBoostFactor());
        assertThat(v12.adverseBps()).isEqualTo(v11.adverseBps());
        assertThat(v12.orders()).isEqualTo(v11.orders());
        assertThat(v12.combined()).isEqualTo(v11.combined());
        assertThat(v12.live()).isEqualTo(v11.live());
        assertThat(v12.dailyLossCountsOpen()).isEqualTo(v11.dailyLossCountsOpen());
    }

    @Test
    void aThresholdOfZeroOrBelowIsRefused(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String v12 = java.nio.file.Files.readString(Path.of("..", "config", "risk", "follower-risk.v12.yaml"));
        for (String bad : new String[] {"0", "-1.5"}) {
            Path file = java.nio.file.Files.createDirectories(dir.resolve("bad" + bad)).resolve("follower-risk.v12.yaml");
            java.nio.file.Files.writeString(file, v12.replace("conviction_rvol_full: 2.5", "conviction_rvol_full: " + bad));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> FollowerRisk.load(file))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conviction_rvol_full");
        }
    }
}
