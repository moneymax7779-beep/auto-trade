package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** The opening drive: the 28 Sep NIFTY open (PDL and prior ORL broken in the first minute). */
class OpeningDriveTest {

    private static final Path FILE = Path.of("..", "config", "strategy", "opening-drive.v1.yaml");
    private static final StrategyFactory FACTORY = Strategies.load(FILE);

    private final Strategy strategy = FACTORY.create("NIFTY", Snapshots.SESSION);

    /** 28 Sep NIFTY: opened 23079.75 between the prior ORL / PDL below and the prior ORH / PDH above. */
    private static Snapshots open() {
        return new Snapshots()
                .set("spot", 23070.0)
                .set("structure.dayOpen", 23079.75)
                .set("structure.pdh", 23250.0)
                .set("structure.pdl", 23021.20)
                .set("structure.prevOrHigh", 23116.05)
                .set("structure.prevOrLow", 23033.20)
                .set("structure.prevSessionMinutes", 360);
    }

    private static PositionView holding(double bid) {
        return new PositionView(true, OptionSide.PE, 86, 89.20, null, bid);
    }

    @Test
    void loadsWithTheFullBudgetAsOnePlanLot() {
        assertThat(FACTORY).isInstanceOf(OpeningDriveFactory.class);
        assertThat(FACTORY.version()).isEqualTo("odb-v1");
        assertThat(FACTORY.premiumBudget()).isEqualTo(500_000);
        assertThat(FACTORY.intendedLots()).isEqualTo(1);
        assertThat(FACTORY.premiumStopPct()).isEqualTo(25);
    }

    @Test
    void v2KeepsTheFullBudgetButOnly100000OnExpiryDays() {
        StrategyFactory v2 = Strategies.load(Path.of("..", "config", "strategy", "opening-drive.v2.yaml"));
        assertThat(v2.version()).isEqualTo("odb-v2");
        assertThat(v2.premiumBudget()).isEqualTo(500_000);
        assertThat(v2.expiryDayPremiumBudget()).isEqualTo(100_000);
        assertThat(FACTORY.expiryDayPremiumBudget()).isNaN();                  // v1: ₹5L every day
    }

