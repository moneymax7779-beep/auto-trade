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

    @Test
    void v2UsesTheWallsOiDropOverTenMinutesSoStaleBseOiStillCounts() {
        Strategy v2 = Strategies.load(Path.of("..", "config", "strategy", "wall-break-rider.v2.yaml")).create("SENSEX", Snapshots.SESSION);
        Snapshots dropping = covering(false).set("options.callWallOiChangePct", -6.0).set("structure.spotChange3m", 40.0);
        assertThat(v2.decide(dropping.at("12:40"), PositionView.FLAT).orders()).isEmpty();
        assertThat(v2.decide(dropping.at("12:41"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
        Strategy shallow = Strategies.load(Path.of("..", "config", "strategy", "wall-break-rider.v2.yaml")).create("SENSEX", Snapshots.SESSION);
        Snapshots small = covering(false).set("options.callWallOiChangePct", -3.0).set("structure.spotChange3m", 40.0);
        shallow.decide(small.at("12:40"), PositionView.FLAT);
        assertThat(shallow.decide(small.at("12:41"), PositionView.FLAT).orders()).as("a 3 % drop is not enough").isEmpty();
        Strategy away = Strategies.load(Path.of("..", "config", "strategy", "wall-break-rider.v2.yaml")).create("SENSEX", Snapshots.SESSION);
        Snapshots falling = covering(false).set("options.callWallOiChangePct", -6.0).set("structure.spotChange3m", -40.0);
        away.decide(falling.at("12:40"), PositionView.FLAT);
        assertThat(away.decide(falling.at("12:41"), PositionView.FLAT).orders()).as("index moving away from the wall").isEmpty();
    }

    // ------------------------------------------------------------------ v3 (ledger A-327)

    private static Strategy v3() {
        return Strategies.load(Path.of("..", "config", "strategy", "wall-break-rider.v3.yaml")).create("NIFTY", Snapshots.SESSION);
    }

    private static Snapshots callSignal(double spot) {
        return covering(false).set("options.callWallOiChangePct", -6.0).set("structure.spotChange3m", 5.0).set("spot", spot)
                .set("options.callBarrierStrike", 23150.0);
    }

    private static Snapshots putSignal() {
        return covering(false).set("options.putFloorOiChangePct", -6.0).set("options.callWallOiChangePct", 0.0)
                .set("structure.spotChange3m", -5.0).set("structure.vwapSpotProxy", 23300.0).set("futures.oiState", "NEUTRAL");
    }

    private static java.time.Instant at(int hour, int minute) {
        return Snapshots.SESSION.atTime(java.time.LocalTime.of(hour, minute)).atZone(com.autotrade.core.time.MarketTime.IST).toInstant();
    }

    @Test
    void v3Loads() {
        StrategyFactory f = Strategies.load(Path.of("..", "config", "strategy", "wall-break-rider.v3.yaml"));
        assertThat(f.version()).isEqualTo("wbr-v3");
    }

    @Test
    void v3SkipsAnEntryAfterAMoreThanFourAtrMoveInThirtyMinutes() {
        Strategy late = v3();
        late.decide(callSignal(23000).set("options.callWallOiChangePct", 0.0).at("12:10"), PositionView.FLAT);
        late.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        assertThat(late.decide(callSignal(23115).at("12:41"), PositionView.FLAT).orders()).as("11.5 ATR in 30 min").isEmpty();
        Strategy fine = v3();
        fine.decide(callSignal(23090).set("options.callWallOiChangePct", 0.0).at("12:10"), PositionView.FLAT);
        fine.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        assertThat(fine.decide(callSignal(23115).at("12:41"), PositionView.FLAT).orders()).as("2.5 ATR").singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
    }

    @Test
    void v3WaitsAnHourBeforeTheOtherSideAfterALoss() {
        Strategy s = v3();
        s.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        assertThat(s.decide(callSignal(23115).at("12:41"), PositionView.FLAT).orders()).hasSize(1);
        s.decide(covering(false).at("12:50"), new PositionView(true, OptionSide.CE, 1, 100, at(12, 41), 70));
        s.decide(putSignal().at("12:51"), PositionView.FLAT);                         // closed below its cost: a loss
        s.decide(putSignal().at("12:52"), PositionView.FLAT);
        assertThat(s.decide(putSignal().at("12:53"), PositionView.FLAT).orders()).as("puts blocked till 13:51").isEmpty();
        s.decide(putSignal().at("13:51"), PositionView.FLAT);
        assertThat(s.decide(putSignal().at("13:52"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));

        Strategy won = v3();
        won.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        won.decide(callSignal(23115).at("12:41"), PositionView.FLAT);
        won.decide(covering(false).at("12:50"), new PositionView(true, OptionSide.CE, 1, 100, at(12, 41), 130));
        won.decide(putSignal().at("12:51"), PositionView.FLAT);                     // the close resets the streak
        won.decide(putSignal().at("12:52"), PositionView.FLAT);
        assertThat(won.decide(putSignal().at("12:53"), PositionView.FLAT).orders()).as("after a win: no block").hasSize(1);
    }

    @Test
    void v3ExitsWhenTheIndexFallsBackThroughTheWallItCrossedAndHasNoNoProgressExit() {
        Strategy s = v3();
        s.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        s.decide(callSignal(23115).at("12:41"), PositionView.FLAT);                 // entry wall 23150
        PositionView held = new PositionView(true, OptionSide.CE, 1, 100, at(12, 41), 101);
        assertThat(s.decide(covering(false).at("13:05"), held).orders()).as("no no-progress exit after 24 min").isEmpty();
        assertThat(s.decide(covering(false).set("spot", 23160.0).set("structure.lastBarClose", 23160.0).at("13:06"), held)
                .orders()).as("crossed the wall").isEmpty();
        assertThat(s.decide(covering(false).set("spot", 23140.0).set("structure.lastBarClose", 23140.0).at("13:09"), held)
                .orders()).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("WALL_LOST"));

        Strategy stale = v3();                         // crossing while the last 3-minute close is still below the wall
        stale.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        stale.decide(callSignal(23115).at("12:41"), PositionView.FLAT);
        assertThat(stale.decide(covering(false).set("spot", 23160.0).set("structure.lastBarClose", 23140.0).at("13:07"), held)
                .orders()).as("the bar before the crossing does not count").isEmpty();
        assertThat(stale.decide(covering(false).set("spot", 23170.0).set("structure.lastBarClose", 23140.0).at("13:08"), held)
                .orders()).as("still no newer bar").isEmpty();
        assertThat(stale.decide(covering(false).set("spot", 23165.0).set("structure.lastBarClose", 23165.0).at("13:09"), held)
                .orders()).as("a new close above the wall").isEmpty();
        assertThat(stale.decide(covering(false).set("spot", 23130.0).set("structure.lastBarClose", 23130.0).at("13:12"), held)
                .orders()).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("WALL_LOST"));

        Strategy never = v3();
        never.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        never.decide(callSignal(23115).at("12:41"), PositionView.FLAT);
        assertThat(never.decide(covering(false).set("structure.lastBarClose", 23140.0).at("13:09"), held).orders())
                .as("below a wall it never crossed: no wall exit").isEmpty();
    }

    // ------------------------------------------------------------------ A-328 versions

    private static Strategy version(String v) {
        return Strategies.load(Path.of("..", "config", "strategy", "wall-break-rider." + v + ".yaml")).create("NIFTY", Snapshots.SESSION);
    }

    @Test
    void v4SkipsAnEntryMoreThanSixAtrFromVwap() {
        Strategy far = version("v4");                     // spot 23115, VWAP 23000, ATR 10: 11.5 ATR above VWAP
        far.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        assertThat(far.decide(callSignal(23115).at("12:41"), PositionView.FLAT).orders()).isEmpty();
        Strategy near = version("v4");
        Snapshots close = callSignal(23115).set("structure.vwapSpotProxy", 23080.0);  // 3.5 ATR
        near.decide(close.at("12:40"), PositionView.FLAT);
        assertThat(near.decide(close.at("12:41"), PositionView.FLAT).orders()).hasSize(1);
    }

    @Test
    void v5TakesNoNewEntryAfterQuarterPastOne() {
        Strategy s = version("v5");
        s.decide(callSignal(23115).at("13:15"), PositionView.FLAT);
        assertThat(s.decide(callSignal(23115).at("13:16"), PositionView.FLAT).orders()).isEmpty();
        Strategy early = version("v5");
        early.decide(callSignal(23115).at("13:13"), PositionView.FLAT);
        assertThat(early.decide(callSignal(23115).at("13:14"), PositionView.FLAT).orders()).hasSize(1);
    }

    @Test
    void v6SkipsAnEntryWhoseWallIsUnderHalfAnAtrAway() {
        Strategy tight = version("v6");                   // wall 23118 vs spot 23115: 0.3 ATR
        Snapshots close = callSignal(23115).set("options.callBarrierStrike", 23118.0);
        tight.decide(close.at("12:40"), PositionView.FLAT);
        assertThat(tight.decide(close.at("12:41"), PositionView.FLAT).orders()).isEmpty();
        Strategy room = version("v6");                    // wall 23150: 3.5 ATR
        room.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        assertThat(room.decide(callSignal(23115).at("12:41"), PositionView.FLAT).orders()).hasSize(1);
    }

    @Test
    void v7TakesOneTradeADay() {
        Strategy s = version("v7");
        s.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        assertThat(s.decide(callSignal(23115).at("12:41"), PositionView.FLAT).orders()).hasSize(1);
        s.decide(covering(false).at("12:50"), new PositionView(true, OptionSide.CE, 1, 100, at(12, 41), 150));
        s.decide(callSignal(23115).at("12:51"), PositionView.FLAT);                    // closed with a gain
        s.decide(callSignal(23115).at("12:52"), PositionView.FLAT);
        assertThat(s.decide(callSignal(23115).at("12:53"), PositionView.FLAT).orders()).as("the day's trade is used").isEmpty();
        Strategy unfilled = version("v7");                // an entry that never filled does not count
        unfilled.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        assertThat(unfilled.decide(callSignal(23115).at("12:41"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(unfilled.decide(callSignal(23115).at("12:42"), PositionView.FLAT).orders()).hasSize(1);
    }

    // ------------------------------------------------------------------ A-329 versions

    @Test
    void v8TakesNoNewEntryFromHalfPastOneAfterAWinningTrade() {
        Strategy s = version("v8");
        s.decide(callSignal(23115).at("13:20"), PositionView.FLAT);
        assertThat(s.decide(callSignal(23115).at("13:21"), PositionView.FLAT).orders()).hasSize(1);
        s.decide(covering(false).at("13:28"), new PositionView(true, OptionSide.CE, 1, 100, at(13, 21), 150));
        s.decide(callSignal(23115).at("13:29"), PositionView.FLAT);                    // closed with a gain
        s.decide(callSignal(23115).at("13:30"), PositionView.FLAT);
        assertThat(s.decide(callSignal(23115).at("13:31"), PositionView.FLAT).orders()).as("after the win, from 13:30").isEmpty();

        Strategy before = version("v8");                  // a re-entry before 13:30 is still allowed
        before.decide(callSignal(23115).at("12:40"), PositionView.FLAT);
        before.decide(callSignal(23115).at("12:41"), PositionView.FLAT);
        before.decide(covering(false).at("12:50"), new PositionView(true, OptionSide.CE, 1, 100, at(12, 41), 150));
        before.decide(callSignal(23115).at("12:51"), PositionView.FLAT);
        before.decide(callSignal(23115).at("12:52"), PositionView.FLAT);
        assertThat(before.decide(callSignal(23115).at("12:53"), PositionView.FLAT).orders()).hasSize(1);

        Strategy lost = version("v8");                    // after a loss the late entry stays allowed
        lost.decide(callSignal(23115).at("13:20"), PositionView.FLAT);
        lost.decide(callSignal(23115).at("13:21"), PositionView.FLAT);
        lost.decide(covering(false).at("13:28"), new PositionView(true, OptionSide.CE, 1, 100, at(13, 21), 70));
        lost.decide(callSignal(23115).at("13:29"), PositionView.FLAT);
        lost.decide(callSignal(23115).at("13:30"), PositionView.FLAT);
        assertThat(lost.decide(callSignal(23115).at("13:31"), PositionView.FLAT).orders()).hasSize(1);
    }

    @Test
    void v9SkipsAnEntryWhenTheAtmIvOfItsSideIsThirtyFivePercentOrMore() {
        Strategy dear = version("v9");
        Snapshots high = callSignal(23115).set("options.atmCeIv", 0.37).set("options.atmPeIv", 0.20);
        dear.decide(high.at("12:40"), PositionView.FLAT);
        assertThat(dear.decide(high.at("12:41"), PositionView.FLAT).orders()).isEmpty();
        Strategy fair = version("v9");
        Snapshots low = callSignal(23115).set("options.atmCeIv", 0.29).set("options.atmPeIv", 0.40);
        fair.decide(low.at("12:40"), PositionView.FLAT);
        assertThat(fair.decide(low.at("12:41"), PositionView.FLAT).orders()).as("the call side's IV counts").hasSize(1);
        assertThat(version("v10")).isNotNull();
    }
}
