package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autotrade.core.time.MarketTime;
import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.OrderIntent;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/**
 * A-431: sbr-v3's exit when a level the entry already crossed is reclaimed (X4a), on sbr-v2's synthetic day with a previous
 * close of 25,070 between the traded line (first-hour high 25,060) and the CE entry close 25,085 (buffer 7.56).
 * A: the 10:35 close 25,062 is 8 below 25,070 (> 7.56) but above the line stop (25,052.44): LEVEL_RECLAIM.
 * B: the 10:35 close 25,064 is only 6 below 25,070: held. C: sbr-v2 (no reclaim) holds the A path.
 */
class SessionBreakRetestV3Test {

    private static final String[][] BARS = {{"09:20", "25020", "24990", "25000"}, {"09:25", "25030", "25000", "25020"},
            {"09:30", "25060", "25020", "25040"}, {"09:35", "25040", "25010", "25020"}, {"09:40", "25030", "25000", "25010"},
            {"09:45", "25020", "24995", "25005"}, {"09:50", "25030", "25005", "25025"}, {"09:55", "25058", "25030", "25050"},
            {"10:00", "25040", "25020", "25030"}, {"10:05", "25035", "25015", "25025"}, {"10:10", "25040", "25020", "25030"},
            {"10:15", "25040", "25020", "25030"}, {"10:20", "25080", "25062", "25075"}};

    private static Strategy load(String v) {
        return Strategies.load(Path.of("..", "config", "strategy", "session-break-retest." + v + ".yaml")).create("NIFTY", Snapshots.SESSION);
    }

    private double pdh = 25200.0;
    private int dte = 0;
    private double flow15 = Double.NaN;
    private double flow30 = Double.NaN;
    private double ivChange = Double.NaN;
    private double prevOrHigh = Double.NaN;
    private double orH = Double.NaN, orL = Double.NaN;
    private double pwh = Double.NaN;

    private List<OrderIntent> minute(Strategy strategy, LocalTime t, double h, double l, double c, PositionView p) {
        Snapshots s = new Snapshots().set("spot", c).set("structure.dayOpen", 25000.0).set("structure.pdh", pdh)
                .set("structure.prevClose", 25070.0)
                .set("structure.lastMinuteHigh", h).set("structure.lastMinuteLow", l).set("structure.lastMinuteClose", c)
                .set("volatility.vix", 16.0).set("options.expiry", "2026-09-29")
                .set("regime.dteTradingDays", dte).set("options.atmBand3Share15m", flow15).set("options.atmBand3Share30m", flow30).set("options.atmIvChange3m", ivChange)
                .set("structure.prevOrHigh", prevOrHigh)
                .set("structure.orHigh", t.isBefore(LocalTime.of(9, 30)) ? Double.NaN : orH)
                .set("structure.orLow", t.isBefore(LocalTime.of(9, 30)) ? Double.NaN : orL)
                .set("structure.pwh", pwh);
        return strategy.decide(s.at(t.toString()), p).orders();
    }

    private List<OrderIntent> bar(Strategy strategy, String close, double h, double l, double c, PositionView p) {
        LocalTime end = LocalTime.parse(close);
        List<OrderIntent> last = List.of();
        for (int k = 4; k >= 0; k--) {
            last = minute(strategy, end.minusMinutes(k), h, l, c, p);
            if (k > 0) {
                assertThat(last).as("%s", end.minusMinutes(k)).isEmpty();
            }
        }
        return last;
    }