    @Test
    void buysTheAtmPutWhenTheFirstMinuteClosesBelowPdl() {
        assertThat(strategy.decide(open().at("09:15"), PositionView.FLAT).orders()).isEmpty();   // before the window
        Decision d = strategy.decide(open().set("spot", 22975.60).at("09:16"), PositionView.FLAT);
        assertThat(d.orders()).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.lots()).isEqualTo(1);                  // scaled to ₹5L by the executor
            assertThat(o.strikeOffset()).isZero();
            assertThat(o.premiumStopPct()).isEqualTo(25);
            assertThat(o.reason()).isEqualTo("BREAK_PDL");      // both broken: the stop uses the nearer, PDL
            assertThat(o.stopPoints()).isCloseTo(23021.20 - 22975.60, org.assertj.core.api.Assertions.within(1e-6));
        });
        assertThat(d.pe().conditions()).containsEntry("pdl_broken", true).containsEntry("prior_orl_broken", true);
        assertThat(d.pe().stage()).isEqualTo(Stage.CONFIRMED);
    }

    @Test
    void aGapThroughTheLevelIsNotABreak() {
        Snapshots gap = open().set("structure.dayOpen", 23000.0);             // opened below PDL and prior ORL
        assertThat(strategy.decide(gap.set("spot", 22975.60).at("09:16"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void noTradeWithoutACompletePreviousSessionOrOutsideTheWindow() {
        Strategy incomplete = FACTORY.create("NIFTY", Snapshots.SESSION);
        assertThat(incomplete.decide(open().set("structure.prevSessionMinutes", 214).set("spot", 22975.60).at("09:16"),
                PositionView.FLAT).orders()).isEmpty();
        strategy.decide(open().at("09:16"), PositionView.FLAT);
        assertThat(strategy.decide(open().set("spot", 22975.60).at("09:26"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void anUpBreakOfThePriorOpeningRangeHighBuysTheCall() {
        Decision d = strategy.decide(open().set("spot", 23120.0).at("09:17"), PositionView.FLAT);
        assertThat(d.orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("BREAK_PRIOR_ORH");
        });
    }

    @Test
    void exitsWhenTheIndexIsBackAboveTheBrokenLevel() {
        strategy.decide(open().set("spot", 22975.60).at("09:16"), PositionView.FLAT);
        assertThat(strategy.decide(open().set("spot", 22990.0).at("09:17"), holding(95)).orders()).isEmpty();
        Decision d = strategy.decide(open().set("spot", 23021.20).at("09:18"), holding(80));
        assertThat(d.orders()).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderIntent.Action.EXIT);
            assertThat(o.reason()).isEqualTo("STRUCTURE_PDL");
        });
        // sent once; flat afterwards and the day is done, even with the level broken again
        assertThat(strategy.decide(open().set("spot", 23025.0).at("09:19"), holding(80)).orders()).isEmpty();
        assertThat(strategy.decide(open().set("spot", 22960.0).at("09:20"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void trailsOnceThePremiumHasRunAndExitsAtTen() {
        strategy.decide(open().set("spot", 22975.60).at("09:16"), PositionView.FLAT);
        assertThat(strategy.decide(open().set("spot", 22940.0).at("09:17"), holding(100)).orders()).isEmpty();
        assertThat(strategy.decide(open().set("spot", 22920.0).at("09:18"), holding(112)).orders()).isEmpty(); // best 112 ≥ 1.2 × 89.2
        assertThat(strategy.decide(open().set("spot", 22930.0).at("09:19"), holding(90)).orders()).isEmpty();  // 90 > 0.8 × 112
        Decision trail = strategy.decide(open().set("spot", 22935.0).at("09:20"), holding(89.5));
        assertThat(trail.orders()).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("TRAIL"));

        Strategy other = FACTORY.create("NIFTY", Snapshots.SESSION);
        other.decide(open().set("spot", 22975.60).at("09:16"), PositionView.FLAT);
        assertThat(other.decide(open().set("spot", 22900.0).at("09:59"), holding(140)).orders()).isEmpty();
        assertThat(other.decide(open().set("spot", 22900.0).at("10:00"), holding(141.75)).orders())
                .singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("TIME_10:00"));
    }

    @Test
    void aRefusedEntryIsTriedOnceMore() {
        assertThat(strategy.decide(open().set("spot", 22975.60).at("09:16"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(strategy.decide(open().set("spot", 22970.0).at("09:17"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(strategy.decide(open().set("spot", 22965.0).at("09:18"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void v3TradesAgainOnlyAfterAFreshBreakAndV2TradesOnce() {
        Strategy v3 = Strategies.load(Path.of("..", "config", "strategy", "opening-drive.v3.yaml")).create("NIFTY", Snapshots.SESSION);
        Strategy v2 = Strategies.load(Path.of("..", "config", "strategy", "opening-drive.v2.yaml")).create("NIFTY", Snapshots.SESSION);
        for (Strategy s : java.util.List.of(v2, v3)) {
            assertThat(s.decide(open().set("spot", 22975.60).at("09:16"), PositionView.FLAT).orders()).hasSize(1);
            s.decide(open().set("spot", 22960.0).at("09:17"), holding(80));                   // held, then stopped out
        }
        // flat again, still below PDL: no new entry for either (v3 waits for a fresh break)
        assertThat(v2.decide(open().set("spot", 22950.0).at("09:18"), PositionView.FLAT).orders()).isEmpty();
        assertThat(v3.decide(open().set("spot", 22950.0).at("09:18"), PositionView.FLAT).orders()).isEmpty();
        // back above both put levels: re-armed, no order
        assertThat(v3.decide(open().set("spot", 23040.0).at("09:19"), PositionView.FLAT).orders()).isEmpty();
        assertThat(v2.decide(open().set("spot", 23040.0).at("09:19"), PositionView.FLAT).orders()).isEmpty();
        // broken again inside the window: v3 re-enters, v2 has had its trade
        assertThat(v3.decide(open().set("spot", 23010.0).at("09:20"), PositionView.FLAT).orders())
                .singleElement().satisfies(o -> assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER));
        assertThat(v2.decide(open().set("spot", 23010.0).at("09:20"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void v3ReentersAtOnceWhenTheStructureStopWasTheReturnThroughTheLevel() {
        Strategy v3 = Strategies.load(Path.of("..", "config", "strategy", "opening-drive.v3.yaml")).create("NIFTY", Snapshots.SESSION);
        assertThat(v3.decide(open().set("spot", 22975.60).at("09:16"), PositionView.FLAT).orders()).hasSize(1);
        // held; spot back above PDL and the prior ORL: the structure stop exits (30 Sep 09:23 pattern)
        assertThat(v3.decide(open().set("spot", 23040.0).at("09:17"), holding(80)).orders())
                .singleElement().satisfies(o -> assertThat(o.action()).isEqualTo(OrderIntent.Action.EXIT));
        // flat, and broken again in the next minute: the return happened while held, so it trades again
        assertThat(v3.decide(open().set("spot", 23010.0).at("09:18"), PositionView.FLAT).orders())
                .singleElement().satisfies(o -> assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER));
    }
}
