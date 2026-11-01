package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.time.MarketTime;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;

/**
 * The A-287 market-data exits (els-v14), minute by minute. The snapshots carry only spot (no one-minute close from the
 * features, as in the engine with features v12): the exits build their own minutes.
 */
class StraddleExitsTest {

    private static final ThresholdConfig V14 =
            ThresholdConfig.load(Path.of("..", "config", "strategy", "expiry-level-straddle.v14.yaml"));
    private static final double TRIGGER = 23116;                     // entered on an up cross of the ORH
    private static final Instant OPENED = Snapshots.SESSION.atTime(LocalTime.of(12, 41)).atZone(MarketTime.IST).toInstant();

    private final StraddleExits exits = new StraddleExits(V14);

    private static PositionView holding(double valueRatio) {
        return new PositionView(true, OptionSide.CE, 75, 40.0, OPENED, 30.0, 0, valueRatio);
    }

    private String at(String time, double spot, long volumeLastMinute, String ceOiFlow, PositionView view) {
        return exits.update(Snapshots.bullishCoil().set("spot", spot).set("futures.volumeLastMinute", volumeLastMinute)
                .set("options.atmIv", 0.15).set("options.ceOiFlow", ceOiFlow).set("options.expiry", Snapshots.SESSION.toString())
                .at(time), view, TRIGGER, true);
    }

    /**
     * Minute {@code hhmm} trades at {@code prices} (the last is its close) on {@code volume} futures; returns what the
     * first snapshot of the next minute decides.
     */
    private String minute(String hhmm, long volume, PositionView view, double... prices) {
        for (int i = 0; i < prices.length; i++) {
            at(hhmm + ":" + (10 + 10 * i), prices[i], 0, null, view);
        }
        return at(LocalTime.parse(hhmm).plusMinutes(1) + ":00", prices[prices.length - 1], volume, null, view);
    }

    /** Minutes 12:00 to 12:{@code until} (exclusive), closing {@code swing} points either side of 23150. */
    private void session(int until, double swing, PositionView held) {
        for (int m = 0; m < until; m++) {
            minute(LocalTime.of(12, m).toString(), 1000, m < 41 ? PositionView.FLAT : held, 23150 + (m % 2 == 0 ? swing : -swing));
        }
    }

    @Test
    void aQuietIndexAfterTenMinutesIsTheta() {
        session(49, 1, holding(0.9));
        assertThat(minute("12:49", 1000, holding(0.9), 23149)).as("12:50:00, nine minutes old").isNull();
        assertThat(minute("12:50", 1000, holding(0.9), 23151)).isEqualTo("THETA");
    }

    @Test
    void anIndexMovingFasterThanTheIvChargesIsHeld() {
        session(52, 30, holding(0.9));                               // +-30 points a minute is far above 15 % IV
        assertThat(minute("12:52", 1000, holding(0.9), 23180)).isNull();
    }

    @Test
    void twoQuietClosesBackUnderTheTriggerAreAFalseBreak() {
        session(44, 30, holding(0.9));
        assertThat(minute("12:44", 900, holding(0.9), 23110)).isNull();
        assertThat(minute("12:45", 900, holding(0.9), 23108)).isEqualTo("FALSE_BREAK");
    }

    @Test
    void aReturnUnderTheTriggerOnHeavyVolumeIsAReversalAndHeld() {
        session(44, 30, holding(0.9));
        assertThat(minute("12:44", 5000, holding(0.9), 23110)).isNull();
        assertThat(minute("12:45", 5000, holding(0.9), 23108)).isNull();
    }

    @Test
    void aClimaxMinuteClosingAgainstTheMoveTakesTheProfit() {
        session(44, 30, holding(1.4));
        assertThat(minute("12:44", 5000, holding(1.4), 23200, 23150, 23125)).isEqualTo("CLIMAX");   // 5x median, close at 6 %
    }

    @Test
    void theSameClimaxWithTheStraddleUnderwaterIsNotAProfitExit() {
        session(44, 30, holding(0.8));
        assertThat(minute("12:44", 5000, holding(0.8), 23200, 23150, 23125)).isNull();
    }

    @Test
    void reachingThePdhAndClosingBackUnderItWhileCallsAreWrittenIsARejection() {
        session(44, 30, holding(1.3));
        at("12:44:10", 23205, 0, null, holding(1.3));
        at("12:44:50", 23190, 0, null, holding(1.3));
        assertThat(at("12:45:00", 23190, 1000, "BUILD", holding(1.3))).isEqualTo("REJECTED_PDH");   // PDH 23200
    }

    @Test
    void theSameRejectionWithoutCallWritingIsHeld() {
        session(44, 30, holding(1.3));
        at("12:44:10", 23205, 0, null, holding(1.3));
        at("12:44:50", 23190, 0, null, holding(1.3));
        assertThat(at("12:45:00", 23190, 1000, "UNWIND", holding(1.3))).isNull();
    }

    @Test
    void aLevelReachedOnlyBeforeTheEntryIsNotARejection() {
        session(40, 30, PositionView.FLAT);
        minute("12:40", 1000, PositionView.FLAT, 23205);              // PDH touched before the entry
        at("12:41:10", 23180, 0, null, holding(1.3));
        assertThat(at("12:42:00", 23190, 1000, "BUILD", holding(1.3))).isNull();
    }

