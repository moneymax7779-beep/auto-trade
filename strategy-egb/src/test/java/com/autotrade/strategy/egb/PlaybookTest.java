package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/** A-244: the playbook's squeeze entry, its T1 / RUNNER split and the golden-window gap. */
class PlaybookTest {

    private static Strategy load(String file) {
        return Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
    }

    private static Snapshots snap(double spot, Map<String, Object> extra) {
        Snapshots s = new Snapshots().set("spot", spot).set("structure.orComplete", true).set("structure.orHigh", 22700.0)
                .set("structure.orLow", 22550.0).set("structure.pdh", 22800.0).set("structure.pdl", 22450.0)
                .set("structure.atr3m", 10.0).set("regime.dteTradingDays", 2).set("options.callBarrierStrike", 22900.0);
        extra.forEach(s::set);
        return s;
    }

    private static final Map<String, Object> SQUEEZE = Map.of("futures.rvolTod", 2.0, "options.ceOiChange5m", -50_000.0,
            "options.peOiChange5m", 10_000.0);

    /** 09:42-09:59 at 22,690 (the 09:57 candle closes below ORH), then 10:00 closes at 22,712 = the candle high. */
    private static Decision breakout(Strategy s, String at, Map<String, Object> columns) {
        int start = Integer.parseInt(at.substring(0, 2)) * 60 + Integer.parseInt(at.substring(3)) - 18;
        for (int m = start; m < start + 17; m++) {
            s.decide(snap(22690, Map.of()).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        s.decide(snap(22698, Map.of()).at(String.format("%02d:%02d", (start + 17) / 60, (start + 17) % 60)), PositionView.FLAT);
        return s.decide(snap(22712, columns).at(at), PositionView.FLAT);
    }

    @Test
    void squeezeEntersOnACleanTopQuarterBreakWithCoveringCalls() {
        Decision d = breakout(load("playbook-squeeze.v1.yaml"), "10:00", SQUEEZE);
        assertThat(d.orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("SQUEEZE");
        });
        Map<String, Object> callsAdded = new HashMap<>(SQUEEZE);
        callsAdded.put("options.ceOiChange5m", 40_000.0);
        assertThat(breakout(load("playbook-squeeze.v1.yaml"), "10:00", callsAdded).orders()).as("call writers adding").isEmpty();
        assertThat(breakout(load("playbook-squeeze.v1.yaml"), "11:30", SQUEEZE).orders()).as("mid-day gap").isEmpty();
    }

    @Test
    void t1ExitsAtOneRWhileTheRunnerMovesItsStopToTheEntry() {
        // entry 22,712; the candle's minute closes 22,690 / 22,698 / 22,712: stop = max(ORH 22,700 - 5, 22,690) = 22,695,
        // so 1R = 22,729
        Strategy t1 = load("playbook-squeeze.v1.yaml"), runner = load("playbook-squeeze-runner.v1.yaml");
        breakout(t1, "10:00", SQUEEZE);
        breakout(runner, "10:00", SQUEEZE);
        PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 101);
        assertThat(t1.decide(snap(22728, Map.of()).at("10:01"), open).orders()).as("short of 1R").isEmpty();
        assertThat(t1.decide(snap(22730, Map.of()).at("10:02"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TARGET"));
        assertThat(runner.decide(snap(22730, Map.of()).at("10:01"), open).orders()).as("runner rides past 1R").isEmpty();
        assertThat(runner.decide(snap(22715, Map.of()).at("10:02"), open).orders()).isEmpty();
        assertThat(runner.decide(snap(22711, Map.of()).at("10:03"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("BE_STOP"));
    }

    /** Review 27 Oct: at 09:21 a 30-minute box has 3 minutes of data, which is always "tight"; no box before the lookback. */
    @Test
    void boxNeedsAWholeLookbackOfTodaysMinutes() {
        Map<String, Object> cols = Map.of("futures.rvolTod", 2.0, "options.atmBandNet3m", -2_000_000.0,
                "structure.prevClose", 22700.0);
        Strategy early = load("playbook-box.v1.yaml");
        for (int m = 556; m < 561; m++) {                                    // 09:16-09:20 flat at 22,690
            early.decide(snap(22690, Map.of()).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(early.decide(snap(22680, cols).at("09:21"), PositionView.FLAT).orders()).isEmpty();

        Strategy late = load("playbook-box.v1.yaml");
        for (int m = 556; m < 600; m++) {                                    // 09:16-09:59 flat: a real 30-minute box
            late.decide(snap(22690, Map.of()).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(late.decide(snap(22680, cols).at("10:00"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("BOX_DOWN"));
    }

    private static String hm(int m) {
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /** A-246: a 45-minute flat box at 22,690 from 10:30, broken by the 11:33 candle close (11:30-13:40 window). */
    @Test
    void macSqueezeBuysTheCallOnABoxBreakWithCallOiFalling() {
        Map<String, Object> breakout = Map.of("futures.rvolTod", 2.0, "options.ceOiChange5m", -50_000.0);
        for (boolean callsFalling : new boolean[] {true, false}) {
            Strategy s = load("playbook-mac-squeeze.v1.yaml");
            for (int m = 630; m < 693; m++) {                                // 10:30-11:32 flat
                assertThat(s.decide(snap(22690, Map.of()).at(hm(m)), PositionView.FLAT).orders()).isEmpty();
            }
            Map<String, Object> cols = callsFalling ? breakout : Map.of("futures.rvolTod", 2.0, "options.ceOiChange5m", 10_000.0);
            var orders = s.decide(snap(22700, cols).at("11:33"), PositionView.FLAT).orders();
            if (callsFalling) {
                assertThat(orders).singleElement().satisfies(o -> {
                    assertThat(o.side()).isEqualTo(OptionSide.CE);
                    assertThat(o.reason()).isEqualTo("MAC_SQUEEZE");
                });
            } else {
                assertThat(orders).as("call writers adding").isEmpty();
            }
        }
    }

    /** A-246: ORL 22,550 broken by the 09:45 candle, retested by 09:47's 22,551 and the 09:48 candle closes below it. */
    @Test
    void orlRetestBuysThePutWhenTheRetestFailsAndMovesItsStopToCostAfterTwentyPercent() {
        Map<String, Object> quietVixUp = Map.of("futures.rvolTod", 0.8, "volatility.vixChange15m", 0.1);
        Strategy s = load("playbook-orl-retest.v1.yaml");
        for (int m = 571; m < 585; m++) {                                    // 09:31-09:44 above ORL
            s.decide(snap(22560, Map.of()).at(hm(m)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22540, quietVixUp).at("09:45"), PositionView.FLAT).orders()).as("the break itself").isEmpty();
        s.decide(snap(22548, Map.of()).at("09:46"), PositionView.FLAT);
        s.decide(snap(22551, Map.of()).at("09:47"), PositionView.FLAT);
        assertThat(s.decide(snap(22545, quietVixUp).at("09:48"), PositionView.FLAT).orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("ORL_RETEST");
        });
        assertThat(s.decide(snap(22540, Map.of()).at("09:49"), new PositionView(true, OptionSide.PE, 1, 100, null, 121))
                .orders()).as("+21 %: armed").isEmpty();
        assertThat(s.decide(snap(22543, Map.of()).at("09:50"), new PositionView(true, OptionSide.PE, 1, 100, null, 100))
                .orders()).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("BE_STOP"));
    }

    @Test
    void orlRetestNeedsVixRising() {
        Strategy s = load("playbook-orl-retest.v1.yaml");
        for (int m = 571; m < 585; m++) {
            s.decide(snap(22560, Map.of()).at(hm(m)), PositionView.FLAT);
        }
        s.decide(snap(22540, Map.of()).at("09:45"), PositionView.FLAT);
        s.decide(snap(22551, Map.of()).at("09:47"), PositionView.FLAT);
        assertThat(s.decide(snap(22545, Map.of("futures.rvolTod", 0.8, "volatility.vixChange15m", -0.1)).at("09:48"),
                PositionView.FLAT).orders()).isEmpty();
    }

    /** A-246 S: at 10:15 the probe flags flat futures OI (0.5 % of OI) and volume below normal (mean RVOL 0.8). */
    @Test
    void standAsideProbeRecordsItsFlagsAtTenFifteenAndNeverTrades() {
        Strategy s = load("playbook-stand-aside-probe.v1.yaml");
        Map<String, Object> fut = Map.of("futures.rvolTod", 0.8, "futures.oi", 10_000_000.0, "futures.oiChangeDay", 50_000.0);
        Decision last = null;
        for (int m = 556; m <= 615; m++) {                                    // 09:16-10:15
            last = s.decide(snap(22690, fut).at(hm(m)), PositionView.FLAT);
            assertThat(last.orders()).isEmpty();
        }
        assertThat(last.ce().conditions()).containsEntry("sa_flat_oi", true).containsEntry("sa_low_volume", true)
                .containsEntry("sa_stand_aside", true);
    }

    @Test
    void everyPlaybookFileLoadsForBothIndices() {
        for (String f : new String[] {"playbook-squeeze.v1.yaml", "playbook-squeeze-runner.v1.yaml", "playbook-avalanche.v1.yaml",
                "playbook-avalanche-runner.v1.yaml", "playbook-vwap.v1.yaml", "playbook-vwap-runner.v1.yaml",
                "playbook-box.v1.yaml", "playbook-box.v2.yaml", "playbook-mac-squeeze.v1.yaml",
                "playbook-mac-squeeze-runner.v1.yaml", "playbook-orl-retest.v1.yaml", "playbook-stand-aside-probe.v1.yaml"}) {
            assertThat(Strategies.load(Path.of("..", "config", "strategy", f)).create("SENSEX", Snapshots.SESSION)).isNotNull();
        }
    }
}