    private PositionView enter(Strategy strategy) {
        for (String[] b : BARS) {
            assertThat(bar(strategy, b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]),
                    PositionView.FLAT)).as(b[0]).isEmpty();
        }
        assertThat(bar(strategy, "10:25", 25090, 25065, 25085, PositionView.FLAT)).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("BREAK_RETEST_CE"));
        Instant i = Snapshots.SESSION.atTime(LocalTime.of(10, 25)).atZone(MarketTime.IST).toInstant();
        return new PositionView(true, OptionSide.CE, 1, 100, i, 100);
    }

    /**
     * A-433 sbr-v4: the 10:25 CE entry close 25,085 with the PDH 25,200 ahead (0.46 %) is taken; with the PDH at 25,130
     * (0.18 %, the nearest level ahead) the same retest is skipped; v3 takes both.
     */
    @Test
    void v4SkipsTheEntryWhenTheNextLevelIsTooClose() {
        enter(load("v4"));                                                    // room 0.46 %: entered
        pdh = 25130.0;
        Strategy v4 = load("v4");
        for (String[] b : BARS) {
            bar(v4, b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]), PositionView.FLAT);
        }
        assertThat(bar(v4, "10:25", 25090, 25065, 25085, PositionView.FLAT)).as("room 0.18 %").isEmpty();
        enter(load("v3"));                                                    // v3 ignores the room
    }

    /**
     * A-435 sbr-v5: no new entry from last_new_entry_0dte when the index expires today. v5 itself (14:00) takes the 10:25
     * entry on an expiry day; the same file with the cutoff at 10:25 (exclusive) skips it at 0 DTE and takes it at 1 DTE.
     */
    @Test
    void v5SkipsLateEntriesOnlyOnTheExpiryDay(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        enter(load("v5"));                                                    // 0 DTE, 10:25 < 14:00: entered
        Path early = dir.resolve("session-break-retest.v5.yaml");
        String v5 = java.nio.file.Files.readString(Path.of("..", "config", "strategy", "session-break-retest.v5.yaml"));
        assertThat(v5).contains("last_new_entry_0dte: \"14:00\"");
        java.nio.file.Files.writeString(early, v5.replace("last_new_entry_0dte: \"14:00\"", "last_new_entry_0dte: \"10:25\""));
        Strategy cut = Strategies.load(early).create("NIFTY", Snapshots.SESSION);
        for (String[] b : BARS) {
            bar(cut, b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]), PositionView.FLAT);
        }
        assertThat(bar(cut, "10:25", 25090, 25065, 25085, PositionView.FLAT)).as("0 DTE at the cutoff").isEmpty();
        dte = 1;
        enter(Strategies.load(early).create("NIFTY", Snapshots.SESSION));     // 1 DTE: the cutoff does not apply
    }

    /** Feeds the synthetic day minute by minute (each bar's values for its five minutes); the first entry's time, or null. */
    private LocalTime firstEntry(Strategy strategy) {
        String[][] all = java.util.Arrays.copyOf(BARS, BARS.length + 1);
        all[BARS.length] = new String[] {"10:25", "25090", "25065", "25085"};
        for (String[] b : all) {
            LocalTime end = LocalTime.parse(b[0]);
            for (int k = 4; k >= 0; k--) {
                List<OrderIntent> o = minute(strategy, end.minusMinutes(k), Double.parseDouble(b[1]), Double.parseDouble(b[2]),
                        Double.parseDouble(b[3]), PositionView.FLAT);
                if (!o.isEmpty()) {
                    return end.minusMinutes(k);
                }
            }
        }
        return null;
    }

    /**
     * A-437 sbr-v7 / v8: the same break-retest confirmed on shorter closes. v5 enters on the 10:25 five-minute close; v8
     * breaks on the 10:16 one-minute close (25,075) and enters at 10:21 (low 25,065 within the band, close 25,085 above the
     * previous 25,075); v7 breaks on the 10:18 three-minute close and enters on the 10:21 one (low 25,062).
     */
    @Test
    void v7AndV8ConfirmTheBreakOnShorterCloses() {
        assertThat(firstEntry(load("v5"))).isEqualTo(LocalTime.of(10, 25));
        assertThat(firstEntry(load("v7"))).isEqualTo(LocalTime.of(10, 21));
        assertThat(firstEntry(load("v8"))).isEqualTo(LocalTime.of(10, 21));
    }

    /**
     * A-437 sbr-v6: the 10:25 CE with the index up 55 points in 15 minutes is skipped when the 15-minute ATM +- 3 flow is
     * -2 % (call writing into the rise), taken at -1 % and at +2 %, and when the flow is unknown.
     */
    @Test
    void v6OiGateSkipsACallAgainstCallWriting() {
        flow15 = -0.02;
        assertThat(firstEntry(load("v6"))).isNull();
        flow15 = -0.01;
        assertThat(firstEntry(load("v6"))).isEqualTo(LocalTime.of(10, 25));
        flow15 = 0.02;
        assertThat(firstEntry(load("v6"))).isEqualTo(LocalTime.of(10, 25));
        flow15 = Double.NaN;
        assertThat(firstEntry(load("v6"))).isEqualTo(LocalTime.of(10, 25));
        flow15 = -0.02;
        assertThat(firstEntry(load("v5"))).as("v5 has no gate").isEqualTo(LocalTime.of(10, 25));
    }

    /**
     * A-438 sbr-v9: after the 10:25 CE (entry close 25,085, EM 252: targets >= 25,122.8; the static target is the PDH 25,200)
     * the 10:35 bar makes a top 25,160, confirmed as a swing (still the session high) at 10:45: the new target. 10:50 comes
     * back within the band (25,155 >= 25,147.4) and the 10:55 close 25,125 is 30 >= 25.2 back from the best: LEVEL_REJECT.
     * v5 holds the same path.
     */
    @Test
    void v9TakesProfitAtAFreshTop() {
        for (String v : new String[] {"v9", "v5"}) {
            Strategy s = load(v);
            PositionView p = enter(s);
            assertThat(bar(s, "10:30", 25150, 25085, 25140, p)).as(v).isEmpty();
            assertThat(bar(s, "10:35", 25160, 25135, 25150, p)).as(v).isEmpty();
            assertThat(bar(s, "10:40", 25150, 25135, 25140, p)).as(v).isEmpty();
            assertThat(bar(s, "10:45", 25145, 25130, 25135, p)).as(v).isEmpty();
            assertThat(bar(s, "10:50", 25155, 25135, 25150, p)).as(v).isEmpty();
            List<OrderIntent> last = bar(s, "10:55", 25150, 25120, 25125, p);
            if (v.equals("v9")) {
                assertThat(last).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("LEVEL_REJECT"));
            } else {
                assertThat(last).as("v5 has no fresh target").isEmpty();
            }
        }
    }

    /**
     * A-439 sbr-v10: the static target is the PDH 25,200 (band 12.6, so armed by the 10:30 high 25,195); the 10:35 close
     * 25,180 is 15 back from the best: v10 (0.05 x EM = 12.6) exits, v5 (0.10 x EM = 25.2) holds.
     */
    @Test
    void v10RejectsOnTheShorterPullback() {
        for (String v : new String[] {"v10", "v5"}) {
            Strategy s = load(v);
            PositionView p = enter(s);
            assertThat(bar(s, "10:30", 25195, 25085, 25190, p)).as(v).isEmpty();
            List<OrderIntent> last = bar(s, "10:35", 25192, 25175, 25180, p);
            if (v.equals("v10")) {
                assertThat(last).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("LEVEL_REJECT"));
            } else {
                assertThat(last).as("v5 holds").isEmpty();
            }
        }
    }

    /**
     * A-439 sbr-v11: with the prior day's opening-range high at 25,140 (>= 25,122.8 beyond the entry close) it is v11's target
     * (v5's stays the PDH 25,200): the 10:30 high 25,138 arms it and the 10:35 close 25,104 (34 back) exits v11; v5 holds.
     */
    @Test
    void v11TargetsThePriorOpeningRangeHigh() {
        prevOrHigh = 25140.0;
        for (String v : new String[] {"v11", "v5"}) {
            Strategy s = load(v);
            PositionView p = enter(s);
            assertThat(bar(s, "10:30", 25138, 25085, 25130, p)).as(v).isEmpty();
            List<OrderIntent> last = bar(s, "10:35", 25135, 25100, 25104, p);
            if (v.equals("v11")) {
                assertThat(last).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("LEVEL_REJECT"));
            } else {
                assertThat(last).as("v5 holds").isEmpty();
            }
        }
    }

    /** A-440: an opening path: the range 25,030 / 24,990, a break at 09:35 (25,045), a retest at 09:45 (low 25,038, close 25,055). */
    private static final String[][] OPEN = {{"09:20", "25020", "24990", "25000"}, {"09:25", "25030", "25000", "25020"},
            {"09:30", "25030", "25010", "25020"}, {"09:35", "25050", "25025", "25045"}, {"09:40", "25048", "25035", "25042"},
            {"09:45", "25060", "25038", "25055"}};

    private LocalTime openEntry(Strategy strategy) {
        for (String[] b : OPEN) {
            LocalTime end = LocalTime.parse(b[0]);
            for (int k = 4; k >= 0; k--) {
                if (!minute(strategy, end.minusMinutes(k), Double.parseDouble(b[1]), Double.parseDouble(b[2]),
                        Double.parseDouble(b[3]), PositionView.FLAT).isEmpty()) {
                    return end.minusMinutes(k);
                }
            }
        }
        return null;
    }

    /** A-440 sbr-v13: the ORH 25,030 is a line from 09:30, so the 09:35 break and 09:45 retest enter; v11 waits for 10:00. */
    @Test
    void v13TradesTheOpeningRangeFrom0930() {
        orH = 25030.0;
        orL = 24990.0;
        assertThat(openEntry(load("v13"))).isEqualTo(LocalTime.of(9, 45));
        assertThat(openEntry(load("v11"))).isNull();
    }

    /** A-440 sbr-v14: with the opening range wide (25,100 / 24,900) the PDH 25,030 is v14's line; v13 has no line there. */
    @Test
    void v14TradesAMajorLevelBreak() {
        orH = 25100.0;
        orL = 24900.0;
        pdh = 25030.0;
        assertThat(openEntry(load("v14"))).isEqualTo(LocalTime.of(9, 45));
        assertThat(openEntry(load("v13"))).isNull();
    }

    /**
     * A-441 sbr-v15: as 15 Oct: the ORH 25,030 (resistance) and a prior-week high 25,018 just below the 09:30 close 25,020
     * (support), 12 apart (merge distance 25.2) and nearer the ORH than the day open 25,000. v14 merges it into the ORH line,
     * which becomes a support at 25,018, and misses the up-break; v15 keeps the ORH as resistance and enters at 09:45.
     */
    @Test
    void v15MergesLevelsOnlyOnTheSameSide() {
        orH = 25030.0;
        orL = 24990.0;
        pwh = 25018.0;
        assertThat(openEntry(load("v14"))).isNull();
        assertThat(openEntry(load("v15"))).isEqualTo(LocalTime.of(9, 45));
    }

    /**
     * A-439 zones (LevelsService.zones ported): minute highs pivot at 25,050 (09:19), 25,049 (09:26) and 25,051 (09:33), each
     * strictly above the 3 minutes before and not below the 3 after, 7 minutes apart and within 0.04 % of the zone mean: one
     * resistance zone of 3 touches at 25,050, confirmed at 09:36. The two pivot lows (25,005 at 09:22 and 09:29) make a support
     * zone of only 2 touches, left out at 3.
     */
    @Test
    void v11ZonesFollowTheChartRule() {
        double[] highs = {25000, 25010, 25020, 25050, 25030, 25020, 25010, 25015, 25020, 25030, 25049, 25030, 25020, 25010,
                25020, 25030, 25040, 25051, 25030, 25020, 25010};
        Strategy s = load("v11");
        LocalTime t = LocalTime.of(9, 16);
        for (double h : highs) {
            minute(s, t, h, h - 5, h - 2, PositionView.FLAT);
            t = t.plusMinutes(1);
        }
        assertThat(s).isInstanceOf(SessionBreakRetest.class);
        assertThat(((SessionBreakRetest) s).zoneLevels(3)).containsExactly(25050.0);
        assertThat(((SessionBreakRetest) s).zoneLevels(2)).containsExactlyInAnyOrder(25050.0, 25005.0);
    }

    /**
     * A-437 sbr-v8: after the 10:21 CE (line 25,060, buffer 7.56), the 10:22 one-minute close 25,050 is a LINE_STOP on v8; v5,
     * holding the 10:25 entry, needs a five-minute close.
     */
    @Test
    void v8StopsOnAOneMinuteClose() {
        Strategy v8 = load("v8");
        assertThat(firstEntry(v8)).isEqualTo(LocalTime.of(10, 21));
        Instant at = Snapshots.SESSION.atTime(LocalTime.of(10, 21)).atZone(MarketTime.IST).toInstant();
        PositionView p = new PositionView(true, OptionSide.CE, 1, 100, at, 100);
        assertThat(minute(v8, LocalTime.of(10, 22), 25080, 25045, 25050, p)).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("LINE_STOP"));
    }

    /**
     * A-447 sbr-v17: after the 10:25 CE (line 25,060, EM 252.0: 0.15 x EM = 37.8), a one-minute close at 25,040 (20 below the
     * line) is held, one at 25,020 (40 below) exits at once (FAST_STOP); v13 waits for the five-minute close.
     */
    @Test
    void v17StopsOnALargeOneMinuteBreak() {
        for (String v : new String[] {"v17", "v13"}) {
            Strategy s = load(v);
            PositionView p = enter(s);
            assertThat(minute(s, LocalTime.of(10, 26), 25085, 25035, 25040, p)).as(v + " 20 below").isEmpty();
            List<OrderIntent> o = minute(s, LocalTime.of(10, 27), 25045, 25015, 25020, p);
            if (v.equals("v17")) {
                assertThat(o).singleElement().satisfies(x -> assertThat(x.reason()).isEqualTo("FAST_STOP"));
            } else {
                assertThat(o).as("v13 waits for the 10:30 close").isEmpty();
            }
        }
    }

    /**
     * A-452 sbr-v18: the 10:25 CE is bought at 2 lots (of intended 2, the full budget) when the 30-minute flow backs it by
     * 5 %, at 1 lot (half) when by 15 % (crowded); v13 always 1 lot of intended 1.
     */
    @Test
    void v18HalvesACrowdedEntry() {
        for (double f : new double[] {0.05, 0.15}) {
            flow30 = f;
            Strategy s = load("v18");
            for (String[] b : BARS) {
                bar(s, b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]), PositionView.FLAT);
            }
            List<OrderIntent> o = bar(s, "10:25", 25090, 25065, 25085, PositionView.FLAT);
            assertThat(o).singleElement().satisfies(x -> assertThat(x.lots()).as("flow %s", f).isEqualTo(f > 0.10 ? 1 : 2));
        }
    }

    /** A-452 sbr-v19 / v20: falling IV halves the entry (v19); either crowding or falling IV halves it (v20). */
    @Test
    void v19AndV20HalveOnFallingIvOrCrowding() {
        double[][] cases = {{0.05, 0.001, 2, 2}, {0.05, -0.001, 1, 1}, {0.15, 0.001, 2, 1}};   // flow30, ivChange, v19 lots, v20 lots
        for (double[] k : cases) {
            flow30 = k[0];
            ivChange = k[1];
            for (String v : new String[] {"v19", "v20"}) {
                Strategy s = load(v);
                for (String[] b : BARS) {
                    bar(s, b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]), PositionView.FLAT);
                }
                int want = (int) (v.equals("v19") ? k[2] : k[3]);
                assertThat(bar(s, "10:25", 25090, 25065, 25085, PositionView.FLAT)).singleElement()
                        .satisfies(x -> assertThat(x.lots()).as("%s flow %s iv %s", v, k[0], k[1]).isEqualTo(want));
            }
        }
    }

    /** A-453 sbr-v21: crowded and falling IV = no entry; one weak = 1 lot; none = 2 lots. */
    @Test
    void v21SkipsWhenBothAreWeak() {
        double[][] cases = {{0.15, -0.001, 0}, {0.15, 0.001, 1}, {0.05, -0.001, 1}, {0.05, 0.001, 2}};
        for (double[] k : cases) {
            flow30 = k[0];
            ivChange = k[1];
            Strategy s = load("v21");
            for (String[] b : BARS) {
                bar(s, b[0], Double.parseDouble(b[1]), Double.parseDouble(b[2]), Double.parseDouble(b[3]), PositionView.FLAT);
            }
            List<OrderIntent> o = bar(s, "10:25", 25090, 25065, 25085, PositionView.FLAT);
            if (k[2] == 0) {
                assertThat(o).as("both weak").isEmpty();
            } else {
                assertThat(o).singleElement().satisfies(x -> assertThat(x.lots()).as("flow %s iv %s", k[0], k[1]).isEqualTo((int) k[2]));
            }
        }
    }

    @Test
    void exitsWhenACrossedLevelIsReclaimed() {
        Strategy s = load("v3");
        PositionView p = enter(s);
        assertThat(bar(s, "10:30", 25090, 25075, 25080, p)).isEmpty();
        assertThat(bar(s, "10:35", 25080, 25060, 25062, p)).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("LEVEL_RECLAIM"));
    }

    @Test
    void lessThanTheBufferBackIsHeld() {
        Strategy s = load("v3");
        PositionView p = enter(s);
        assertThat(bar(s, "10:30", 25090, 25075, 25080, p)).isEmpty();
        assertThat(bar(s, "10:35", 25080, 25062, 25064, p)).isEmpty();
    }

    @Test
    void sbrV2HasNoReclaimExit() {
        Strategy s = load("v2");
        PositionView p = enter(s);
        assertThat(bar(s, "10:30", 25090, 25075, 25080, p)).isEmpty();
        assertThat(bar(s, "10:35", 25080, 25060, 25062, p)).isEmpty();
    }
}
