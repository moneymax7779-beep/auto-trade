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
}
