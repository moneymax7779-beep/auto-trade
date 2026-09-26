package com.autotrade.strategy.ecr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategy;

/** Strategy v4: volatility regime, runner gates and the closing-auction mode. */
class EarlyConfirmRunnerV4Test {

    private static final ThresholdConfig FILE =
            ThresholdConfig.load(Path.of("..", "config", "strategy", "early-confirm-runner.v4.yaml"));
    private static final EcrExtensions EXT = EcrConfig.from(FILE).ext();

    private final Strategy strategy = new EarlyConfirmRunnerFactory(FILE).create("NIFTY", Snapshots.SESSION);

    @Test
    void volatilityRegimeUsesVixForNiftyIvForSensexAndEscalatesOnRealisedVol() {
        assertThat(VolatilityRegime.classify(new Snapshots().set("volatility.vixPercentile252d", 82.0)
                .set("volatility.atmIvPercentile", 10.0).at("10:00"), EXT))
                .isEqualTo(new VolatilityRegime.Result("HIGH", "VIX_252D", 82.0));
        assertThat(VolatilityRegime.classify(new Snapshots().set("volatility.vixPercentile60d", 95.0)
                .at("10:00"), EXT).regime()).isEqualTo("EXTREME");
        assertThat(VolatilityRegime.classify(new Snapshots().set("volatility.vixPercentile252d", 50.0)
                .set("volatility.realizedVolPercentile", 95.0).at("10:00"), EXT))
                .isEqualTo(new VolatilityRegime.Result("HIGH", "VIX_252D+RV", 50.0));
        assertThat(VolatilityRegime.classify(new Snapshots().at("10:00"), EXT).regime()).isEqualTo("UNKNOWN");
        assertThat(EcrExtensions.bucket("LOW", "<20").to()).isEqualTo(20);
    }

