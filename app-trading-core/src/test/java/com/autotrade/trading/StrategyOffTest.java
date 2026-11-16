package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.OrderIntent;

/** V35 (user 11 Nov 2026): the owner's OFF switch refuses an OFF strategy's entries and adds, never its exits. */
class StrategyOffTest {

    @Test
    void anOffStrategyOpensAndAddsNothingButAlwaysExits() {
        Set<String> off = Set.of("ma-cross");
        for (OrderIntent.Action action : OrderIntent.Action.values()) {
            assertThat(TradingSession.refusedAsOff(off, "ma-cross", action)).as(action.name())
                    .isEqualTo(action != OrderIntent.Action.EXIT);
            assertThat(TradingSession.refusedAsOff(off, "oi-burst-cross", action)).as(action.name()).isFalse();
            assertThat(TradingSession.refusedAsOff(Set.of(), "ma-cross", action)).as(action.name()).isFalse();
        }
    }
}
