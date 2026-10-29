package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/** A-251: obc-v1 enters on the day's first OI-burst EMA cross only when it is a continuation of the move from the open. */
class OiBurstCrossTest {

    private static Strategy load() {
        return Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v1.yaml")).create("NIFTY", Snapshots.SESSION);
    }

    private static Snapshots snap(double spot, double dayOpen, double s3, double s1) {
        return new Snapshots().set("spot", spot).set("structure.prevClose", 22700.0).set("structure.dayOpen", dayOpen)
                .set("regime.dteTradingDays", 2).set("options.atmBandShare3m", s3).set("options.atmBandShare1m", s1);
    }

    /** 09:16-09:30 at 22,690 (below the EMA seeded at 22,700), then 09:31 at 22,720 with the given flow. */
    private static java.util.List<com.autotrade.strategy.OrderIntent> cross(Strategy s, double dayOpen, double s3, double s1) {
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22690, dayOpen, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        return s.decide(snap(22720, dayOpen, s3, s1).at("09:31"), PositionView.FLAT).orders();
    }

    @Test
    void anUpCrossWithAPutWritingBurstAboveTheOpenBuysTheCall() {
        assertThat(cross(load(), 22680, 0.07, 0.01)).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("OBC_UP");
        });
    }

    @Test
    void noBurstOrAWrongOneMinuteSignMeansNoTrade() {
        assertThat(cross(load(), 22680, 0.05, 0.01)).as("below the 3-minute threshold").isEmpty();
        assertThat(cross(load(), 22680, 0.07, -0.01)).as("1-minute flow against").isEmpty();
    }

    @Test
    void aFirstEventThatIsAReversalEndsTheDay() {
        Strategy s = load();
        assertThat(cross(s, 22750, 0.07, 0.01)).as("up cross below the open: a reversal").isEmpty();
        for (int m = 572; m <= 590; m++) {                                    // back below the EMA
            s.decide(snap(22650, 22600, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22760, 22600, 0.08, 0.02).at("09:51"), PositionView.FLAT).orders())
                .as("a later continuation is not the day's first event").isEmpty();
    }

    /** A-252 v3: already above the EMA for 15 minutes (no fresh cross), a burst its way enters; v1 does not. */
    @Test
    void v3EntersOnABurstWithoutAFreshCross() {
        for (String file : new String[] {"oi-burst-cross.v1.yaml", "oi-burst-cross.v3.yaml"}) {
            Strategy s = Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22720 + (m - 556), 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            var orders = s.decide(snap(22740, 22680, 0.07, 0.01).at("09:31"), PositionView.FLAT).orders();
            assertThat(orders).as(file).hasSize(file.contains("v3") ? 1 : 0);
        }
    }

    /** A-252 v2: a put's stop is the PDH above the entry plus 0.1 %, so a 6-point poke above the PDH does not stop it. */
    @Test
    void v2PutsItsStopAtThePdhPlusTheBuffer() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v2.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22710, 22600, 0.0, 0.0).set("structure.pdh", 22580.5).set("structure.dayHigh", 22720.0)
                    .at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22530, 22600, -0.07, -0.01).set("structure.pdh", 22580.5).set("structure.dayHigh", 22720.0)
                .at("09:31"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));
        PositionView open = new PositionView(true, OptionSide.PE, 1, 100, null, 90);
        assertThat(s.decide(snap(22586.6, 22600, 0.0, 0.0).set("structure.pdh", 22580.5).at("09:35"), open).orders())
                .as("22,586.6 is below 22,580.5 x 1.001 = 22,603.1").isEmpty();
        assertThat(s.decide(snap(22605, 22600, 0.0, 0.0).set("structure.pdh", 22580.5).at("09:36"), open).orders())
                .singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("STOP"));
    }

    /** A-253 v5: a 5-minute burst at 09:28 is remembered; at 09:31 the close is above the EMA and above the open: a call. */
    @Test
    void v5EntersOnABurstRememberedFromEarlierMinutes() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v5.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            Snapshots snap = snap(22690, 22680, 0.0, 0.0).set("options.atmBandShare5m", m == 568 ? 0.10 : 0.0)
                    .set("structure.dayLow", 22670.0);
            assertThat(s.decide(snap.at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT).orders()).isEmpty();
        }
        assertThat(s.decide(snap(22720, 22680, 0.0, 0.0).set("options.atmBandShare5m", 0.0).set("structure.dayLow", 22670.0)
                .at("09:31"), PositionView.FLAT)
                .orders()).singleElement().satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
    }

    /** A-254 v6: a call's stop is the tighter of the structure stop and entry - 2 x ATR3m (ATR 10: 20 points). */
    @Test
    void v6UsesTheTighterOfTheStructureAndTheAtrStop() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v6.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22720 + (m - 556), 22680, 0.0, 0.0).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                    .at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22740, 22680, 0.07, 0.01).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                .at("09:31"), PositionView.FLAT).orders()).hasSize(1);
        PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 95);
        assertThat(s.decide(snap(22721, 22680, 0.0, 0.0).at("09:32"), open).orders()).as("19 points down: kept").isEmpty();
        assertThat(s.decide(snap(22719, 22680, 0.0, 0.0).at("09:33"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("STOP"));
    }

    /** A-255 v8: entry 22,740 (ATR 10): armed at +20, best close 22,800, exit on a close at or below 22,770. */
    @Test
    void v8TrailsThreeAtrBelowTheBestCloseOnceUpTwoAtr() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v8.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22720 + (m - 556), 22680, 0.0, 0.0).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                    .at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22740, 22680, 0.07, 0.01).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                .at("09:31"), PositionView.FLAT).orders()).hasSize(1);
        PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 130);
        assertThat(s.decide(snap(22800, 22680, 0.0, 0.0).at("09:40"), open).orders()).isEmpty();
        assertThat(s.decide(snap(22771, 22680, 0.0, 0.0).at("09:45"), open).orders()).as("29 below the best").isEmpty();
        assertThat(s.decide(snap(22770, 22680, 0.0, 0.0).at("09:46"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TRAIL_ATR"));
    }

    /**
     * A-258 oba-v1 (open 22,800): an up burst at 09:31 above the EMA but below the open (a reversal) is skipped, not the end
     * of the day; a new up burst above the open at 10:00 enters; after that trade the same burst still on does not
     * re-enter; a new burst does; three entries a day at most. v8, on the same minutes, stops at the 09:31 reversal.
     */
    @Test
    void allDayEntersAgainOnlyOnANewBurstAndSkipsReversals() {
        for (String file : new String[] {"oi-burst-allday.v1.yaml", "oi-burst-cross.v8.yaml"}) {
            Strategy s = Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
            PositionView call = new PositionView(true, OptionSide.CE, 1, 100, null, 110);
            java.util.List<String> entries = new java.util.ArrayList<>();
            for (int m = 556; m <= 610; m++) {
                String t = String.format("%02d:%02d", m / 60, m % 60);
                double spot = m <= 571 ? 22710 + (m - 556) : 22730 + (m - 572) * 3.0;
                double s3 = switch (t) {
                    case "09:31", "10:00", "10:01", "10:02", "10:04", "10:07", "10:10" -> 0.2;
                    default -> 0.0;
                };
                boolean held = t.equals("10:01") || t.equals("10:05") || t.equals("10:08");
                Snapshots snap = snap(spot, 22800, s3, Math.signum(s3) * 0.01).set("structure.dayLow", 22600.0)
                        .set("structure.dayHigh", 22900.0).set("structure.atr3m", 10.0);
                if (!s.decide(snap.at(t), held ? call : PositionView.FLAT).orders().isEmpty() && !held) {
                    entries.add(t);
                }
            }
            assertThat(entries).as(file).isEqualTo(file.contains("allday") ? java.util.List.of("10:00", "10:04", "10:07")
                    : java.util.List.of());
        }
    }

    /**
     * A-266: with the previous close 22,700, an open at 22,800 is a +0.44 % gap: obc-v13 takes no call (with the gap) but
     * still takes a put (against it) on another day; an open at 22,750 (+0.22 %) is under 0.4 % and the call is taken.
     */
    @Test
    void aBigOpeningGapBlocksEntriesInItsDirectionOnly() {
        java.util.function.BiFunction<Double, Double, Snapshots> at = (spot, s3) -> snap(spot, 22800, s3, Math.signum(s3) * 0.01)
                .set("structure.dayLow", 22600.0).set("structure.dayHigh", 22950.0).set("structure.atr3m", 10.0);
        Strategy up = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v13.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            up.decide(at.apply(22790.0 + (m - 556), 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(up.decide(at.apply(22830.0, 0.2).at("09:31"), PositionView.FLAT).orders()).as("call with a +0.44 % gap").isEmpty();

        Strategy down = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v13.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            down.decide(at.apply(22810.0 - (m - 556), 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(down.decide(at.apply(22770.0, -0.2).at("09:31"), PositionView.FLAT).orders()).as("put against the gap").hasSize(1);

        Strategy small = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v13.yaml")).create("NIFTY", Snapshots.SESSION);
        java.util.function.BiFunction<Double, Double, Snapshots> at2 = (spot, s3) -> snap(spot, 22750, s3, Math.signum(s3) * 0.01)
                .set("structure.dayLow", 22600.0).set("structure.dayHigh", 22950.0).set("structure.atr3m", 10.0);
        for (int m = 556; m <= 570; m++) {
            small.decide(at2.apply(22740.0 + (m - 556), 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(small.decide(at2.apply(22780.0, 0.2).at("09:31"), PositionView.FLAT).orders()).as("+0.22 % gap").hasSize(1);
    }

    @Test
    void theFileLoadsForBothIndices() {
        assertThat(Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v1.yaml")).create("SENSEX",
                Snapshots.SESSION)).isNotNull();
        assertThat(Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v11.yaml")).create("SENSEX",
                Snapshots.SESSION)).as("A-260 obc-v11").isNotNull();
        assertThat(Map.of()).isEmpty();
    }
}
