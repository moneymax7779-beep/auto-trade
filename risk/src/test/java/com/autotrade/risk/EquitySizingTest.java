package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;

class EquitySizingTest {

    private static RiskLimits limits(String file) {
        return RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", file)));
    }

    @Test
    void v8FollowsTheEquityAndV4DoesNot() {
        RiskLimits v8 = limits("paper-risk.v8.yaml");
        assertThat(v8.equityMode()).isTrue();
        assertThat(v8.startingCapital()).isEqualTo(500_000);
        assertThat(v8.budgetScale()).as("at the starting capital: 50 % of the file budgets").isCloseTo(0.5, within(1e-9));
        assertThat(v8.withEquity(500_000).dailyLossLimit()).isEqualTo(75_000);

        RiskLimits doubled = v8.withEquity(1_000_000);              // the account has doubled
        assertThat(doubled.capital()).isEqualTo(1_000_000);
        assertThat(doubled.dailyLossLimit()).isEqualTo(150_000);
        assertThat(doubled.budgetScale()).isCloseTo(1.0, within(1e-9));
        RiskLimits halved = v8.withEquity(250_000);                 // or halved: the size follows it down
        assertThat(halved.budgetScale()).isCloseTo(0.25, within(1e-9));
        assertThat(halved.dailyLossLimit()).isEqualTo(37_500);

        RiskLimits v4 = limits("paper-risk.v4.yaml");
        assertThat(v4.equityMode()).isFalse();
        assertThat(v4.budgetScale()).isEqualTo(1.0);
        assertThat(v4.withEquity(1_000_000).dailyLossLimit()).as("a fixed limit stays fixed").isEqualTo(110_000);
    }

    @Test
    void v9IsV4AtTheStartingCapitalAndFollowsTheEquity() {
        RiskLimits v9 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v9.yaml")));
        assertThat(v9.equityMode()).isTrue();
        assertThat(v9.budgetScale()).as("full equity per trade at Rs 5L").isCloseTo(1.0, within(1e-9));
        assertThat(v9.withEquity(500_000).dailyLossLimit()).as("v4's Rs 1,10,000").isEqualTo(110_000);
        RiskLimits today = v9.withEquity(394_639);
        assertThat(today.budgetScale()).isCloseTo(0.789, within(0.001));
        assertThat(today.dailyLossLimit()).isCloseTo(86_820.6, within(0.1));
        assertThat(v9.withEquity(1_000_000).budgetScale()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void v10IsV9WithTheEquityCountedFromSevenOctober() {
        RiskLimits v10 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v10.yaml")));
        assertThat(v10.equityFrom()).isEqualTo(java.time.LocalDate.of(2026, 10, 7));
        assertThat(v10.budgetScale()).isCloseTo(1.0, within(1e-9));
        assertThat(v10.withEquity(500_000).dailyLossLimit()).isEqualTo(110_000);
        assertThat(v10.withEquity(600_000).equityFrom()).isEqualTo(java.time.LocalDate.of(2026, 10, 7));
        RiskLimits v9 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v9.yaml")));
        assertThat(v9.equityFrom()).isNull();
    }

    /** A-662: risk v69 is v64 with FE's id in place of F1's, for the low priority and the own 5 % cap. */
    @Test
    void v69NamesFeWhereV64NamedF1() {
        RiskLimits v64 = limits("paper-risk.v64.yaml"), v69 = limits("paper-risk.v69.yaml");
        assertThat(v69.strategyGroupLoss().strategies()).containsExactly("oi-burst-cross@obc-v31lvoirhe");
        assertThat(v69.strategyGroupLoss().pct()).isEqualTo(v64.strategyGroupLoss().pct());
        assertThat(v69.withEquity(500_000).capital()).isEqualTo(v64.withEquity(500_000).capital());
        assertThat(v69.dailyLossLimit()).isEqualTo(v64.dailyLossLimit());
    }

    /** Code review 24 Nov 2026: the live engine sizes from withEquity(), which must keep F1's own 5 % cap (risk v64). */
    @Test
    void v64KeepsTheStrategyGroupLossThroughTheEquity() {
        RiskLimits v64 = limits("paper-risk.v64.yaml");
        assertThat(v64.equityMode()).isTrue();
        assertThat(v64.strategyGroupLoss().strategies()).containsExactly("oi-burst-cross@obc-v31lvoirhh");
        RiskLimits grown = v64.withEquity(1_200_000);
        assertThat(grown.capital()).isEqualTo(1_200_000);
        assertThat(grown.strategyGroupLoss()).isEqualTo(v64.strategyGroupLoss());
        assertThat(grown.capital() * grown.strategyGroupLoss().pct() / 100.0).as("5 % of today's equity").isEqualTo(60_000);
    }

    @Test
    void aStrategyGroupLossNeedsAPositivePercentAndPlainIds() {
        assertThat(RiskLimits.StrategyGroupLoss.parse(" a@v1 ,b ", 5).strategies()).containsExactlyInAnyOrder("a@v1", "b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RiskLimits.StrategyGroupLoss.parse("a", 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RiskLimits.StrategyGroupLoss.parse("[a, b]", 5))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RiskLimits.StrategyGroupLoss.parse("a,,b", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
