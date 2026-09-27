package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** The expiry gamma breakout state machine, scenario by scenario (docs/CALIBRATION-egb.md). */
class ExpiryGammaStrategyTest {

    private static final Path FILE = Path.of("..", "config", "strategy", "expiry-gamma-breakout.v1.yaml");
    private static final ThresholdConfig CONFIG = ThresholdConfig.load(FILE);

    private final Strategy strategy = new ExpiryGammaFactory(CONFIG).create("NIFTY", Snapshots.SESSION);

    private static PositionView ce(int lots) {
        return new PositionView(true, OptionSide.CE, lots, 50, null);
    }

    @Test
    void loadsThroughTheGenericLoaderWithItsFeatureRequirements() {
        StrategyFactory factory = Strategies.load(FILE);
        assertThat(factory).isInstanceOf(ExpiryGammaFactory.class);
        assertThat(factory.id()).isEqualTo("expiry-gamma-breakout");
        assertThat(factory.requiredFeatureSections()).contains("compression", "gamma");
        assertThat(factory.premiumStopPct()).isEqualTo(30);
        assertThat(EgbConfig.from(CONFIG).tranches()).containsExactly(1, 2, 1);
    }

    @Test
    void v2DropsTheSwingStructureFromTheStructureBias() {
        Snapshots noHigherLows = Snapshots.bullishCoil().set("structure.higherLows", false);
        Decision v1 = strategy.decide(noHigherLows.at("12:41"), PositionView.FLAT);
        assertThat(v1.ce().conditions()).containsEntry("trend_swings", false).containsEntry("structure_bias", false);
        assertThat(v1.orders()).isEmpty();

        ThresholdConfig v2Config = ThresholdConfig.load(Path.of("..", "config", "strategy", "expiry-gamma-breakout.v2.yaml"));
        assertThat(v2Config.version()).isEqualTo("egb-v2");
        Strategy v2 = new ExpiryGammaFactory(v2Config).create("NIFTY", Snapshots.SESSION);
        Decision early = v2.decide(noHigherLows.at("12:41"), PositionView.FLAT);
        assertThat(early.ce().conditions()).containsEntry("trend_swings", false).containsEntry("structure_bias", true);
        assertThat(early.orders()).singleElement().extracting(OrderIntent::stage).isEqualTo(Stage.EARLY_ENTRY);
    }

    @Test
    void v3IsV2WithAPremiumBudget() {
        StrategyFactory v2 = Strategies.load(Path.of("..", "config", "strategy", "expiry-gamma-breakout.v2.yaml"));
        StrategyFactory v3 = Strategies.load(Path.of("..", "config", "strategy", "expiry-gamma-breakout.v3.yaml"));
        assertThat(v2.premiumBudget()).isNaN();
        assertThat(v3.premiumBudget()).isEqualTo(500_000);
        assertThat(v3.intendedLots()).isEqualTo(4);
        assertThat(v3.version()).isEqualTo("egb-v3");
    }

    @Test
    void doesNothingOffExpiryDay() {
        Decision decision = strategy.decide(Snapshots.bullishCoil().set("regime.dteTradingDays", 2).at("12:40"),
                PositionView.FLAT);
        assertThat(decision.orders()).isEmpty();
        assertThat(decision.state().regime()).isEqualTo("NOT_EXPIRY");
    }

