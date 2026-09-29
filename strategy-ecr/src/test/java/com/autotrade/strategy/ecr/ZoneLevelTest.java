package com.autotrade.strategy.ecr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategy;

/** ecr-v10: a close beyond a tested zone is a level break; one touch is not a tested zone. */
class ZoneLevelTest {

    private static final ThresholdConfig V10 = ThresholdConfig.load(Path.of("..", "config", "strategy", "early-confirm-runner.v10.yaml"));

    private static Strategy v10() {
        return new EarlyConfirmRunnerFactory(V10).create("NIFTY", Snapshots.SESSION);
    }

    /** 28 Sep-like: zone low 22807.65 touched 3 times, the last 1-minute close 22804.15 below it. */
    private static Snapshots belowTheZone(int touches) {
        return new Snapshots().set("spot", 22804.0).set("structure.atr3m", 11.7).set("structure.zoneLow", 22807.65)
                .set("structure.zoneLowTouches", touches).set("structure.zoneHigh", 22865.4).set("structure.zoneHighTouches", 1)
                .set("structure.lastMinuteClose", 22804.15);
    }

    @Test
    void aCloseBelowATestedZoneIsABreak() {
        assertThat(EcrConfig.from(V10).maxCampaignsPerSide()).isZero();
        Decision d = v10().decide(belowTheZone(3).at("14:18"), PositionView.FLAT);
        assertThat(d.pe().conditions()).containsEntry("zone_tested", true).containsEntry("zone_broke", true)
                .containsEntry("broke_level", true);
        assertThat(d.ce().conditions()).containsEntry("zone_tested", false).containsEntry("zone_broke", false);
    }

    @Test
    void aRetestThatHoldsOrAnUntestedLowIsNot() {
        // 13:08-like: the close stays above the zone low
        Decision retest = v10().decide(belowTheZone(2).set("structure.lastMinuteClose", 22812.2).set("spot", 22812.2).at("13:09"),
                PositionView.FLAT);
        assertThat(retest.pe().conditions()).containsEntry("zone_broke", false);
        Decision once = v10().decide(belowTheZone(1).at("14:18"), PositionView.FLAT);
        assertThat(once.pe().conditions()).containsEntry("zone_tested", false).containsEntry("zone_broke", false);
    }
}
