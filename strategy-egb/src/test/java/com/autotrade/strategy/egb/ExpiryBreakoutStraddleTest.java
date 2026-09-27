package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** The expiry breakout straddle: when it buys both legs, and when it does not. */
class ExpiryBreakoutStraddleTest {

    private static final Path FILE = Path.of("..", "config", "strategy", "expiry-breakout-straddle.v1.yaml");
    private static final StrategyFactory FACTORY = Strategies.load(FILE);
    private static final PositionView HOLDING = new PositionView(true, OptionSide.CE, 93, 42.2, null);

    private final Strategy strategy = FACTORY.create("NIFTY", Snapshots.SESSION);

    /** Compressed and just broken above the opening-range high (one 3-minute close through it). */
    private static Snapshots breakout() {
        return Snapshots.bullishCoil().breakout();
    }

    @Test
    void loadsThroughTheGenericLoader() {
        assertThat(FACTORY).isInstanceOf(ExpiryBreakoutStraddleFactory.class);
        assertThat(FACTORY.id()).isEqualTo("expiry-breakout-straddle");
        assertThat(FACTORY.version()).isEqualTo("ebs-v1");
        assertThat(FACTORY.premiumStopPct()).isEqualTo(20);
        assertThat(FACTORY.requiredFeatureSections()).contains("levels", "compression");
    }

    @Test
    void buysTheFullBudgetStraddleOnAnExpiryBreakoutAfter1230() {
        strategy.decide(Snapshots.bullishCoil().at("12:20"), PositionView.FLAT);   // the compression before it
        Decision decision = strategy.decide(breakout().at("12:31"), PositionView.FLAT);
        assertThat(decision.ce().conditions()).containsEntry("trigger", true).containsEntry("broke_up", true);
        assertThat(decision.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.ENTER_STRADDLE);
            assertThat(order.side()).isNull();
            assertThat(order.strikeOffset()).isZero();
            assertThat(order.premiumBudget()).isEqualTo(500_000);
            assertThat(order.targetPct()).isEqualTo(30);
            assertThat(order.premiumStopPct()).isEqualTo(20);
            assertThat(order.reason()).isEqualTo("BREAKOUT_UP");
        });
        assertThat(decision.ce().stage()).isEqualTo(Stage.CONFIRMED);
    }

    @Test
    void aBreakDownCountsToo() {
        // still compressed on this snapshot; spot 0.5 ATR below the opening-range low after two closes under it
        Decision decision = strategy.decide(Snapshots.bullishCoil().set("structure.distOrlAtr", -0.5)
                .set("structure.orlClosesBelow", 2).at("13:10"), PositionView.FLAT);
        assertThat(decision.orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("BREAKOUT_DOWN");
    }

    @Test
    void outsideTheWindowOffExpiryOrWithoutCompressionItOnlyWatches() {
        strategy.decide(Snapshots.bullishCoil().at("12:20"), PositionView.FLAT);
        assertThat(strategy.decide(breakout().at("12:29"), PositionView.FLAT).orders()).as("before 12:30").isEmpty();
        assertThat(strategy.decide(breakout().at("14:00"), PositionView.FLAT).orders()).as("from 14:00").isEmpty();
        Strategy other = FACTORY.create("NIFTY", Snapshots.SESSION);
        other.decide(Snapshots.bullishCoil().set("regime.dteTradingDays", 1).at("12:20"), PositionView.FLAT);
        assertThat(other.decide(breakout().set("regime.dteTradingDays", 1).at("12:45"), PositionView.FLAT).orders())
                .as("not expiry").isEmpty();
        Strategy loose = FACTORY.create("NIFTY", Snapshots.SESSION);
        assertThat(loose.decide(breakout().set("compression.rangeVsSession", 1.2).at("12:45"), PositionView.FLAT)
                .orders()).as("no compression in the last 30 minutes").isEmpty();
        Strategy stale = FACTORY.create("NIFTY", Snapshots.SESSION);
        stale.decide(Snapshots.bullishCoil().at("12:20"), PositionView.FLAT);
        assertThat(stale.decide(breakout().set("structure.orhClosesAbove", 3).at("12:45"), PositionView.FLAT).orders())
                .as("a breakout three closes old is not a fresh break").isEmpty();
    }

    @Test
    void compressionIsRememberedForThirtyMinutes() {
        strategy.decide(Snapshots.bullishCoil().at("12:10"), PositionView.FLAT);                 // compressed
        Snapshots loose = breakout().set("compression.rangeVsSession", 1.2);
        assertThat(strategy.decide(loose.at("12:40"), PositionView.FLAT).orders()).hasSize(1);   // 30 min later
        Strategy late = FACTORY.create("NIFTY", Snapshots.SESSION);
        late.decide(Snapshots.bullishCoil().at("12:09"), PositionView.FLAT);
        assertThat(late.decide(loose.at("12:40"), PositionView.FLAT).orders()).isEmpty();        // 31 min later
    }

    @Test
    void oneStraddlePerDayAndFlatBy1515() {
        strategy.decide(Snapshots.bullishCoil().at("12:20"), PositionView.FLAT);
        strategy.decide(breakout().at("12:31"), PositionView.FLAT);
        assertThat(strategy.decide(breakout().at("12:32"), HOLDING).orders()).isEmpty();
        Decision flat = strategy.decide(breakout().at("15:15"), HOLDING);
        assertThat(flat.orders()).singleElement().satisfies(order -> {
            assertThat(order.action()).isEqualTo(OrderIntent.Action.EXIT);
            assertThat(order.reason()).isEqualTo("FLAT_BY");
        });

        Strategy bracketed = FACTORY.create("NIFTY", Snapshots.SESSION);
        bracketed.decide(Snapshots.bullishCoil().at("12:20"), PositionView.FLAT);
        assertThat(bracketed.decide(breakout().at("12:31"), PositionView.FLAT).orders()).hasSize(1);
        bracketed.decide(breakout().at("12:32"), HOLDING);
        Decision after = bracketed.decide(breakout().at("12:50"), PositionView.FLAT);           // target or stop hit
        assertThat(after.orders()).as("one straddle per day").isEmpty();
        assertThat(after.ce().stage()).isEqualTo(Stage.EXITED);
    }

    @Test
    void aRefusedEntryIsRetriedAfterThreeMinutes() {
        strategy.decide(Snapshots.bullishCoil().at("12:20"), PositionView.FLAT);
        assertThat(strategy.decide(breakout().at("12:31"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(strategy.decide(breakout().at("12:32"), PositionView.FLAT).orders()).as("refused: wait").isEmpty();
        assertThat(strategy.decide(breakout().at("12:34"), PositionView.FLAT).orders()).isEmpty();
        assertThat(strategy.decide(breakout().at("12:35"), PositionView.FLAT).orders()).as("3 minutes later").hasSize(1);
    }
}
