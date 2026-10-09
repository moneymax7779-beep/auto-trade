package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;

/** A-064 trend-day rider and A-065 break-retest runner split. */
class TrendDayRiderTest {

    private static final class Day {
        final Strategy strategy;
        final Map<String, Object> fixed = new HashMap<>();
        double high = Double.NaN, low = Double.NaN;

        Day(String file) {
            StrategyFactory f = Strategies.load(Path.of("..", "config", "strategy", file));
            strategy = f.create("NIFTY", Snapshots.SESSION);
        }

        Decision at(String t, double spot, PositionView p, Object... extra) {
            high = Double.isNaN(high) ? spot : Math.max(high, spot);
            low = Double.isNaN(low) ? spot : Math.min(low, spot);
            Snapshots s = new Snapshots().set("spot", spot).set("structure.dayHigh", high).set("structure.dayLow", low)
                    .set("structure.atr3m", 20.0).set("regime.dteTradingDays", 1);
            fixed.forEach(s::set);
            for (int i = 0; i + 1 < extra.length; i += 2) {
                s.set((String) extra[i], extra[i + 1]);
            }
            return strategy.decide(s.at(t), p);
        }
    }

    @Test
    void theSplitFilesLoadWithHalfBudgets() {
        assertThat(Strategies.load(Path.of("..", "config", "strategy", "break-retest.v2.yaml")).premiumBudget()).isEqualTo(125_000);
        StrategyFactory runner = Strategies.load(Path.of("..", "config", "strategy", "break-retest-runner.v1.yaml"));
        assertThat(runner.id()).isEqualTo("break-retest-runner");
        assertThat(runner.premiumBudget()).isEqualTo(125_000);
        StrategyFactory tdr = Strategies.load(Path.of("..", "config", "strategy", "trend-day-rider.v1.yaml"));
        assertThat(tdr.id()).isEqualTo("trend-day-rider");
        assertThat(tdr.premiumBudget()).isEqualTo(250_000);
    }

