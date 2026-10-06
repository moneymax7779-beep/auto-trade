package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

class SetupFamiliesTest {

    /** Feeds minutes to one strategy: the day high / low follow the closes; fixed levels; ATR 20, VWAP 22,600 by default. */
    private static final class Day {
        final Strategy strategy;
        final Map<String, Object> levels;
        double high = Double.NaN, low = Double.NaN;
        int dte = 1;

        Day(String file, Map<String, Object> levels) {
            StrategyFactory f = Strategies.load(Path.of("..", "config", "strategy", file));
            this.strategy = f.create("NIFTY", Snapshots.SESSION);
            this.levels = levels;
        }

        Decision at(String t, double spot, PositionView p, Object... extra) {
            high = Double.isNaN(high) ? spot : Math.max(high, spot);
            low = Double.isNaN(low) ? spot : Math.min(low, spot);
            Snapshots s = new Snapshots().set("spot", spot).set("structure.dayHigh", high).set("structure.dayLow", low)
                    .set("structure.atr3m", 20.0).set("structure.vwapSpotProxy", 22600.0).set("regime.dteTradingDays", dte);
            levels.forEach(s::set);
            for (int i = 0; i + 1 < extra.length; i += 2) {
                s.set((String) extra[i], extra[i + 1]);
            }
            return strategy.decide(s.at(t), p);
        }

        Decision at(String t, double spot) {
            return at(t, spot, PositionView.FLAT);
        }
    }

    private static final Map<String, Object> PRIOR = Map.of("structure.prevOrHigh", 22571.0, "structure.prevOrLow", 22507.0,
            "structure.pdh", 22621.0, "structure.pdl", 22397.0, "structure.prevClose", 22555.0, "structure.dayOpen", 22600.0);

    @Test
    void allFiveLoad() {
        for (String f : new String[] {"opening-reclaim", "break-retest", "failed-breakout", "range-fade", "auction-pressure"}) {
            StrategyFactory factory = Strategies.load(Path.of("..", "config", "strategy", f + ".v1.yaml"));
            assertThat(factory.id()).isEqualTo(f);
            assertThat(factory.premiumBudget()).isEqualTo(250_000);
            assertThat(factory.premiumStopPct()).isEqualTo(25);
        }
    }

    @Test
    void openingLowReclaimBuysTheCallThenTakesTheNextLevel() {
        Day d = new Day("opening-reclaim.v1.yaml", PRIOR);
        d.at("09:16", 22590);
        d.at("09:18", 22575);
        d.at("09:20", 22569);                                       // the low, 2 points from prior ORH 22,571
        LocalTime t = LocalTime.of(9, 21);
        for (int i = 0; i < 10; i++, t = t.plusMinutes(1)) {
            assertThat(d.at(t.toString(), 22575 + i * 2.0).orders()).isEmpty();
        }
        Decision entry = d.at("09:31", 22605);                      // first close back above the day open 22,600
        assertThat(entry.orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("OPENING_LOW_RECLAIM");
        });
        PositionView holding = new PositionView(true, OptionSide.CE, 1, 50, null, 52);
        assertThat(d.at("09:35", 22615, holding).orders()).isEmpty();
        assertThat(d.at("09:36", 22622, holding).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("TARGET");   // PDH 22,621
    }

    @Test
    void neverTheIndexOnItsExpiryDay() {
        Day d = new Day("opening-reclaim.v1.yaml", PRIOR);
        d.dte = 0;
        d.at("09:16", 22590);
        d.at("09:20", 22569);
        LocalTime t = LocalTime.of(9, 21);
        for (int i = 0; i < 10; i++, t = t.plusMinutes(1)) {
            d.at(t.toString(), 22575 + i * 2.0);
        }
        assertThat(d.at("09:31", 22605).orders()).isEmpty();
    }