    @Test
    void takesTheEarlyProbeOnlyAfterCompressionAndWithAcceleration() {
        // no compression seen yet today: armed conditions hold but the design wants COMPRESSION first
        Decision noCompression = strategy.decide(Snapshots.bullishCoil().set("compression.rangeVsSession", 1.2)
                .at("12:40"), PositionView.FLAT);
        assertThat(noCompression.orders()).isEmpty();
        assertThat(noCompression.ce().stage()).isEqualTo(Stage.WATCH);

        Decision early = strategy.decide(Snapshots.bullishCoil().at("12:41"), PositionView.FLAT);
        assertThat(early.ce().conditions()).containsEntry("compressed", true).containsEntry("structure_bias", true);
        assertThat(early.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(order.side()).isEqualTo(OptionSide.CE);
            assertThat(order.lots()).isEqualTo(1);                       // 25% of 4 lots
            assertThat(order.stage()).isEqualTo(Stage.EARLY_ENTRY);
            assertThat(order.premiumStopPct()).isEqualTo(30);
            assertThat(order.strikeOffset()).isZero();                   // ATM delta 0.48 closer to 0.55 than ITM 0.66
        });
    }

    @Test
    void lowAccelerationArmsWithoutEntering() {
        Decision decision = strategy.decide(Snapshots.bullishCoil().set("futures.momentum1m", -1.0)
                .set("futures.acceleration1m", -1.0).set("gamma.gammaPnlCe", -0.2).at("12:41"), PositionView.FLAT);
        assertThat(decision.ce().stage()).isEqualTo(Stage.ARMED);
        assertThat(decision.ce().earlyScore()).isEqualTo(62.5);          // 5 of 8 components
        assertThat(decision.orders()).isEmpty();
    }

    @Test
    void fullLifecycleEarlyConfirmRetestRunnerThenTrail() {
        strategy.decide(Snapshots.bullishCoil().at("12:41"), PositionView.FLAT);
        Decision confirm = strategy.decide(Snapshots.bullishCoil().breakout().at("12:44"), ce(1));
        assertThat(confirm.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.ADD);
            assertThat(order.lots()).isEqualTo(2);                       // 45%
        });
        assertThat(confirm.ce().stage()).isEqualTo(Stage.CONFIRMED);

        Decision retesting = strategy.decide(Snapshots.bullishCoil().breakout().set("spot", 23121.0)
                .set("structure.distOrhAtr", 0.5).set("structure.orhClosesAbove", 2)
                .set("retest.orhState", "RETESTING").set("structure.ema9", 23117.0).at("12:47"), ce(3));
        assertThat(retesting.orders()).isEmpty();
        assertThat(retesting.ce().stage()).isEqualTo(Stage.RETEST);

        Decision runner = strategy.decide(Snapshots.bullishCoil().breakout().set("spot", 23130.0)
                .set("structure.distOrhAtr", 1.4).set("structure.orhClosesAbove", 3)
                .set("retest.orhState", "HELD").set("retest.orhPullbackVolumeRatio", 0.6)
                .set("structure.ema9", 23120.0).at("12:50"), ce(3));
        assertThat(runner.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.ADD);
            assertThat(order.lots()).isEqualTo(1);                       // 30%
            assertThat(order.stage()).isEqualTo(Stage.RUNNER);
        });

        // trail: the last swing low (23124, above the ORH) is broken on a 3-minute close
        Decision trail = strategy.decide(Snapshots.bullishCoil().breakout().set("spot", 23122.0)
                .set("structure.distOrhAtr", 0.6).set("structure.orhClosesAbove", 5)
                .set("retest.orhState", "HELD").set("structure.lastSwingLow", 23124.0)
                .set("structure.lastBarClose", 23122.0).set("structure.ema9", 23118.0).at("13:05"), ce(4));
        assertThat(trail.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("RUNNER_TRAIL");
        assertThat(trail.ce().stage()).isEqualTo(Stage.EXITED);

        assertThat(strategy.decide(Snapshots.bullishCoil().at("13:30"), PositionView.FLAT).orders())
                .as("one campaign per side per day").isEmpty();
    }

    @Test
    void aRefusedEntryIsNotACampaignAndIsRetriedAfterTheCooldown() {
        assertThat(strategy.decide(Snapshots.bullishCoil().at("12:41"), PositionView.FLAT).orders())
                .singleElement().extracting(OrderIntent::action).isEqualTo(OrderIntent.Action.ENTER);
        // the executor refused it (e.g. another strategy holds the underlying's lots): still flat
        Decision refused = strategy.decide(Snapshots.bullishCoil().at("12:42"), PositionView.FLAT);
        assertThat(refused.orders()).as("no retry inside the cooldown").isEmpty();
        assertThat(refused.ce().stage()).isNotEqualTo(Stage.EXITED);
        assertThat(strategy.decide(Snapshots.bullishCoil().at("12:43"), PositionView.FLAT).orders()).isEmpty();
        assertThat(strategy.decide(Snapshots.bullishCoil().at("12:45"), PositionView.FLAT).orders())
                .as("retried after 3 minutes").singleElement()
                .extracting(OrderIntent::stage).isEqualTo(Stage.EARLY_ENTRY);
    }

    @Test
    void goingFlatAfterHoldingEndsTheCampaign() {
        strategy.decide(Snapshots.bullishCoil().at("12:41"), PositionView.FLAT);
        strategy.decide(Snapshots.bullishCoil().at("12:42"), ce(1));
        Decision stopped = strategy.decide(Snapshots.bullishCoil().at("12:43"), PositionView.FLAT);   // resting stop filled
        assertThat(stopped.ce().stage()).isEqualTo(Stage.EXITED);
        assertThat(strategy.decide(Snapshots.bullishCoil().at("12:50"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void aStalledEntryIsCutByTheThetaStop() {
        // ATM call: delta 0.48, gamma 0.0033, theta −0.16/min → 6-minute directional breakeven ≈ 2.0 points
        Snapshots coil = Snapshots.bullishCoil().set("gamma.atmCeGamma", 0.0033).set("gamma.atmCeThetaPerMin", -0.16);
        strategy.decide(coil.at("12:41"), PositionView.FLAT);           // entry at 23115
        Decision stillEarly = strategy.decide(coil.at("12:44"), ce(1));
        assertThat(stillEarly.orders()).isEmpty();
        Decision theta = strategy.decide(coil.set("spot", 23116.0).at("12:47"), ce(1));   // +1 point in 6 minutes
        assertThat(theta.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("THETA_STOP");
        assertThat(new EgbSide(OptionSide.CE, coil.at("12:41")).directionalBreakeven(0, 6))
                .isCloseTo((Math.sqrt(0.48 * 0.48 + 2 * 0.0033 * 0.96) - 0.48) / 0.0033,
                        org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void aFailedRetestExits() {
        strategy.decide(Snapshots.bullishCoil().at("12:41"), PositionView.FLAT);
        strategy.decide(Snapshots.bullishCoil().breakout().at("12:44"), ce(1));
        Decision failed = strategy.decide(Snapshots.bullishCoil().breakout().set("spot", 23150.0)
                .set("structure.distOrhAtr", 3.4).set("retest.orhState", "FAILED").at("12:46"), ce(3));
        assertThat(failed.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("RETEST_FAILED");
    }

    @Test
    void choppyVwapWideSpreadsAndLowPremiumResponseAreRejected() {
        assertThat(new ExpiryGammaFactory(CONFIG).create("NIFTY", Snapshots.SESSION)
                .decide(Snapshots.bullishCoil().set("compression.vwapCrosses", 3).at("12:41"), PositionView.FLAT)
                .orders()).as("chop").isEmpty();
        assertThat(new ExpiryGammaFactory(CONFIG).create("NIFTY", Snapshots.SESSION)
                .decide(Snapshots.bullishCoil().set("gamma.atmCeSpreadPct", 2.0).set("gamma.itmCeSpreadPct", 2.0)
                        .at("12:41"), PositionView.FLAT).orders()).as("spread").isEmpty();
        Strategy fresh = new ExpiryGammaFactory(CONFIG).create("NIFTY", Snapshots.SESSION);
        Decision lagging = fresh.decide(Snapshots.bullishCoil().set("futures.momentum1m", -1.0)
                .breakout().set("futures.momentum1m", 5.0).set("options.premiumResponseCe", 0.3).at("12:44"),
                PositionView.FLAT);
        assertThat(lagging.ce().conditions()).containsEntry("premium_ok", false);
        assertThat(lagging.orders()).isEmpty();
    }

    @Test
    void theOneStrikeInTheMoneyOptionIsChosenWhenItsDeltaIsCloser() {
        Decision decision = strategy.decide(Snapshots.bullishCoil().set("gamma.atmCeDelta", 0.40)
                .set("gamma.itmCeDelta", 0.58).at("12:41"), PositionView.FLAT);
        assertThat(decision.orders()).singleElement().extracting(OrderIntent::strikeOffset).isEqualTo(1);
    }

    @Test
    void everythingClosesByFlatBy() {
        strategy.decide(Snapshots.bullishCoil().at("12:41"), PositionView.FLAT);
        Decision flat = strategy.decide(Snapshots.bullishCoil().at("15:15"), ce(1));
        assertThat(flat.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("FLAT_BY");
    }

    @Test
    void putsMirrorAtTheOpeningRangeLow() {
        Decision decision = strategy.decide(Snapshots.bullishCoil()
                .set("spot", 23031.0).set("structure.distOrhAtr", -8.5).set("structure.distOrlAtr", 0.1)
                .set("structure.vwapSpotProxy", 23050.0).set("structure.ema9", 23036.0).set("structure.ema20", 23040.0)
                .set("structure.ema9Slope", -1.5).set("structure.higherLows", false).set("structure.lowerHighs", true)
                .set("futures.momentum1m", -6.0).set("futures.acceleration1m", -3.0).set("breadth.moverBreadth", -60.0)
                .set("gamma.gammaPnlPe", 0.4).set("futures.oiState", "FRESH_SHORT").at("12:41"), PositionView.FLAT);
        assertThat(decision.pe().stage()).isEqualTo(Stage.EARLY_ENTRY);
        assertThat(decision.orders()).singleElement().satisfies(order -> {
            assertThat(order.side()).isEqualTo(OptionSide.PE);
            assertThat(order.strikeOffset()).isZero();                   // |ATM delta| 0.52 vs |ITM| 0.70
        });
    }

    @Test
    void withoutTheRetestGateTheRunnerNeedsASecondCloseAndANewHigh() {
        ThresholdConfig noRetest = ThresholdConfig.parse("variant", java.nio.file.Files.exists(FILE)
                ? readVariant() : "");
        Strategy variant = new ExpiryGammaFactory(noRetest).create("NIFTY", Snapshots.SESSION);
        variant.decide(Snapshots.bullishCoil().at("12:41"), PositionView.FLAT);
        variant.decide(Snapshots.bullishCoil().breakout().at("12:44"), ce(1));
        Decision runner = variant.decide(Snapshots.bullishCoil().breakout().set("spot", 23135.0)
                .set("structure.distOrhAtr", 1.9).set("structure.orhClosesAbove", 2).set("structure.ema9", 23120.0)
                .at("12:47"), ce(3));
        assertThat(runner.orders()).singleElement().extracting(OrderIntent::stage).isEqualTo(Stage.RUNNER);
    }

    private static String readVariant() {
        try {
            return java.nio.file.Files.readString(FILE).replace("require_retest: true", "require_retest: false");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
