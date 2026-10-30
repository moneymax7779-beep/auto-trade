package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/** A-270 gfd-v1: fade a >= 0.4 % opening gap at 09:31, target the previous close, stop beyond the opening range. */
class GapFadeTest {

    private static Strategy load() {
        return Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v1.yaml")).create("NIFTY", Snapshots.SESSION);
    }

    /** Previous close 22,700; the opening range 22,790-22,840. */
    private static Snapshots snap(double spot, double open) {
        return new Snapshots().set("spot", spot).set("structure.prevClose", 22700.0).set("structure.dayOpen", open)
                .set("structure.orComplete", true).set("structure.orHigh", 22840.0).set("structure.orLow", 22790.0)
                .set("regime.dteTradingDays", 0);
    }

    private static final PositionView PUT = new PositionView(true, OptionSide.PE, 1, 100, Instant.parse("2026-10-29T04:01:00Z"));

    @Test
    void aGapUpIsFadedWithAPutAtTheFirstSnapshotOfTheWindowOnly() {
        Strategy s = load();
        assertThat(s.decide(snap(22800, 22813.5).at("09:30"), PositionView.FLAT).orders()).as("before 09:31").isEmpty();
        assertThat(s.decide(snap(22800, 22813.5).at("09:31"), PositionView.FLAT).orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("GAP_UP_FADE");
        });
        assertThat(s.decide(snap(22800, 22813.5).at("09:32"), PositionView.FLAT).orders()).as("one trade a day").isEmpty();
    }

    @Test
    void theGapFilledIsTheTargetAndAMinuteCloseAboveTheRangeTheStop() {
        Strategy target = load();
        target.decide(snap(22800, 22813.5).at("09:31"), PositionView.FLAT);
        assertThat(target.decide(snap(22760, 22813.5).at("09:40"), PUT).orders()).as("not filled yet").isEmpty();
        assertThat(target.decide(snap(22699, 22813.5).at("09:55"), PUT).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TARGET"));

        Strategy stop = load();
        stop.decide(snap(22800, 22813.5).at("09:31"), PositionView.FLAT);
        assertThat(stop.decide(snap(22845, 22813.5).at("09:33"), PUT).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("STOP"));
    }

    @Test
    void aGapDownBuysTheCallAndASmallGapNothing() {
        assertThat(load().decide(snap(22620, 22600).at("09:31"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
        assertThat(load().decide(snap(22760, 22768).at("09:31"), PositionView.FLAT).orders()).as("+0.30 %").isEmpty();
    }

    /** A-272 gfd-v2: after a gap up the put needs calls written >= 1.5x puts over ATM +- 5 in the last 2 minutes. */
    @Test
    void theOiGateLetsTheFadeThroughOnlyWhenTheFadingSidesWritersLead() {
        java.util.function.BiFunction<Double, Double, Snapshots> gapUp = (calls, puts) -> snap(22800, 22813.5)
                .set("options.atmBand5CallChange2m", calls).set("options.atmBand5PutChange2m", puts);
        Strategy v2 = Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v2.yaml")).create("NIFTY", Snapshots.SESSION);
        assertThat(v2.decide(gapUp.apply(4_680_000.0, 1_680_000.0).at("09:31"), PositionView.FLAT).orders()).as("15 Sep NIFTY")
                .singleElement().satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));
        for (double[] cp : new double[][] {{100_000, 100_000}, {140_000, 100_000}, {-50_000, -200_000}, {Double.NaN, 0}}) {
            Strategy blocked = Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v2.yaml")).create("NIFTY", Snapshots.SESSION);
            assertThat(blocked.decide(gapUp.apply(cp[0], cp[1]).at("09:31"), PositionView.FLAT).orders())
                    .as("calls %s, puts %s", cp[0], cp[1]).isEmpty();
        }
        Strategy fallingPuts = Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v2.yaml")).create("NIFTY", Snapshots.SESSION);
        assertThat(fallingPuts.decide(gapUp.apply(50_000.0, -300_000.0).at("09:31"), PositionView.FLAT).orders())
                .as("puts unwinding count as 0").hasSize(1);
        Strategy gapDown = Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v2.yaml")).create("NIFTY", Snapshots.SESSION);
        assertThat(gapDown.decide(snap(22620, 22600).set("options.atmBand5CallChange2m", 50_000.0)
                .set("options.atmBand5PutChange2m", 400_000.0).at("09:31"), PositionView.FLAT).orders())
                .as("gap down, puts written 8x calls").singleElement().satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
        assertThat(load().decide(gapUp.apply(0.0, 900_000.0).at("09:31"), PositionView.FLAT).orders())
                .as("v1 has no gate").hasSize(1);
    }

    /** A-274 gfd-v3: after the entry, the session's low reaching the previous close exits the put at that snapshot. */
    @Test
    void v3ExitsOnATouchOfThePreviousCloseWithoutAMinuteCloseThere() {
        Strategy v3 = Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v3.yaml")).create("NIFTY", Snapshots.SESSION);
        assertThat(v3.decide(snap(22800, 22813.5).set("structure.dayLow", 22780.0).set("structure.dayHigh", 22850.0)
                .set("options.atmBand5CallChange2m", 300_000.0).set("options.atmBand5PutChange2m", 100_000.0).at("09:31"),
                PositionView.FLAT).orders()).hasSize(1);
        assertThat(v3.decide(snap(22760, 22813.5).set("structure.dayLow", 22740.0).set("structure.dayHigh", 22850.0).at("09:40"), PUT)
                .orders()).as("a new low, not yet the previous close").isEmpty();
        assertThat(v3.decide(snap(22720, 22813.5).set("structure.dayLow", 22695.0).set("structure.dayHigh", 22850.0).at("09:41"), PUT)
                .orders()).as("touched 22,695 <= 22,700, closed the minute at 22,720").singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TARGET_TOUCH"));

        Strategy v2 = Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v2.yaml")).create("NIFTY", Snapshots.SESSION);
        v2.decide(snap(22800, 22813.5).set("structure.dayLow", 22780.0).set("structure.dayHigh", 22850.0)
                .set("options.atmBand5CallChange2m", 300_000.0).set("options.atmBand5PutChange2m", 100_000.0).at("09:31"), PositionView.FLAT);
        assertThat(v2.decide(snap(22720, 22813.5).set("structure.dayLow", 22695.0).set("structure.dayHigh", 22850.0).at("09:41"), PUT)
                .orders()).as("v2 waits for a minute close").isEmpty();
    }

    private static Strategy v4(String v) {
        return Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v4" + v + ".yaml")).create("NIFTY", Snapshots.SESSION);
    }

    /** A gap-up minute: spot, the 5-minute ATM +- 5 call / put OI change, the futures OI state; day low 22,780, VWAP 22,805. */
    private static Snapshots minute(double spot, double calls, double puts, String futures) {
        return snap(spot, 22813.5).set("structure.dayLow", 22780.0).set("structure.dayHigh", 22835.0)
                .set("structure.vwapSpotProxy", 22805.0).set("options.atmBand5CallChange5m", calls)
                .set("options.atmBand5PutChange5m", puts).set("futures.oiState", futures);
    }

    /** A-275 gfd-v4a: no fixed minute; enters on the second snapshot in a row with the gate on, all checks holding. */
    @Test
    void v4EntersOnTheFirstMinuteTheEvidenceHoldsNotAtAFixedTime() {
        Strategy a = v4("a");
        assertThat(a.decide(minute(22800, 50_000, 100_000, "NEUTRAL").at("09:31"), PositionView.FLAT).orders()).as("gate off").isEmpty();
        assertThat(a.decide(minute(22800, 300_000, 100_000, "NEUTRAL").at("09:32"), PositionView.FLAT).orders())
                .as("gate on once: not yet persistent").isEmpty();
        assertThat(a.decide(minute(22800, 320_000, 90_000, "FRESH_LONG").at("09:33"), PositionView.FLAT).orders())
                .as("futures building longs: no").isEmpty();
        assertThat(a.decide(minute(22800, 330_000, 90_000, "LONG_UNWIND").at("09:34"), PositionView.FLAT).orders())
                .as("09:34: gate on twice, futures unwinding, below the open").singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));

        Strategy priceNo = v4("a");
        priceNo.decide(minute(22820, 300_000, 100_000, "NEUTRAL").at("09:31"), PositionView.FLAT);
        assertThat(priceNo.decide(minute(22820, 300_000, 100_000, "NEUTRAL").at("09:32"), PositionView.FLAT).orders())
                .as("22,820 is above the open and VWAP: no price confirmation").isEmpty();
        Strategy c = v4("c");
        c.decide(minute(22820, 300_000, 100_000, "NEUTRAL").at("09:31"), PositionView.FLAT);
        assertThat(c.decide(minute(22820, 300_000, 100_000, "NEUTRAL").at("09:32"), PositionView.FLAT).orders())
                .as("v4c has no price check").hasSize(1);
        Strategy b = v4("b");
        b.decide(minute(22800, 300_000, 100_000, "FRESH_LONG").at("09:31"), PositionView.FLAT);
        assertThat(b.decide(minute(22800, 300_000, 100_000, "FRESH_LONG").at("09:32"), PositionView.FLAT).orders())
                .as("v4b has no futures check").hasSize(1);
    }

    @Test
    void v4NeverEntersOnceTheGapFilledOrTheRangeBroke() {
        Strategy broken = v4("a");
        broken.decide(minute(22845, 300_000, 100_000, "NEUTRAL").at("09:31"), PositionView.FLAT);   // closed above ORH 22,840
        assertThat(broken.decide(minute(22800, 300_000, 100_000, "NEUTRAL").at("09:40"), PositionView.FLAT).orders()).isEmpty();
        Strategy filled = v4("a");
        filled.decide(minute(22760, 300_000, 100_000, "NEUTRAL").set("structure.dayLow", 22690.0).at("09:31"), PositionView.FLAT);
        assertThat(filled.decide(minute(22760, 300_000, 100_000, "NEUTRAL").set("structure.dayLow", 22690.0).at("09:32"),
                PositionView.FLAT).orders()).as("the low reached the previous close: no gap left").isEmpty();
        Strategy late = v4("a");
        late.decide(minute(22800, 300_000, 100_000, "NEUTRAL").at("10:59"), PositionView.FLAT);
        late.decide(minute(22800, 300_000, 100_000, "NEUTRAL").at("11:00"), PositionView.FLAT);
        assertThat(late.decide(minute(22800, 300_000, 100_000, "NEUTRAL").at("11:01"), PositionView.FLAT).orders())
                .as("after the window").isEmpty();
    }

    private static Strategy v5(String v) {
        return Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v5" + v + ".yaml")).create("NIFTY", Snapshots.SESSION);
    }

    /** A-276 near-fill: open 22,813.5, previous close 22,700: 85 % of the gap is filled at 22,717.0 (touch or close). */
    @Test
    void nearFillExitsWhenEightyFivePercentOfTheGapHasFilled() {
        Strategy s = v5("a");
        assertThat(s.decide(snap(22800, 22813.5).set("structure.dayLow", 22780.0).set("structure.dayHigh", 22850.0)
                .set("options.atmBand5CallChange2m", 300_000.0).set("options.atmBand5PutChange2m", 100_000.0).at("09:31"),
                PositionView.FLAT).orders()).hasSize(1);
        assertThat(s.decide(snap(22725, 22813.5).set("structure.dayLow", 22720.0).set("structure.dayHigh", 22850.0).at("09:50"), PUT)
                .orders()).as("22,720: 82 % filled").isEmpty();
        assertThat(s.decide(snap(22730, 22813.5).set("structure.dayLow", 22715.0).set("structure.dayHigh", 22850.0).at("09:51"), PUT)
                .orders()).as("touched 22,715 <= 22,717.0").singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TARGET_TOUCH"));
    }

    /** A-276 give-back: armed at +40 % on the average premium, exits when the bid keeps only half the peak gain. */
    @Test
    void giveBackKeepsHalfThePeakGainOnceArmed() {
        Strategy s = v5("b");
        s.decide(snap(22800, 22813.5).set("structure.dayLow", 22780.0).set("structure.dayHigh", 22850.0)
                .set("options.atmBand5CallChange2m", 300_000.0).set("options.atmBand5PutChange2m", 100_000.0).at("09:31"), PositionView.FLAT);
        java.util.function.DoubleFunction<PositionView> put = bid -> new PositionView(true, OptionSide.PE, 1, 100,
                Instant.parse("2026-10-29T04:01:00Z"), bid);
        Snapshots quiet = snap(22760, 22813.5).set("structure.dayLow", 22740.0).set("structure.dayHigh", 22850.0);
        assertThat(s.decide(quiet.at("09:40"), put.apply(135)).orders()).as("+35 %: not armed").isEmpty();
        assertThat(s.decide(quiet.at("09:41"), put.apply(150)).orders()).as("+50 %: armed, peak 150").isEmpty();
        assertThat(s.decide(quiet.at("09:42"), put.apply(126)).orders()).as("126 > 125 = 100 + half of 50").isEmpty();
        assertThat(s.decide(quiet.at("09:43"), put.apply(125)).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("GIVEBACK"));
    }

    /** A-277 runner (v6a): the fill does not end the trade; a minute close above EMA20 does (a put). */
    @Test
    void theRunnerHoldsThroughTheFillAndExitsOnItsTrail() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "gap-fade.v6a.yaml")).create("NIFTY", Snapshots.SESSION);
        s.decide(snap(22800, 22813.5).set("structure.dayLow", 22780.0).set("structure.dayHigh", 22850.0)
                .set("options.atmBand5CallChange5m", 300_000.0).set("options.atmBand5PutChange5m", 100_000.0).at("09:31"), PositionView.FLAT);
        assertThat(s.decide(snap(22795, 22813.5).set("structure.dayLow", 22780.0).set("structure.dayHigh", 22850.0)
                .set("options.atmBand5CallChange5m", 300_000.0).set("options.atmBand5PutChange5m", 100_000.0).at("09:32"), PositionView.FLAT)
                .orders()).as("v4b: the gate on two snapshots").singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));
        Snapshots below = snap(22690, 22813.5).set("structure.dayLow", 22685.0).set("structure.dayHigh", 22850.0)
                .set("structure.ema20", 22720.0).set("structure.atr1m", 10.0);
        assertThat(s.decide(below.at("09:55"), PUT).orders()).as("filled: held").isEmpty();
        assertThat(s.decide(below.set("spot", 22650.0).at("10:10"), PUT).orders()).as("below EMA20: held").isEmpty();
        assertThat(s.decide(below.set("spot", 22725.0).at("10:40"), PUT).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("RUN_TRAIL"));
    }
}