    /** brt-v1's break and retest of ORH 22,627 (SetupFamiliesTest), fed to brt-v1 and brtr-v1 alike. */
    private static Day breakAndRetest(String file) {
        Day d = new Day(file);
        d.fixed.putAll(Map.of("structure.orComplete", true, "structure.orHigh", 22627.0, "structure.orLow", 22560.0,
                "structure.pdh", 22700.0, "structure.pdl", 22397.0, "structure.vwapSpotProxy", 22600.0));
        d.at("09:37", 22610, PositionView.FLAT);
        d.at("09:38", 22620, PositionView.FLAT);
        d.at("09:39", 22625, PositionView.FLAT);
        d.at("09:40", 22630, PositionView.FLAT);
        d.at("09:41", 22640, PositionView.FLAT);
        d.at("09:42", 22632, PositionView.FLAT);
        d.at("09:43", 22635, PositionView.FLAT);
        assertThat(d.at("09:44", 22642, PositionView.FLAT).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("BREAK_RETEST_UP");
        return d;
    }

    @Test
    void theRunnerTakesTheSameEntryButRidesPastTheTargetAndTheTimeStop() {
        PositionView holding = new PositionView(true, OptionSide.CE, 1, 50, null, 52);
        Day v1 = breakAndRetest("break-retest.v1.yaml");
        Day runner = breakAndRetest("break-retest-runner.v1.yaml");
        assertThat(v1.at("09:50", 22705, holding).orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("TARGET");
        assertThat(runner.at("09:50", 22705, holding).orders()).as("no target").isEmpty();
        assertThat(runner.at("10:20", 22720, holding).orders()).as("no time stop").isEmpty();
        // below VWAP 22,700 at 10:22 (not a 3-minute close: 67 minutes after 09:15), then at 10:24 (69 minutes)
        assertThat(runner.at("10:22", 22690, holding, "structure.vwapSpotProxy", 22700.0).orders()).isEmpty();
        assertThat(runner.at("10:24", 22690, holding, "structure.vwapSpotProxy", 22700.0).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("VWAP_TRAIL");
        Day stopped = breakAndRetest("break-retest-runner.v1.yaml");
        assertThat(stopped.at("09:45", 22629, holding).orders()).as("brt-v1's structure stop: the break close 22,630")
                .singleElement().extracting(OrderIntent::reason).isEqualTo("STOP");
    }

    @Test
    void threeMinuteClosesAreOnThe0915Grid() {
        assertThat(SetupStrategy.threeMinuteClose(LocalTime.of(9, 18))).isTrue();
        assertThat(SetupStrategy.threeMinuteClose(LocalTime.of(10, 9))).isTrue();
        assertThat(SetupStrategy.threeMinuteClose(LocalTime.of(10, 10))).isFalse();
        assertThat(SetupStrategy.threeMinuteClose(LocalTime.of(9, 15))).isFalse();
    }

    /** An hour above VWAP 22,500 from 09:30, rising 2 a minute from 22,520 with EMA20 15 below; breadth as given. */
    private static Day trendDay(double breadth) {
        Day d = new Day("trend-day-rider.v1.yaml");
        d.fixed.putAll(Map.of("structure.dayOpen", 22500.0, "structure.vwapSpotProxy", 22500.0, "breadth.dayBreadth", breadth));
        LocalTime t = LocalTime.of(9, 30);
        for (int i = 0; i <= 64; i++, t = t.plusMinutes(1)) {      // 09:30 .. 10:34
            double close = 22520 + 2.0 * i;
            assertThat(d.at(t.toString(), close, PositionView.FLAT, "structure.ema20", close - 15).orders())
                    .as("no pullback yet at %s", t).isEmpty();
        }
        // 22,648 at 10:34 (the high); pullback toward EMA20 22,640, within 0.5 ATR (10), then the turn
        d.at("10:35", 22642, PositionView.FLAT, "structure.ema20", 22638.0);
        d.at("10:36", 22644, PositionView.FLAT, "structure.ema20", 22639.0);
        return d;
    }

    @Test
    void aProvenTrendDayBuysTheCallOnThePullbackTurnOnce() {
        Day d = trendDay(30);
        Decision entry = d.at("10:37", 22650, PositionView.FLAT, "structure.ema20", 22640.0);
        assertThat(entry.orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("TREND_DAY_PULLBACK_UP");
        });
        PositionView holding = new PositionView(true, OptionSide.CE, 1, 100, null, 101);
        assertThat(d.at("10:38", 22660, holding, "structure.ema20", 22641.0).orders()).isEmpty();
        assertThat(d.at("10:39", 22633, holding, "structure.ema20", 22642.0).orders()).as("below the window's lowest close 22,634")
                .singleElement().extracting(OrderIntent::reason).isEqualTo("STOP");
        // flat again: the same pullback (touches before 10:37) is not traded twice
        assertThat(d.at("10:40", 22655, PositionView.FLAT, "structure.ema20", 22670.0).orders()).as("a fresh touch first")
                .isEmpty();
    }

    @Test
    void weakBreadthOrNoPullbackMeansNoEntry() {
        assertThat(trendDay(10).at("10:37", 22650, PositionView.FLAT, "structure.ema20", 22640.0).orders()).isEmpty();
        Day noPullback = new Day("trend-day-rider.v1.yaml");
        noPullback.fixed.putAll(Map.of("structure.dayOpen", 22500.0, "structure.vwapSpotProxy", 22500.0, "breadth.dayBreadth", 30.0));
        LocalTime t = LocalTime.of(9, 30);
        for (int i = 0; i <= 70; i++, t = t.plusMinutes(1)) {
            double close = 22520 + 2.0 * i;
            assertThat(noPullback.at(t.toString(), close, PositionView.FLAT, "structure.ema20", close - 40).orders()).isEmpty();
        }
    }

    @Test
    void anHourIsNeededAfterAnyCloseBelowVwap() {
        Day d = trendDay(30);
        d.at("10:37", 22490, PositionView.FLAT, "structure.ema20", 22640.0);   // below VWAP: the count restarts
        d.at("10:38", 22600, PositionView.FLAT, "structure.ema20", 22598.0);
        d.at("10:39", 22602, PositionView.FLAT, "structure.ema20", 22598.0);
        assertThat(d.at("10:40", 22660, PositionView.FLAT, "structure.ema20", 22600.0).orders()).isEmpty();
    }
}