    @Test
    void breakAndRetestEntersOnTheTurnAfterAHeldRetest() {
        Map<String, Object> or = Map.of("structure.orComplete", true, "structure.orHigh", 22627.0, "structure.orLow", 22560.0,
                "structure.pdh", 22700.0, "structure.pdl", 22397.0);
        Day d = new Day("break-retest.v1.yaml", or);
        d.at("09:37", 22610);
        d.at("09:38", 22620);
        d.at("09:39", 22625);
        d.at("09:40", 22630);                                       // the break of ORH 22,627
        d.at("09:41", 22640);
        d.at("09:42", 22632);                                       // back within 0.3 ATR: the retest
        assertThat(d.at("09:43", 22635).orders()).as("not above both previous closes").isEmpty();
        assertThat(d.at("09:44", 22642).orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("BREAK_RETEST_UP");
        });

        Day failed = new Day("break-retest.v1.yaml", or);
        failed.at("09:38", 22620);
        failed.at("09:39", 22625);
        failed.at("09:40", 22630);
        failed.at("09:41", 22615);                                  // 12 below the level: the break failed
        failed.at("09:42", 22625);
        assertThat(failed.at("09:43", 22632).orders()).isEmpty();
    }

    @Test
    void failedBreakoutBuysThePutOnTheLowerHighTurn() {
        Day d = new Day("failed-breakout.v1.yaml", Map.of("structure.pdh", 22621.0, "structure.pdl", 22397.0));
        d.at("09:48", 22610);
        d.at("09:50", 22615);
        d.at("09:51", 22625);                                       // through PDH, a new day high
        d.at("09:52", 22628);
        d.at("09:53", 22618);                                       // back below within 10 minutes: failed
        d.at("09:54", 22620);                                       // a lower high
        Decision entry = d.at("09:55", 22612);
        assertThat(entry.orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("FAILED_BREAKOUT_UP");
        });
        PositionView holding = new PositionView(true, OptionSide.PE, 1, 50, null, 52);
        assertThat(d.at("09:56", 22629, holding).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("STOP");    // above the failed high 22,628
    }

    @Test
    void rangeFadeBuysTheReclaimedSweepOfTheRangeLow() {
        Day d = new Day("range-fade.v1.yaml", Map.of());
        d.at("09:30", 22750);                                       // the day's extremes, long before the range
        d.at("09:31", 22650);
        LocalTime t = LocalTime.of(10, 0);
        for (int i = 0; i < 60; i++, t = t.plusMinutes(1)) {
            d.at(t.toString(), i % 4 < 2 ? 22590.0 + (i % 2) * 2 : 22608.0 - (i % 2) * 2);   // around VWAP 22,600
        }
        assertThat(d.at("11:00", 22585).orders()).as("the sweep: below the range low 22,590").isEmpty();
        assertThat(d.at("11:01", 22594).orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("RANGE_LOW_RECLAIM");
        });
    }

    @Test
    void auctionPressureDecidesAtTheFirstAuctionMinuteAndExitsAt1529() {
        Day d = new Day("auction-pressure.v1.yaml", Map.of());
        assertThat(d.at("15:20", 22718, PositionView.FLAT).orders()).isEmpty();
        Decision entry = d.at("15:21", 22718, PositionView.FLAT, "cas.constituentCoveragePct", 100.0,
                "cas.weightedIepReturnPct", 1.287, "phase", "CAS_MARKET_AND_LIMIT");
        assertThat(entry.orders()).singleElement().extracting(OrderIntent::side).isEqualTo(OptionSide.CE);
        PositionView holding = new PositionView(true, OptionSide.CE, 1, 50, null, 52);
        assertThat(d.at("15:28", 22718, holding, "phase", "CAS_LIMIT_ONLY").orders()).isEmpty();
        assertThat(d.at("15:29", 22718, holding, "phase", "CAS_LIMIT_ONLY").orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("FLAT_BY");

        Day weak = new Day("auction-pressure.v1.yaml", Map.of());
        assertThat(weak.at("15:21", 22718, PositionView.FLAT, "cas.constituentCoveragePct", 100.0,
                "cas.weightedIepReturnPct", 0.10).orders()).as("below 0.15 %").isEmpty();
        assertThat(weak.at("15:22", 22718, PositionView.FLAT, "cas.constituentCoveragePct", 100.0,
                "cas.weightedIepReturnPct", 0.40).orders()).as("decided at the first minute").isEmpty();
    }
}
