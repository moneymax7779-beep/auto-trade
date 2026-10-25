package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.strategy.Decision;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/**
 * Code review 25 Oct 2026: the research keys added on 24-25 Oct (mac-v7 conditional time stop, mac-v8 entry filters,
 * odm-v16 retry after a veto) do what their ledger entries say, and the live versions (mac-v1, odm-v4) are unchanged.
 */
class ReviewedKeysTest {

    private static Strategy strategy(String file) {
        return Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
    }

    private static Decision at(Strategy s, String t, double spot, PositionView p, Map<String, Object> values) {
        Snapshots snap = new Snapshots().set("spot", spot).set("structure.atr3m", 20.0).set("structure.vwapSpotProxy", 22600.0)
                .set("regime.dteTradingDays", 1).set("structure.dayHigh", Math.max(spot, 22650.0))
                .set("structure.dayLow", Math.min(spot, 22550.0));
        values.forEach(snap::set);
        return s.decide(snap.at(t), p);
    }

    private static final PositionView CALL = new PositionView(true, OptionSide.CE, 1, 50, null, 52);

    /** A cross up at 10:01 at 22,610 with the swing low at 22,580 (risk 30, target 22,670). */
    private static Strategy crossedUp(String file, Map<String, Object> extra) {
        Strategy s = strategy(file);
        Map<String, Object> base = new java.util.HashMap<>(Map.of("structure.lastSwingLow", 22580.0,
                "structure.lastSwingHigh", 22700.0, "structure.dayOpen", 22600.0, "structure.prevClose", 22590.0));
        base.putAll(extra);
        Map<String, Object> before = new java.util.HashMap<>(base);
        before.putAll(Map.of("structure.ema9", 22598.0, "structure.ema20", 22602.0));
        at(s, "10:00", 22600, PositionView.FLAT, before);
        Map<String, Object> cross = new java.util.HashMap<>(base);
        cross.putAll(Map.of("structure.ema9", 22605.0, "structure.ema20", 22601.0));
        assertThat(at(s, "10:01", 22610, PositionView.FLAT, cross).orders()).as(file + " enters on the cross").hasSize(1);
        return s;
    }

    private static String exit(Decision d) {
        return d.orders().isEmpty() ? null : d.orders().getFirst().reason();
    }

    @Test
    void macV1StillExitsAtSixtyMinutes() {
        Strategy s = crossedUp("ma-cross.v1.yaml", Map.of());
        Map<String, Object> working = Map.of("structure.ema9", 22625.0, "structure.ema20", 22612.0, "structure.lastSwingLow", 22580.0);
        assertThat(exit(at(s, "11:00", 22630, CALL, working))).isNull();
        assertThat(exit(at(s, "11:01", 22630, CALL, working))).isEqualTo("TIME_60");
    }

    @Test
    void macV7KeepsAWorkingTradeAndTrailsTheNextSwing() {
        Strategy s = crossedUp("ma-cross.v7.yaml", Map.of());
        Map<String, Object> working = Map.of("structure.ema9", 22625.0, "structure.ema20", 22612.0, "structure.lastSwingLow", 22580.0);
        assertThat(exit(at(s, "11:01", 22630, CALL, working))).as("60 minutes, but not a 3-minute close yet").isNull();
        assertThat(exit(at(s, "11:03", 22630, CALL, working))).as("+20 >= 0.5R, above EMA20 and VWAP: kept").isNull();
        assertThat(exit(at(s, "11:30", 22640, CALL, Map.of("structure.ema9", 22635.0, "structure.ema20", 22620.0,
                "structure.lastSwingLow", 22625.0)))).as("a new swing low 22,625: the stop moves up").isNull();
        assertThat(exit(at(s, "11:31", 22624, CALL, Map.of("structure.ema9", 22630.0, "structure.ema20", 22620.0,
                "structure.lastSwingLow", 22625.0)))).isEqualTo("TRAIL_STOP");
    }

    @Test
    void macV7CutsATradeThatIsNotWorkingAtSixtyMinutes() {
        Strategy s = crossedUp("ma-cross.v7.yaml", Map.of());
        assertThat(exit(at(s, "11:03", 22615, CALL, Map.of("structure.ema9", 22614.0, "structure.ema20", 22610.0,
                "structure.lastSwingLow", 22580.0)))).as("+5 < 0.5R").isEqualTo("TIME_60");
    }

