package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.risk.RiskLimits;

/** replay-equity chains replayed days: absent = the starting capital; given = that equity, sized like live. */
class ReplayEquityTest {

    private static TradingProperties.Trading trading(Double equity) {
        return new TradingProperties.Trading("P", List.of("NIFTY"), "base", null, null, null, null, null, null, 250, 3000,
                "09:00", "15:45", "upstox", 0, null, "own", null, equity);
    }

    @Test
    void replayEquitySetsTheSizingOfAReplayedDay() {
        RiskLimits v9 = RiskLimits.from(ThresholdConfig.load(Path.of("..", "config", "risk", "paper-risk.v9.yaml")));
        assertThat(EquityLedger.apply(v9, trading(null).replayRealisedNet(v9.startingCapital())).capital()).isEqualTo(500_000);
        RiskLimits chained = EquityLedger.apply(v9, trading(1_049_620.0).replayRealisedNet(v9.startingCapital()));
        assertThat(chained.capital()).isEqualTo(1_049_620);
        assertThat(chained.budgetScale()).isCloseTo(2.099, org.assertj.core.api.Assertions.within(0.001));
        assertThat(chained.dailyLossLimit()).isCloseTo(230_916.4, org.assertj.core.api.Assertions.within(0.1));
    }
}
