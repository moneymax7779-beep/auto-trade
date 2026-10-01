package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** etr-v1: enters on a new day low in a bearish trend state, adds on the next ones, exits on the swing. */
class ExpiryTrendRiderTest {

    private static final StrategyFactory FACTORY =
            Strategies.load(Path.of("..", "config", "strategy", "expiry-trend-rider.v1.yaml"));

    private final Strategy strategy = FACTORY.create("SENSEX", Snapshots.SESSION);

    /** 1 Oct-like: expiry day, below the opening-range low, breadth -80, short build, IV rising. */
    private static Snapshots falling(double spot, double dayLow) {
        return new Snapshots()
                .set("spot", spot)
                .set("regime.dteTradingDays", 0)
                .set("structure.orComplete", true)
                .set("structure.orHigh", 72446.75)
                .set("structure.orLow", 72202.31)
                .set("structure.dayHigh", 72569.65)
                .set("structure.dayLow", dayLow)
                .set("structure.lastBarClose", spot)
                .set("structure.lastSwingHigh", 72150.0)
                .set("structure.lastSwingLow", dayLow)
                .set("breadth.moverBreadth", -80.0)
                .set("futures.oiState", "FRESH_SHORT")
                .set("options.atmIvChange3m", 0.01);
    }

    private static PositionView holdingPuts() {
        return new PositionView(true, OptionSide.PE, 1, 200, null, 210);
    }

    @Test
    void loads() {
        assertThat(FACTORY).isInstanceOf(ExpiryTrendRiderFactory.class);
        assertThat(FACTORY.version()).isEqualTo("etr-v1");
        assertThat(FACTORY.intendedLots()).isEqualTo(4);
        assertThat(FACTORY.premiumBudget()).isEqualTo(500_000);
    }

    @Test
    void entersOnANewDayLowAddsOnTheNextAndExitsWhenTheSwingHighIsReclaimed() {
        assertThat(strategy.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT).orders()).isEmpty();   // no previous low yet
        Decision entry = strategy.decide(falling(72060, 72050).at("12:41"), PositionView.FLAT);
        assertThat(entry.orders()).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderIntent.Action.ENTER);
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.lots()).isEqualTo(1);
        });
        assertThat(strategy.decide(falling(72070, 72050).at("12:42"), holdingPuts()).orders()).isEmpty();        // no new low
        assertThat(strategy.decide(falling(71990, 71980).at("12:43"), holdingPuts()).orders())
                .singleElement().extracting(OrderIntent::action).isEqualTo(OrderIntent.Action.ADD);
        Decision exit = strategy.decide(falling(72160, 71980).set("structure.lastBarClose", 72160.0).at("12:50"), holdingPuts());
        assertThat(exit.orders()).singleElement().satisfies(o -> {
            assertThat(o.action()).isEqualTo(OrderIntent.Action.EXIT);
            assertThat(o.reason()).isEqualTo("SWING_HIGH_RECLAIMED");
        });
    }

    @Test
    void noTradeWhenTheIndexDoesNotExpireOrBreadthOrIvDisagree() {
        Strategy notExpiry = FACTORY.create("NIFTY", Snapshots.SESSION);
        notExpiry.decide(falling(72110, 72100).set("regime.dteTradingDays", 4).at("12:40"), PositionView.FLAT);
        assertThat(notExpiry.decide(falling(72060, 72050).set("regime.dteTradingDays", 4).at("12:41"), PositionView.FLAT)
                .orders()).isEmpty();
        Strategy weakBreadth = FACTORY.create("SENSEX", Snapshots.SESSION);
        weakBreadth.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        assertThat(weakBreadth.decide(falling(72060, 72050).set("breadth.moverBreadth", -20.0).at("12:41"), PositionView.FLAT)
                .orders()).isEmpty();
        Strategy ivFalling = FACTORY.create("SENSEX", Snapshots.SESSION);
        ivFalling.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        assertThat(ivFalling.decide(falling(72060, 72050).set("options.atmIvChange3m", -0.01).at("12:41"), PositionView.FLAT)
                .orders()).isEmpty();
    }
}