    @Test
    void macV8FiltersRefuseTheirCases() {
        crossedUp("ma-cross.v8.yaml", Map.of());                       // the plain cross still enters
        assertNoEntry("ma-cross.v8.yaml", "10:01", Map.of("structure.prevClose", 22700.0), "a gap of -0.44 % against the call");
        assertNoEntry("ma-cross.v8.yaml", "12:30", Map.of(), "the 12:00-13:00 pause");
        assertNoEntry("ma-cross.v8.yaml", "10:01", Map.of("structure.dayOpen", 22650.0), "0.18 % against since the open");
        assertNoEntry("ma-cross.v8.yaml", "10:01", Map.of("structure.lastSwingLow", 22590.0), "stop 1.0 ATR < 1.25");
        assertNoEntry("ma-cross.v8.yaml", "10:01", Map.of("structure.vwapSpotProxy", 22640.0), "1.5 ATR below VWAP");
    }

    private static void assertNoEntry(String file, String time, Map<String, Object> extra, String why) {
        Strategy s = strategy(file);
        Map<String, Object> base = new java.util.HashMap<>(Map.of("structure.lastSwingLow", 22580.0,
                "structure.lastSwingHigh", 22700.0, "structure.dayOpen", 22600.0, "structure.prevClose", 22590.0));
        base.putAll(extra);
        String before = time.equals("10:01") ? "10:00" : "12:29";
        Map<String, Object> b = new java.util.HashMap<>(base);
        b.putAll(Map.of("structure.ema9", 22598.0, "structure.ema20", 22602.0));
        at(s, before, 22600, PositionView.FLAT, b);
        Map<String, Object> c = new java.util.HashMap<>(base);
        c.putAll(Map.of("structure.ema9", 22605.0, "structure.ema20", 22601.0));
        assertThat(at(s, time, 22610, PositionView.FLAT, c).orders()).as(why).isEmpty();
    }

    @Test
    void halfAPairIsRefusedAtLoad() throws Exception {
        String yaml = Files.readString(Path.of("..", "config", "strategy", "ma-cross.v8.yaml"))
                .replaceAll("(?m)^\\s*no_entry_until:.*$", "");
        ThresholdConfig c = ThresholdConfig.parse("half.yaml", yaml);
        assertThatThrownBy(() -> Strategies.create(c).create("NIFTY", Snapshots.SESSION))
                .hasStackTraceContaining("must be set together");
    }

    /** A drive up 09:21 with breadth against (vetoed), then 09:24 with breadth agreeing. */
    private static OrderIntent driveAfterVeto(String file) {
        Strategy s = strategy(file);
        Map<String, Object> drive = Map.of("structure.dayOpen", 22600.0, "structure.dayHigh", 22650.0, "structure.dayLow", 22598.0);
        Map<String, Object> vetoed = new java.util.HashMap<>(drive);
        vetoed.put("breadth.moverBreadth", 10.0);
        at(s, "09:20", 22640, PositionView.FLAT, vetoed);
        assertThat(at(s, "09:21", 22650, PositionView.FLAT, vetoed).orders()).as("breadth 10 < 50: vetoed").isEmpty();
        Map<String, Object> agreeing = new java.util.HashMap<>(drive);
        agreeing.putAll(Map.of("breadth.moverBreadth", 70.0, "structure.dayHigh", 22660.0));
        Decision d = at(s, "09:24", 22660, PositionView.FLAT, agreeing);
        return d.orders().isEmpty() ? null : d.orders().getFirst();
    }

    @Test
    void odmV4AVetoedFirstCheckEndsTheDayAsBefore() {
        assertThat(driveAfterVeto("opening-drive-momentum.v4.yaml")).isNull();
    }

    @Test
    void odmV16EntersAtTheNextCheckAfterAVeto() {
        assertThat(driveAfterVeto("opening-drive-momentum.v16.yaml")).isNotNull()
                .extracting(OrderIntent::side).isEqualTo(OptionSide.CE);
    }
}
