package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** wbr-v1: two minutes of call-wall covering above VWAP enter calls; no progress in 15 minutes exits. */
class WallBreakRiderTest {

    private static final StrategyFactory FACTORY =
            Strategies.load(Path.of("..", "config", "strategy", "wall-break-rider.v1.yaml"));

    private static Snapshots covering(boolean weakening) {
        return Snapshots.bullishCoil().set("options.callWallWeakening", weakening).set("options.putFloorWeakening", false)
                .set("structure.vwapSpotProxy", 23000.0).set("futures.oiState", "SHORT_COVERING")
                .set("structure.lastSwingLow", 22900.0).set("structure.lastBarClose", 23115.0);
    }

    @Test
    void loads() {
        assertThat(FACTORY).isInstanceOf(WallBreakRiderFactory.class);
        assertThat(FACTORY.version()).isEqualTo("wbr-v1");
        assertThat(FACTORY.premiumBudget()).isEqualTo(250_000);
    }

    @Test
    void entersAfterTwoMinutesOfCoveringAndLeavesWithoutProgress() {
        Strategy s = FACTORY.create("NIFTY", Snapshots.SESSION);
        assertThat(s.decide(covering(true).at("12:40"), PositionView.FLAT).orders()).as("one minute is not enough").isEmpty();
        assertThat(s.decide(covering(true).at("12:41"), PositionView.FLAT).orders()).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("CALL_WALL_COVERING");
        });
        java.time.Instant opened = Snapshots.SESSION.atTime(java.time.LocalTime.of(12, 41))
                .atZone(com.autotrade.core.time.MarketTime.IST).toInstant();
        PositionView flatish = new PositionView(true, OptionSide.CE, 1, 100, opened, 105);
        assertThat(s.decide(covering(false).at("12:50"), flatish).orders()).isEmpty();
        assertThat(s.decide(covering(false).at("12:56"), flatish).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("NO_PROGRESS_15"));
    }

    @Test
    void noEntryBelowVwapOrBeforeTheWindowOrOffExpiry() {
        Strategy below = FACTORY.create("NIFTY", Snapshots.SESSION);
        below.decide(covering(true).set("structure.vwapSpotProxy", 23200.0).at("12:40"), PositionView.FLAT);
        assertThat(below.decide(covering(true).set("structure.vwapSpotProxy", 23200.0).at("12:41"), PositionView.FLAT).orders()).isEmpty();
        Strategy early = FACTORY.create("NIFTY", Snapshots.SESSION);
        early.decide(covering(true).at("10:20"), PositionView.FLAT);
        assertThat(early.decide(covering(true).at("10:21"), PositionView.FLAT).orders()).isEmpty();
        Strategy notExpiry = FACTORY.create("NIFTY", Snapshots.SESSION);
        notExpiry.decide(covering(true).set("regime.dteTradingDays", 2).at("12:40"), PositionView.FLAT);
        assertThat(notExpiry.decide(covering(true).set("regime.dteTradingDays", 2).at("12:41"), PositionView.FLAT).orders()).isEmpty();
    }
}