    @Test
    void normalRegimeTakesTheEarlyProbeWithTheNormalStop() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().set("volatility.vixPercentile252d", 50.0)
                .at("10:30"), PositionView.FLAT);

        assertThat(decision.state().regime()).isEqualTo("NORMAL/NORMAL");
        assertThat(decision.orders()).singleElement().satisfies(order -> {
            assertThat(order.stage()).isEqualTo(Stage.EARLY_ENTRY);
            assertThat(order.lots()).isEqualTo(1);
            assertThat(order.premiumStopPct()).isEqualTo(25);
        });
    }

    @Test
    void highVolatilityHalvesSizeWaitsForConfirmationAndWidensTheStop() {
        Snapshots high = Snapshots.bullishEarly().set("volatility.vixPercentile252d", 80.0);
        assertThat(strategy.decide(high.at("10:30"), PositionView.FLAT).orders()).isEmpty(); // 2 lots: no probe

        Decision confirmed = strategy.decide(high.confirmedBreakout().set("levels.breakoutBarVolumeRatio", 1.5)
                .at("10:33"), PositionView.FLAT);
        assertThat(confirmed.state().regime()).isEqualTo("NORMAL/HIGH");
        assertThat(confirmed.orders()).singleElement().satisfies(order -> {
            assertThat(order.stage()).isEqualTo(Stage.CONFIRMED);
            assertThat(order.lots()).isEqualTo(2);
            assertThat(order.premiumStopPct()).isEqualTo(35);
        });
    }

    @Test
    void aBreakoutWithoutVolumeIsNotConfirmed() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().confirmedBreakout()
                .set("volatility.vixPercentile252d", 80.0).set("levels.breakoutBarVolumeRatio", 0.8).at("10:33"),
                PositionView.FLAT);
        assertThat(decision.ce().conditions()).containsEntry("breakout_bar", true).containsEntry("breakout_volume", false);
        assertThat(decision.orders()).isEmpty();
    }

    @Test
    void aRunnerBecomesACasRunnerHeldWhileTheAuctionAgreesAndClosedWhenItDisagrees() {
        reachRunner();
        PositionView held = new PositionView(true, OptionSide.CE, 4, 100, null);

        Decision agrees = strategy.decide(casAgreeing(runner()).at("15:16"), held);
        assertThat(agrees.orders()).isEmpty();
        assertThat(agrees.ce().stage()).isEqualTo(Stage.RUNNER);
        assertThat(agrees.ce().casScore()).isGreaterThan(55);

        Decision disagrees = strategy.decide(casDisagreeing(runner()).at("15:18"), held);
        assertThat(disagrees.ce().casScore()).isLessThan(55);
        assertThat(disagrees.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("CAS_DISAGREES");
    }

    @Test
    void aCasRunnerClosesAtTheAuctionEnd() {
        reachRunner();
        PositionView held = new PositionView(true, OptionSide.CE, 4, 100, null);
        strategy.decide(casAgreeing(runner()).at("15:16"), held);
        Decision end = strategy.decide(casAgreeing(runner()).at("15:35"), held);
        assertThat(end.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("CAS_END");
    }

    @Test
    void nonRunnersStillCloseAtFlatBy() {
        strategy.decide(Snapshots.bullishEarly().at("10:30"), PositionView.FLAT);
        Decision flat = strategy.decide(casAgreeing(Snapshots.bullishEarly()).at("15:15"),
                new PositionView(true, OptionSide.CE, 1, 100, null));
        assertThat(flat.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("FLAT_BY");
    }

    @Test
    void casEntriesNeedPerStockAuctionDataAndFuturesAgreement() {
        Decision indexOnly = strategy.decide(casAgreeing(new Snapshots()).at("15:22"), PositionView.FLAT);
        assertThat(indexOnly.ce().casScore()).isGreaterThan(75);
        assertThat(indexOnly.orders()).isEmpty();

        Decision withStocks = strategy.decide(withConstituents(casAgreeing(new Snapshots())).at("15:23"),
                PositionView.FLAT);
        assertThat(withStocks.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(order.side()).isEqualTo(OptionSide.CE);
            assertThat(order.reason()).startsWith("CAS_");
        });
        assertThat(strategy.decide(withConstituents(casAgreeing(new Snapshots())).at("15:24"), PositionView.FLAT)
                .orders()).as("one CAS campaign per side").isEmpty();
    }

    @Test
    void casEntriesOnlyInsideTheEntryWindow() {
        assertThat(strategy.decide(withConstituents(casAgreeing(new Snapshots())).at("15:17"), PositionView.FLAT)
                .orders()).isEmpty();
        assertThat(strategy.decide(withConstituents(casAgreeing(new Snapshots())).at("15:31"), PositionView.FLAT)
                .orders()).isEmpty();
    }

    @Test
    void v5IgnoresAFrozenIndicativeIndexButUsesAMovingOne() {
        ThresholdConfig v5 = ThresholdConfig.load(Path.of("..", "config", "strategy", "early-confirm-runner.v5.yaml"));
        CasScorer scorer = new CasScorer(EcrConfig.from(v5).ext().cas());
        CasScorer.Score frozen = scorer.score(OptionSide.CE, casAgreeing(new Snapshots())
                .set("cas.indicativeChangeLong", 0.0).at("15:22"));
        assertThat(frozen.components().get("iep_direction")).isNaN();
        assertThat(frozen.components().get("fut_cas_alignment")).isNaN();
        assertThat(frozen.value()).isGreaterThan(0); // futures, option and IV components remain
        CasScorer.Score moving = scorer.score(OptionSide.CE, casAgreeing(new Snapshots())
                .set("cas.indicativeChangeLong", 12.0).at("15:22"));
        assertThat(moving.components().get("iep_direction")).isGreaterThan(0.5);
        assertThat(moving.futuresAlignment()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ fixtures

    private void reachRunner() {
        strategy.decide(Snapshots.bullishEarly().at("10:30"), PositionView.FLAT);
        Decision confirmed = strategy.decide(Snapshots.bullishEarly().confirmedBreakout()
                .set("levels.breakoutBarVolumeRatio", 1.5).at("10:33"), new PositionView(true, OptionSide.CE, 1, 100, null));
        assertThat(confirmed.ce().stage()).isEqualTo(Stage.CONFIRMED);
        Decision runner = strategy.decide(runner().at("10:36"), new PositionView(true, OptionSide.CE, 3, 100, null));
        assertThat(runner.ce().stage()).isEqualTo(Stage.RUNNER);
    }

    /** Accepted breakout: two closes, retest held, travel and volume after the break, premium responding. */
    private static Snapshots runner() {
        return Snapshots.bullishEarly().confirmedBreakout()
                .set("levels.breakoutBarVolumeRatio", 1.5)
                .set("structure.orhClosesAbove", 2)
                .set("structure.orhRetestHeld", true)
                .set("levels.orhTravelAtr", 1.0)
                .set("levels.orhVolumeAfterBreak", 1.4);
    }

    private static Snapshots casAgreeing(Snapshots s) {
        return s.set("structure.atr1m", 5.0)
                .set("cas.indicativeReturnPct", 0.3)
                .set("cas.indicativeChangeMid", 10.0)
                .set("cas.indicativeAcceleration", 5.0)
                .set("cas.futuresChange1m", 10.0)
                .set("cas.futuresFollowRatio", 1.0)
                .set("futures.acceleration1m", 3.0)
                .set("premium.ceChange1mPct", 2.0)
                .set("volatility.ivTrend", "RISING");
    }

    private static Snapshots casDisagreeing(Snapshots s) {
        return s.set("structure.atr1m", 5.0)
                .set("cas.indicativeReturnPct", -0.3)
                .set("cas.indicativeChangeMid", -10.0)
                .set("cas.indicativeAcceleration", -5.0)
                .set("cas.futuresChange1m", -10.0)
                .set("cas.futuresFollowRatio", 1.0)
                .set("futures.acceleration1m", -3.0)
                .set("premium.ceChange1mPct", -2.0)
                .set("volatility.ivTrend", "FALLING");
    }

    private static Snapshots withConstituents(Snapshots s) {
        return s.set("cas.constituentCoveragePct", 95.0)
                .set("cas.weightedIepReturnPct", 0.3)
                .set("cas.weightedImbalance", 0.5)
                .set("cas.weightedImbalanceChange", 0.1)
                .set("cas.casBreadthPct", 80.0)
                .set("cas.topConcentration", 0.3);
    }
}
