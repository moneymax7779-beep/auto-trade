package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.VersionedFactory;

/** etr-v1 and etr-v5 in one live set: the later one runs as expiry-trend-rider@etr-v5 with its own settings. */
class VersionedFactoryTest {

    private static StrategyFactory load(String file) {
        return Strategies.load(Path.of("..", "config", "strategy", file));
    }

    @Test
    void aRepeatedStrategyRunsUnderIdAtVersionAndKeepsItsSettings() {
        List<StrategyFactory> out = VersionedFactory.unique(List.of(load("expiry-trend-rider.v1.yaml"),
                load("break-retest.v1.yaml"), load("expiry-trend-rider.v5.yaml")));
        assertThat(out).extracting(StrategyFactory::id)
                .containsExactly("expiry-trend-rider", "break-retest", "expiry-trend-rider@etr-v5");
        assertThat(out.get(2).version()).isEqualTo("etr-v5");
        assertThat(out.get(2).premiumBudget()).isEqualTo(load("expiry-trend-rider.v5.yaml").premiumBudget());
        assertThat(out.get(2).create("NIFTY", LocalDate.of(2026, 10, 7)).id()).isEqualTo("expiry-trend-rider@etr-v5");
    }
}
