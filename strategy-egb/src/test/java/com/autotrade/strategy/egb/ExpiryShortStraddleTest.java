package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** A-358: the expiry short straddle sells at 12:00 on NIFTY's expiry, exits at the break-even, and trades once a day. */
class ExpiryShortStraddleTest {

    private static final Path FILE = Path.of("..", "config", "strategy", "expiry-short-straddle.v1.yaml");
    private static final StrategyFactory FACTORY = Strategies.load(FILE);
    /** Both legs sold: call 45.00 + put 42.00 = a credit of 87 at the 22800 strike. */
    private static final PositionView HOLDING = new PositionView(true, OptionSide.CE, 1, 45.0, null, 44.8, 0, 1.0,
            List.of(new PositionView.Leg(OptionSide.CE, 22800, 65, 45.0, 44.8, 45.2),
                    new PositionView.Leg(OptionSide.PE, 22800, 65, 42.0, 41.8, 42.2)));

    private final Strategy strategy = FACTORY.create("NIFTY", Snapshots.SESSION);

    /** NIFTY expiry day, spot 22815, ATM 22800. */
    private static Snapshots expiry() {
        return new Snapshots().set("spot", 22815.0).set("regime.dteTradingDays", 0)
                .set("options.atmStrike", 22800.0).set("options.strikeStep", 50.0);
    }

    @Test
    void loadsThroughTheGenericLoader() {
        assertThat(FACTORY).isInstanceOf(ExpiryShortStraddleFactory.class);
        assertThat(FACTORY.id()).isEqualTo("expiry-short-straddle");
        assertThat(FACTORY.version()).isEqualTo("ess-v1");
        assertThat(FACTORY.premiumStopPct()).isEqualTo(50);
        assertThat(FACTORY.requiredFeatureSections()).isEmpty();
    }

    @Test
    void sellsOneAtmStraddleAt1200OnNiftysExpiry() {
        assertThat(strategy.decide(expiry().at("11:59"), PositionView.FLAT).orders()).as("before 12:00").isEmpty();
        Decision decision = strategy.decide(expiry().at("12:00"), PositionView.FLAT);
        assertThat(decision.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.SELL_STRADDLE);
            assertThat(order.side()).isNull();
            assertThat(order.lots()).isEqualTo(1);
            assertThat(order.strikeOffset()).isZero();
            assertThat(order.premiumStopPct()).isEqualTo(50);
            assertThat(order.reason()).isEqualTo("SELL_1200");
        });
        assertThat(decision.ce().stage()).isEqualTo(Stage.CONFIRMED);
        assertThat(decision.ce().conditions()).containsEntry("expiry_day", true).containsEntry("entry_window", true);
    }

    @Test
    void neverOffExpiryNorOnSensex() {
        assertThat(strategy.decide(expiry().set("regime.dteTradingDays", 1).at("12:00"), PositionView.FLAT).orders())
                .as("not NIFTY's expiry").isEmpty();
        Strategy sensex = FACTORY.create("SENSEX", Snapshots.SESSION);
        Decision decision = sensex.decide(expiry().set("underlying", "SENSEX").at("12:00"), PositionView.FLAT);
        assertThat(decision.orders()).as("A-292 was NIFTY only").isEmpty();
        assertThat(decision.ce().stage()).isEqualTo(Stage.IDLE);
    }

    @Test
    void aRefusedEntryRetriesInsideTheWindowOnlyThenGivesUpForTheDay() {
        assertThat(strategy.decide(expiry().at("12:00"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(strategy.decide(expiry().at("12:01"), PositionView.FLAT).orders()).as("refused: waits a minute").isEmpty();
        assertThat(strategy.decide(expiry().at("12:02"), PositionView.FLAT).orders()).as("tries again").hasSize(1);
        Decision after = strategy.decide(expiry().at("12:05"), PositionView.FLAT);
        assertThat(after.orders()).as("the window closed").isEmpty();
        assertThat(after.ce().stage()).isEqualTo(Stage.EXITED);
        assertThat(strategy.decide(expiry().at("12:06"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void aStraddleThatOpenedAndClosedInsideOneMinuteIsNotSoldAgain() {
        assertThat(strategy.decide(expiry().at("12:00"), PositionView.FLAT).orders()).hasSize(1);
        // one leg filled and the pair was unwound (LEG_NOT_FILLED, or the ask stop) before the 12:01 snapshot
        Decision next = strategy.decide(expiry().at("12:01"), PositionView.flat(1));
        assertThat(next.orders()).as("not a refusal: a leg traded").isEmpty();
        assertThat(next.ce().stage()).isEqualTo(Stage.EXITED);
        assertThat(strategy.decide(expiry().at("12:02"), PositionView.FLAT).orders()).isEmpty();
    }

    @Test
    void theBreakEvenExitIsAskedOnceWhileTheLegsAreBeingBoughtBack() {
        strategy.decide(expiry().at("12:00"), PositionView.FLAT);
        assertThat(strategy.decide(expiry().set("spot", 22890.0).at("12:31"), HOLDING).orders()).hasSize(1);
        assertThat(strategy.decide(expiry().set("spot", 22895.0).at("12:32"), HOLDING).orders())
                .as("the executor is already closing both legs").isEmpty();
    }

    @Test
    void buysBothBackWhenTheIndexClosesBeyondTheBreakEven() {
        strategy.decide(expiry().at("12:00"), PositionView.FLAT);
        assertThat(strategy.decide(expiry().set("spot", 22880.0).at("12:30"), HOLDING).orders())
                .as("22880 < 22800 + 87").isEmpty();
        assertThat(strategy.decide(expiry().set("spot", 22890.0).at("12:31"), HOLDING).orders()).singleElement()
                .satisfies(order -> {
                    assertThat(order.action()).isEqualTo(OrderIntent.Action.EXIT);
                    assertThat(order.reason()).isEqualTo("BREAK_EVEN_UP");
                });
        Strategy another = FACTORY.create("NIFTY", Snapshots.SESSION);      // the first one is already exiting
        another.decide(expiry().at("12:00"), PositionView.FLAT);
        assertThat(another.decide(expiry().set("spot", 22700.0).at("12:32"), HOLDING).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("BREAK_EVEN_DOWN");
    }

    @Test
    void holdsWhileALegIsStillFillingAndTradesOnceADay() {
        strategy.decide(expiry().at("12:00"), PositionView.FLAT);
        // the executor lists both legs as soon as they are sent; the unfilled one has quantity 0 and no credit yet
        PositionView halfFilled = new PositionView(true, OptionSide.CE, 1, 45.0, null, 44.8, 0, Double.NaN,
                List.of(new PositionView.Leg(OptionSide.CE, 22800, 65, 45.0, 44.8, 45.2),
                        new PositionView.Leg(OptionSide.PE, 22800, 0, 0.0, 41.8, 42.2)));
        assertThat(strategy.decide(expiry().set("spot", 23000.0).at("12:01"), halfFilled).orders())
                .as("a half-filled pair is never judged against a half-width break-even").isEmpty();
        strategy.decide(expiry().at("12:02"), HOLDING);
        Decision closed = strategy.decide(expiry().at("12:03"), PositionView.flat(2));   // the OMS stop closed both
        assertThat(closed.orders()).isEmpty();
        assertThat(closed.ce().stage()).isEqualTo(Stage.EXITED);
        assertThat(strategy.decide(expiry().at("12:04"), PositionView.FLAT).orders()).as("one a day").isEmpty();
    }
}
