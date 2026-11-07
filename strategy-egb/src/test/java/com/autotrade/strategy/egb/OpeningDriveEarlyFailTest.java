package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/**
 * A-249: odm-v17's early-failure exit. A 09:21 put on a 0.3 % drive (open 22,800, low 22,725, stop = the day high
 * 22,800, R = 69); 15 minutes later it exits unless the index went at least 0.25 R (17.25 points) its way meanwhile.
 */
class OpeningDriveEarlyFailTest {

    private static Strategy load(String file) {
        return Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
    }

    private static Snapshots snap(double spot) {
        return new Snapshots().set("spot", spot).set("structure.dayOpen", 22800.0).set("structure.dayHigh", 22800.0)
                .set("structure.dayLow", Math.min(22725.0, spot)).set("regime.dteTradingDays", 2)
                .set("breadth.moverBreadth", -60.0);
    }

    /** Enters at 09:21 at 22,731, then the given index path minute by minute from 09:22; returns the exit reasons. */
    private static List<String> run(String file, double... path) {
        Strategy s = load(file);
        assertThat(s.decide(snap(22731).at("09:21"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));
        PositionView open = new PositionView(true, OptionSide.PE, 1, 100, null, 100);
        List<String> exits = new java.util.ArrayList<>();
        for (int i = 0; i < path.length; i++) {
            int m = 9 * 60 + 22 + i;
            s.decide(snap(path[i]).at(String.format("%02d:%02d", m / 60, m % 60)), open).orders().stream()
                    .map(OrderIntent::reason).forEach(exits::add);
        }
        return exits;
    }

    private static double[] flat(int minutes, double at) {
        double[] p = new double[minutes];
        java.util.Arrays.fill(p, at);
        return p;
    }

    @Test
    void aDriveThatGoesNowhereExitsAtFifteenMinutes() {
        assertThat(run("opening-drive-momentum.v17.yaml", flat(20, 22728))).containsExactly("EARLY_FAIL");
        assertThat(run("opening-drive-momentum.v4.yaml", flat(20, 22728))).as("v4 holds").isEmpty();
    }

    @Test
    void aDriveThatWorkedAQuarterRIsKeptEvenIfItCameBack() {
        double[] path = flat(20, 22728);
        path[5] = 22712;                                                   // 19 points its way at 09:27: 0.28 R
        assertThat(run("opening-drive-momentum.v17.yaml", path)).isEmpty();
    }
}
