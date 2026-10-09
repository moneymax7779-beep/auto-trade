package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.trading.AggressorSplit.Tick;

class AggressorSplitTest {

    @Test
    void tradesAtTheAskAreBoughtAndAtTheBidSold() {
        Tick before = new Tick(100.0, 1_000, 99.9, 100.1);
        AggressorSplit.Split split = AggressorSplit.of(before, List.of(
                new Tick(100.1, 1_300, 100.0, 100.2),     // at the previous ask: 300 bought
                new Tick(100.0, 1_350, 99.9, 100.1),      // at the previous bid: 50 sold
                new Tick(100.05, 1_400, 99.9, 100.1),     // inside the spread, above the last price: 50 bought
                new Tick(100.05, 1_420, 99.9, 100.1),     // inside, unchanged: 20 unclassified
                new Tick(100.0, 1_420, 99.9, 100.1)));    // no volume: nothing
        assertThat(split).isEqualTo(new AggressorSplit.Split(350, 50, 20));
        assertThat(split.boughtPct()).isEqualTo(88L);
    }

    @Test
    void theFirstTickOfTheDayOnlySetsTheBase() {
        AggressorSplit.Split split = AggressorSplit.of(null, List.of(
                new Tick(100, 5_000, 99.9, 100.1), new Tick(99.9, 5_100, 99.8, 100.0)));
        assertThat(split).isEqualTo(new AggressorSplit.Split(0, 100, 0));
        assertThat(AggressorSplit.of(null, List.of()).boughtPct()).isNull();
    }

    @Test
    void missingQuotesFallBackToTheTickRule() {
        Tick before = new Tick(100, 1_000, Double.NaN, Double.NaN);
        assertThat(AggressorSplit.of(before, List.of(new Tick(100.5, 1_100, Double.NaN, Double.NaN))))
                .isEqualTo(new AggressorSplit.Split(100, 0, 0));
    }
}
