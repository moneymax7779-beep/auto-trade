package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** BANKNIFTY can be recorded without being traded; by default every recorded index is traded. */
class TradeUnderlyingsTest {

    private static final StrategyFactory FACTORY = new StrategyFactory() {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public String configHash() {
            return "sha256:test";
        }

        @Override
        public Strategy create(String underlying, LocalDate session) {
            return null;
        }

        @Override
        public double premiumStopPct() {
            return 30;
        }
    };

    private static TradingSession.Settings settings(List<String> trade) {
        return new TradingSession.Settings(TradingSession.Mode.PAPER_REPLAY, "P", LocalDate.of(2026, 9, 29),
                List.of("NIFTY", "BANKNIFTY", "SENSEX"), trade, null, null, List.of(FACTORY), null, null, null, null,
                Map.of(), "test");
    }

    @Test
    void tradeUnderlyingsAreASubsetAndDefaultToEveryRecordedIndex() {
        assertThat(settings(List.of("NIFTY", "SENSEX")).tradeUnderlyings()).containsExactly("NIFTY", "SENSEX");
        assertThat(settings(null).tradeUnderlyings()).containsExactly("NIFTY", "BANKNIFTY", "SENSEX");
        assertThat(settings(List.of()).tradeUnderlyings()).hasSize(3);
        assertThatThrownBy(() -> settings(List.of("NIFTY", "FINNIFTY"))).hasMessageContaining("recorded underlyings");
    }

    @Test
    void propertiesFallBackToTheRecordedList() {
        TradingProperties.Trading none = new TradingProperties.Trading("P", List.of("NIFTY", "BANKNIFTY"), "base", null,
                null, null, null, null, null, 250, 3000, "09:00", "15:45", "upstox", 0, null, null, null);
        assertThat(none.tradeUnderlyingList()).containsExactly("NIFTY", "BANKNIFTY");
        TradingProperties.Trading some = new TradingProperties.Trading("P", List.of("NIFTY", "BANKNIFTY"), "base", null,
                null, null, null, null, null, 250, 3000, "09:00", "15:45", "upstox", 0, List.of("NIFTY"), null, null);
        assertThat(some.tradeUnderlyingList()).containsExactly("NIFTY");
    }
}
