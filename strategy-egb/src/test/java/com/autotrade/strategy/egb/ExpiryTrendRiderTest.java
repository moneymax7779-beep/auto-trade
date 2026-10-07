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

    @Test
    void v2OnlyAddsPyramidProtection() {
        StrategyFactory v2 = Strategies.load(Path.of("..", "config", "strategy", "expiry-trend-rider.v2.yaml"));
        assertThat(v2.pyramidRiskCap()).isTrue();
        assertThat(FACTORY.pyramidRiskCap()).as("v1").isFalse();
        assertThat(v2.premiumStopPct()).isEqualTo(FACTORY.premiumStopPct());
        assertThat(v2.premiumBudget()).isEqualTo(FACTORY.premiumBudget());
        assertThat(v2.version()).isEqualTo("etr-v2");
    }

    @Test
    void v4ExitsAfterThirtyMinutesWithoutANewExtremeAndANewLowRestartsTheClock() {
        Strategy v4 = Strategies.load(Path.of("..", "config", "strategy", "expiry-trend-rider.v4.yaml")).create("SENSEX", Snapshots.SESSION);
        v4.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        assertThat(v4.decide(falling(72060, 72050).at("12:41"), PositionView.FLAT).orders()).hasSize(1);   // entry on a new low
        assertThat(v4.decide(falling(72070, 72050).at("13:10"), holdingPuts()).orders()).as("29 minutes").isEmpty();
        assertThat(v4.decide(falling(72040, 72030).at("13:11"), holdingPuts()).orders())
                .as("a new low at 13:11 restarts the clock (and adds)").allMatch(o -> o.action() == OrderIntent.Action.ADD);
        assertThat(v4.decide(falling(72045, 72030).at("13:40"), holdingPuts()).orders()).isEmpty();
        assertThat(v4.decide(falling(72045, 72030).at("13:41"), holdingPuts()).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("NO_NEW_EXTREME_30"));

        Strategy v1 = FACTORY.create("SENSEX", Snapshots.SESSION);
        v1.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        v1.decide(falling(72060, 72050).at("12:41"), PositionView.FLAT);
        assertThat(v1.decide(falling(72070, 72050).at("13:41"), holdingPuts()).orders()).as("v1 has no time stop").isEmpty();
    }

    @Test
    void v5MakesNoNewEntryAfterATimeStopExit() {
        Strategy v5 = Strategies.load(Path.of("..", "config", "strategy", "expiry-trend-rider.v5.yaml")).create("SENSEX", Snapshots.SESSION);
        v5.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        assertThat(v5.decide(falling(72060, 72050).at("12:41"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(v5.decide(falling(72060, 72050).at("13:11"), holdingPuts()).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("NO_NEW_EXTREME_30"));
        assertThat(v5.decide(falling(71990, 71980).at("13:24"), PositionView.FLAT).orders())
                .as("a new low after the time stop: no re-entry").isEmpty();

        Strategy v4 = Strategies.load(Path.of("..", "config", "strategy", "expiry-trend-rider.v4.yaml")).create("SENSEX", Snapshots.SESSION);
        v4.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        v4.decide(falling(72060, 72050).at("12:41"), PositionView.FLAT);
        v4.decide(falling(72060, 72050).at("13:11"), holdingPuts());
        assertThat(v4.decide(falling(71990, 71980).at("13:24"), PositionView.FLAT).orders()).as("v4 re-enters").hasSize(1);
    }

    @Test
    void v6TrailsThePeakBidOnceFiftyPercentUpAndExitsFifteenPercentBelowIt() {
        Strategy v6 = Strategies.load(Path.of("..", "config", "strategy", "expiry-trend-rider.v6.yaml")).create("SENSEX", Snapshots.SESSION);
        v6.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        assertThat(v6.decide(falling(72060, 72050).at("12:41"), PositionView.FLAT).orders()).hasSize(1);   // entry on a new low
        PositionView up40 = new PositionView(true, OptionSide.PE, 1, 200, null, 280);
        PositionView up60 = new PositionView(true, OptionSide.PE, 1, 200, null, 320);
        PositionView peak = new PositionView(true, OptionSide.PE, 1, 200, null, 400);
        PositionView back14 = new PositionView(true, OptionSide.PE, 1, 200, null, 345);
        PositionView back15 = new PositionView(true, OptionSide.PE, 1, 200, null, 340);
        assertThat(v6.decide(falling(72040, 72030).at("12:50"), up40).orders()).as("not armed below +50 %: the new low adds")
                .allMatch(o -> o.action() == OrderIntent.Action.ADD);
        assertThat(v6.decide(falling(72045, 72030).at("12:55"), up60).orders()).as("armed, no exit").isEmpty();
        assertThat(v6.decide(falling(72045, 72030).at("13:00"), peak).orders()).isEmpty();
        assertThat(v6.decide(falling(72045, 72030).at("13:05"), back14).orders()).as("13.75 % below the peak").isEmpty();
        assertThat(v6.decide(falling(72045, 72030).at("13:06"), back15).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TRAIL_15"));

        Strategy v1 = FACTORY.create("SENSEX", Snapshots.SESSION);
        v1.decide(falling(72110, 72100).at("12:40"), PositionView.FLAT);
        v1.decide(falling(72060, 72050).at("12:41"), PositionView.FLAT);
        v1.decide(falling(72045, 72030).at("13:00"), peak);
        assertThat(v1.decide(falling(72045, 72030).at("13:06"), back15).orders()).as("v1 has no trail").isEmpty();
    }
}
