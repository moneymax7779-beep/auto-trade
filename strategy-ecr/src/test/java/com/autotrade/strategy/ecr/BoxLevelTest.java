package com.autotrade.strategy.ecr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategy;

/** ecr-v9: a close beyond a compressed trailing box also counts as a level break; v7 ignores it. */
class BoxLevelTest {

    private static Strategy load(String file) {
        return new EarlyConfirmRunnerFactory(ThresholdConfig.load(Path.of("..", "config", "strategy", file)))
                .create("NIFTY", Snapshots.SESSION);
    }

    /** 28 Sep-like: box 23010-23050 (4 ATR), the last 1-minute close 23005 below it, spot 23004. */
    private static Snapshots belowTheBox() {
        return new Snapshots().set("spot", 23004.0).set("structure.atr3m", 10.0).set("structure.boxHigh", 23050.0)
                .set("structure.boxLow", 23010.0).set("structure.lastMinuteClose", 23005.0);
    }

    @Test
    void v9CountsTheBoxBreakForSixMinutesAndV7DoesNot() {
        Strategy v9 = load("early-confirm-runner.v9.yaml");
        Decision d = v9.decide(belowTheBox().at("14:18"), PositionView.FLAT);
        assertThat(d.pe().conditions()).containsEntry("box_compressed", true).containsEntry("box_broke", true)
                .containsEntry("broke_level", true);
        assertThat(d.ce().conditions()).containsEntry("box_broke", false).containsEntry("broke_level", false);
        // the level is frozen at the break: still broken while spot stays below it, within 6 minutes
        Snapshots later = belowTheBox().set("structure.boxLow", 22990.0).set("structure.lastMinuteClose", 23000.0);
        assertThat(v9.decide(later.at("14:24"), PositionView.FLAT).pe().conditions()).containsEntry("box_broke", true);
        assertThat(v9.decide(later.at("14:25"), PositionView.FLAT).pe().conditions()).containsEntry("box_broke", false);

        Decision v7 = load("early-confirm-runner.v7.yaml").decide(belowTheBox().at("14:18"), PositionView.FLAT);
        assertThat(v7.pe().conditions()).doesNotContainKey("box_broke").containsEntry("broke_level", false);
    }

    @Test
    void aWideBoxIsNotCompressed() {
        Decision d = load("early-confirm-runner.v9.yaml").decide(belowTheBox().set("structure.boxHigh", 23070.0).at("14:18"),
                PositionView.FLAT);                         // 60 points = 6 ATR
        assertThat(d.pe().conditions()).containsEntry("box_compressed", false).containsEntry("box_broke", false);
    }
}
