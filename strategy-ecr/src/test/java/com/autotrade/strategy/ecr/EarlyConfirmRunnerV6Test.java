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

/** Strategy v6: regime inputs and event floor, strike choice, structure stop, order flow, liquidity, states. */
class EarlyConfirmRunnerV6Test {

    private static final ThresholdConfig FILE =
            ThresholdConfig.load(Path.of("..", "config", "strategy", "early-confirm-runner.v6.yaml"));
    private static final EcrExtensions EXT = EcrConfig.from(FILE).ext();

    private final Strategy strategy = new EarlyConfirmRunnerFactory(FILE).create("NIFTY", Snapshots.SESSION);

    @Test
    void strikeOffsetCountsInTheMoneyStrikesPerSide() {
        OrderIntent ce = new OrderIntent(OrderIntent.Action.ENTER, OptionSide.CE, 1, Stage.CONFIRMED, "x", 40, 1);
        OrderIntent pe = new OrderIntent(OrderIntent.Action.ENTER, OptionSide.PE, 1, Stage.CONFIRMED, "x", 40, 1);
        assertThat(ce.strike(23100, 50)).isEqualTo(23050);
        assertThat(pe.strike(23100, 50)).isEqualTo(23150);
        assertThat(new OrderIntent(OrderIntent.Action.ENTER, OptionSide.CE, 1, Stage.CONFIRMED, "x").strike(23100, 50))
                .isEqualTo(23100);
    }

    @Test
    void anyDesignInputRaisesTheRegimeOneLevelAndEventDaysAreAtLeastHigh() {
        Snapshots normal = new Snapshots().set("volatility.vixPercentile252d", 50.0).set("regime.dteTradingDays", 3)
                .set("regime.minutesToExpiryClose", 2000L);
        assertThat(VolatilityRegime.classify(normal.at("10:00"), EXT).regime()).isEqualTo("NORMAL");
        assertThat(VolatilityRegime.classify(new Snapshots().set("volatility.vixPercentile252d", 50.0).set("regime.dteTradingDays", 3)
                .set("futures.rvolTod", 2.3).at("10:00"), EXT))
                .isEqualTo(new VolatilityRegime.Result("HIGH", "VIX_252D+RVOL", 50.0));
        assertThat(VolatilityRegime.classify(new Snapshots().set("volatility.vixPercentile252d", 50.0).set("regime.dteTradingDays", 3)
                .set("volatility.vixChange15m", 5.0).at("10:00"), EXT).basis()).endsWith("+VIX_MOVE");
        assertThat(VolatilityRegime.classify(new Snapshots().set("volatility.vixPercentile252d", 10.0).set("regime.dteTradingDays", 3)
                .set("regime.marketEvent", "RBI MPC decision").at("10:00"), EXT))
                .isEqualTo(new VolatilityRegime.Result("HIGH", "VIX_252D+EVENT", 10.0));
        assertThat(VolatilityRegime.classify(new Snapshots().set("volatility.vixPercentile252d", 50.0)
                .set("regime.dteTradingDays", 0).set("regime.minutesToExpiryClose", 45L).at("14:45"), EXT).regime())
                .isEqualTo("HIGH");
    }

    @Test
    void highVolatilityBuysOneStrikeInTheMoney() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().confirmedBreakout()
                .set("volatility.vixPercentile252d", 80.0).set("levels.breakoutBarVolumeRatio", 1.5)
                .set("breadth.moverBreadth", 60.0).at("10:33"), PositionView.FLAT);
        assertThat(decision.orders()).singleElement().satisfies(order -> {
            assertThat(order.strikeOffset()).isEqualTo(1);
            assertThat(order.premiumStopPct()).isEqualTo(40);
        });
    }

    @Test
    void earlyEntryUsesMoverBreadth() {
        Snapshots setup = Snapshots.bullishEarly().set("volatility.vixPercentile252d", 50.0);
        Decision without = strategy.decide(setup.set("breadth.moverBreadth", 20.0).at("10:30"), PositionView.FLAT);
        assertThat(without.ce().conditions()).containsEntry("breadth", false);
        Strategy fresh = new EarlyConfirmRunnerFactory(FILE).create("NIFTY", Snapshots.SESSION);
        Decision with = fresh.decide(Snapshots.bullishEarly().set("volatility.vixPercentile252d", 50.0)
                .set("breadth.momentumBreadth", 10.0).set("breadth.moverBreadth", 60.0).at("10:30"), PositionView.FLAT);
        assertThat(with.ce().conditions()).containsEntry("breadth", true);
        assertThat(with.ce().stage()).isEqualTo(Stage.EARLY_ENTRY);
    }

    @Test
    void aLiquidityDropBlocksNewEntries() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().set("volatility.vixPercentile252d", 50.0)
                .set("breadth.moverBreadth", 60.0).set("book.atmCeDepthChangePct", -90.0).at("10:30"), PositionView.FLAT);
        assertThat(decision.ce().conditions()).containsEntry("liquidity_ok", false);
        assertThat(decision.orders()).isEmpty();
    }

    @Test
    void orderFlowCountsAsBookConfirmation() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().set("volatility.vixPercentile252d", 50.0)
                .set("book.atmCeBidChangePct", 40.0).set("book.atmCeAskChangePct", -40.0).at("11:00"), PositionView.FLAT);
        assertThat(decision.ce().conditions()).containsEntry("book_flow_favourable", true)
                .containsEntry("book_favourable", true);
    }

    @Test
    void marketStateIsNamed() {
        Decision decision = strategy.decide(Snapshots.bullishEarly().confirmedBreakout().at("11:00"), PositionView.FLAT);
        assertThat(decision.state().label()).isIn("TREND_UP", "TRANSITION", "RANGE", "TREND_DOWN");
    }
}
