package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.autotrade.core.time.MarketTime;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Stage;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** The expiry level straddle (A-285 / A-286): one a day, and the time stop. */
class ExpiryLevelStraddleTest {

    private static final StrategyFactory FACTORY =
            Strategies.load(Path.of("..", "config", "strategy", "expiry-level-straddle.v12.yaml"));

    private final Strategy strategy = FACTORY.create("NIFTY", Snapshots.SESSION);

    /** Just under the ORH, then through it: a level cross. */
    private Decision cross(Strategy s, String before, String at, PositionView view) {
        s.decide(Snapshots.bullishCoil().at(before), view);
        return s.decide(Snapshots.bullishCoil().set("spot", 23126.0).at(at), view);
    }

    @Test
    void aStraddleTheTargetClosedBeforeTheStrategySawItOpenStillCountsAsTheDaysOne() {
        assertThat(cross(strategy, "12:40", "12:41", PositionView.FLAT).orders()).singleElement()
                .extracting(OrderIntent::action).isEqualTo(OrderIntent.Action.ENTER_STRADDLE);
        Decision after = cross(strategy, "12:42", "12:43", PositionView.flat(2));      // both legs closed at the target
        assertThat(after.orders()).isEmpty();
        assertThat(after.ce().stage()).isEqualTo(Stage.EXITED);
    }

    @Test
    void aRefusedStraddleMayTryAgainOnTheNextCross() {
        assertThat(cross(strategy, "12:40", "12:41", PositionView.FLAT).orders()).hasSize(1);
        assertThat(cross(strategy, "12:42", "12:43", PositionView.FLAT).orders()).as("nothing opened or closed").hasSize(1);
    }

    @Test
    void bothLegsAreSoldSixtyMinutesAfterTheEntryWhenTheTargetHasNotHit() {
        cross(strategy, "12:40", "12:41", PositionView.FLAT);
        var openedAt = Snapshots.SESSION.atTime(LocalTime.of(12, 41)).atZone(MarketTime.IST).toInstant();
        PositionView holding = new PositionView(true, OptionSide.CE, 75, 40.0, openedAt);
        assertThat(strategy.decide(Snapshots.bullishCoil().at("13:40"), holding).orders()).isEmpty();
        assertThat(strategy.decide(Snapshots.bullishCoil().at("13:41"), holding).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TIME_STOP"));
    }

    @Test
    void v14SellsBothLegsOnAFalseBreakOfTheCrossedLevel() {
        Strategy v14 = Strategies.load(Path.of("..", "config", "strategy", "expiry-level-straddle.v14.yaml"))
                .create("NIFTY", Snapshots.SESSION);
        for (int m = 0; m < 40; m++) {                                  // 12:00-12:39 just under the ORH, median volume 1000
            v14.decide(Snapshots.bullishCoil().set("futures.volumeLastMinute", 1000L).at(LocalTime.of(12, m) + ":30"),
                    PositionView.FLAT);
        }
        assertThat(v14.decide(Snapshots.bullishCoil().set("spot", 23126.0).set("futures.volumeLastMinute", 1000L)
                .at("12:40:30"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.targetPct()).as("no combined target").isNaN());
        var openedAt = Snapshots.SESSION.atTime(LocalTime.of(12, 40, 31)).atZone(MarketTime.IST).toInstant();
        PositionView holding = new PositionView(true, OptionSide.CE, 75, 40.0, openedAt, 30.0, 0, 0.9);
        // one snapshot a minute, so each is that minute's close; the volume is the minute just closed
        assertThat(v14.decide(Snapshots.bullishCoil().set("spot", 23110.0).set("futures.volumeLastMinute", 1000L)
                .at("12:41:30"), holding).orders()).as("12:40 closed above the ORH").isEmpty();
        assertThat(v14.decide(Snapshots.bullishCoil().set("spot", 23108.0).set("futures.volumeLastMinute", 900L)
                .at("12:42:30"), holding).orders()).as("one quiet close under it").isEmpty();
        assertThat(v14.decide(Snapshots.bullishCoil().set("spot", 23108.0).set("futures.volumeLastMinute", 900L)
                .at("12:43:30"), holding).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("FALSE_BREAK"));
    }

    @Test
    void aFileWithTheBoxBreakOnNeedsAFeaturesFileWithTheBox() {
        assertThat(FACTORY.requiredFeatureSections()).as("els-v12: box break on, as A-285 was written")
                .containsExactly("structure.box_minutes");
        assertThat(Strategies.load(Path.of("..", "config", "strategy", "expiry-level-straddle.v17.yaml"))
                .requiredFeatureSections()).as("els-v17: level crosses only").isEmpty();
    }
}