    @Test
    void nothingFiresWithoutAPosition() {
        session(55, 1, PositionView.FLAT);
        assertThat(minute("12:55", 900, PositionView.FLAT, 23100)).isNull();
    }

    // ---------------------------------------------------------------- els-v17: the review fixes

    private static final ThresholdConfig V17 =
            ThresholdConfig.load(Path.of("..", "config", "strategy", "expiry-level-straddle.v17.yaml"));

    /** A 23150 straddle bought for 60 (30 + 30), {@code valueRatio} of that now, both legs bid 30. */
    private static PositionView straddle(double valueRatio) {
        return new PositionView(true, OptionSide.CE, 75, 30.0, OPENED, 30.0, 0, valueRatio,
                java.util.List.of(new PositionView.Leg(OptionSide.CE, 23150, 4875, 30.0, 30.0),
                        new PositionView.Leg(OptionSide.PE, 23150, 4875, 30.0, 30.0)));
    }

    @Test
    void v17ThetaSellsWhenTheHeldStrikesAreWorthLessAtTheRealizedVolatilityThanTheirBids() {
        StraddleExits v17 = new StraddleExits(V17);
        for (int m = 0; m < 51; m++) {                                   // +-1 point a minute: ~6 % realized
            double spot = 23150 + (m % 2 == 0 ? 1 : -1);
            String hhmm = LocalTime.of(12, m).toString();
            PositionView view = m < 41 ? PositionView.FLAT : straddle(1.0);
            String exit = v17.update(snapshot(hhmm + ":30", spot), view, TRIGGER, true);
            if (m == 51 - 1) {
                // 12:50:30 closes 12:49, nine minutes old: not yet
                assertThat(exit).isNull();
            }
        }
        // 12:51:30, ten minutes old: the 23150 pair at ~6 % with 159 minutes left is worth ~20, the bids pay 60
        assertThat(v17.update(snapshot("12:51:30", 23151), straddle(1.0), TRIGGER, true)).isEqualTo("THETA");
    }

    @Test
    void v17ThetaHoldsWhileTheIndexMovesEnoughToEarnTheBids() {
        StraddleExits v17 = new StraddleExits(V17);
        for (int m = 0; m < 52; m++) {                                   // +-30 points a minute
            double spot = 23150 + (m % 2 == 0 ? 30 : -30);
            assertThat(v17.update(snapshot(LocalTime.of(12, m) + ":30", spot), m < 41 ? PositionView.FLAT : straddle(1.0),
                    TRIGGER, true)).isNull();
        }
    }

    @Test
    void v17TrailsOnlyAPivotOfTheMoveSinceTheEntry() {
        StraddleExits v17 = new StraddleExits(V17);
        for (int m = 0; m < 40; m++) {
            v17.update(snapshot(LocalTime.of(12, m) + ":30", 23150 + (m % 2 == 0 ? 30 : -30)), PositionView.FLAT, TRIGGER, true);
        }
        v17.update(snapshot("12:40:30", 23150), PositionView.FLAT, TRIGGER, true);
        // minute closes 23200, 23230, 23220, 23210 (dipping to 23195), 23240, 23260: the 12:44 low 23195 is a pivot
        double[][] minutes = {{23200}, {23230}, {23220}, {23195, 23210}, {23240}, {23260}};
        for (int i = 0; i < minutes.length; i++) {
            String hhmm = LocalTime.of(12, 41 + i).toString();
            for (int j = 0; j < minutes[i].length; j++) {
                assertThat(v17.update(snapshot(hhmm + ":" + (30 + 10 * j), minutes[i][j]), straddle(1.3), TRIGGER, true))
                        .isNull();
            }
        }
        // 12:47 closes at 23190, under the pivot: the move's structure broke
        assertThat(v17.update(snapshot("12:47:30", 23190), straddle(1.3), TRIGGER, true)).isNull();
        assertThat(v17.update(snapshot("12:48:30", 23190), straddle(1.3), TRIGGER, true)).isEqualTo("SWING_TRAIL");
    }

    @Test
    void v17IgnoresTheEnginesSwingFromBeforeTheEntry() {
        StraddleExits v17 = new StraddleExits(V17);
        for (int m = 0; m < 46; m++) {                                   // a steady climb, no pivot since the entry
            v17.update(snapshotBuilder(23120 + 2 * m).set("structure.lastSwingLow", 23300.0).at(LocalTime.of(12, m) + ":30"),
                    m < 41 ? PositionView.FLAT : straddle(1.3), TRIGGER, true);
        }
        assertThat(v17.update(snapshotBuilder(23215).set("structure.lastSwingLow", 23300.0).at("12:46:30"),
                straddle(1.3), TRIGGER, true)).as("v14 would trail on the stale 23300 swing").isNull();
    }

    private static com.autotrade.features.snapshot.FeatureSnapshot snapshot(String time, double spot) {
        return snapshotBuilder(spot).at(time);
    }

    private static Snapshots snapshotBuilder(double spot) {
        return Snapshots.bullishCoil().set("spot", spot).set("futures.volumeLastMinute", 1000L).set("options.atmIv", 0.15)
                .set("options.expiry", Snapshots.SESSION.toString());
    }
}
