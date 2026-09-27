package com.autotrade.strategy.ecr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.StrategyFactory;

/** v7 is v6 with a premium budget: the executor scales the 4-lot plan to ₹5,00,000. */
class EarlyConfirmRunnerSizingTest {

    @Test
    void v7CarriesTheBudgetAndV6DoesNot() {
        StrategyFactory v6 = Strategies.load(Path.of("..", "config", "strategy", "early-confirm-runner.v6.yaml"));
        StrategyFactory v7 = Strategies.load(Path.of("..", "config", "strategy", "early-confirm-runner.v7.yaml"));
        assertThat(v6.premiumBudget()).isNaN();
        assertThat(v7.premiumBudget()).isEqualTo(500_000);
        assertThat(v7.intendedLots()).isEqualTo(4).isEqualTo(v6.intendedLots());
        assertThat(v7.version()).isEqualTo("ecr-v7");
        assertThat(v7.premiumStopPct()).isEqualTo(v6.premiumStopPct());
    }

    @Test
    void v8OpensCampaignsOnlyWhenTheIndexExpiresToday() {
        StrategyFactory v7 = Strategies.load(Path.of("..", "config", "strategy", "early-confirm-runner.v7.yaml"));
        StrategyFactory v8 = Strategies.load(Path.of("..", "config", "strategy", "early-confirm-runner.v8.yaml"));
        assertThat(v8.version()).isEqualTo("ecr-v8");
        assertThat(v8.premiumBudget()).isEqualTo(v7.premiumBudget());
        java.util.function.Function<Integer, Snapshots> breakout = dte -> Snapshots.bullishEarly().confirmedBreakout()
                .set("volatility.vixPercentile252d", 80.0).set("levels.breakoutBarVolumeRatio", 1.5)
                .set("breadth.moverBreadth", 60.0).set("regime.dteTradingDays", dte);
        com.autotrade.strategy.PositionView flat = com.autotrade.strategy.PositionView.FLAT;

        assertThat(v7.create("NIFTY", Snapshots.SESSION).decide(breakout.apply(3).at("10:33"), flat).orders())
                .as("v7 trades a non-expiry day").isNotEmpty();
        assertThat(v8.create("NIFTY", Snapshots.SESSION).decide(breakout.apply(3).at("10:33"), flat).orders())
                .as("v8 does not").isEmpty();
        assertThat(v8.create("NIFTY", Snapshots.SESSION).decide(breakout.apply(0).at("10:33"), flat).orders())
                .as("v8 trades the index's expiry day").singleElement()
                .extracting(com.autotrade.strategy.OrderIntent::action).isEqualTo(com.autotrade.strategy.OrderIntent.Action.ENTER);
    }
}
