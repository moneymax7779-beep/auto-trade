package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
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

    @Test
    void maCrossBuysOnTheCrossWithASwingStopAndTwiceTheRiskAsTarget() {
        Day d = new Day("ma-cross.v1.yaml", Map.of("structure.lastSwingLow", 22580.0, "structure.lastSwingHigh", 22700.0));
        d.at("10:00", 22600, PositionView.FLAT, "structure.ema9", 22598.0, "structure.ema20", 22602.0);     // below
        assertThat(d.at("10:01", 22610, PositionView.FLAT, "structure.ema9", 22605.0, "structure.ema20", 22601.0).orders())
                .as("cross up: call, stop at the swing low 22,580 (risk 30), target 22,670").singleElement()
                .satisfies(o -> {
                    assertThat(o.side()).isEqualTo(OptionSide.CE);
                    assertThat(o.reason()).isEqualTo("EMA_CROSS_UP");
                });
        PositionView holding = new PositionView(true, OptionSide.CE, 1, 50, null, 52);
        assertThat(d.at("10:05", 22650, holding, "structure.ema9", 22620.0, "structure.ema20", 22605.0).orders()).isEmpty();
        assertThat(d.at("10:06", 22671, holding, "structure.ema9", 22630.0, "structure.ema20", 22608.0).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("TARGET");

        Day far = new Day("ma-cross.v1.yaml", Map.of("structure.lastSwingLow", 22500.0, "structure.lastSwingHigh", 22700.0));
        far.at("10:00", 22600, PositionView.FLAT, "structure.ema9", 22598.0, "structure.ema20", 22602.0);
        assertThat(far.at("10:01", 22610, PositionView.FLAT, "structure.ema9", 22605.0, "structure.ema20", 22601.0).orders())
                .as("the swing stop is 110 points = 5.5 ATR away: skip").isEmpty();

        Day down = new Day("ma-cross.v1.yaml", Map.of("structure.lastSwingLow", 22500.0, "structure.lastSwingHigh", 22630.0));
        down.at("11:00", 22600, PositionView.FLAT, "structure.ema9", 22603.0, "structure.ema20", 22601.0);
        assertThat(down.at("11:01", 22590, PositionView.FLAT, "structure.ema9", 22598.0, "structure.ema20", 22601.0).orders())
                .singleElement().extracting(OrderIntent::side).isEqualTo(OptionSide.PE);
    }

    /**
     * A-158 aob-v1: a quiet morning (3-minute share 0.001, 1,000 futures a minute), then at 09:41 a 4x share with the last
     * three minutes at 2,000 a minute (2x the day's median window): buy the call. Held through ordinary flow against it, out
     * on a 3x burst the other way. The same burst without the volume does not enter.
     */
    @Test
    void atmOiBurstBuysTheFlowsDirectionWithVolumeAndExitsOnTheOppositeBurst() {
        Day d = new Day("atm-oi-burst.v1.yaml", Map.of());
        Day quiet = new Day("atm-oi-burst.v1.yaml", Map.of());
        for (int m = 16; m <= 40; m++) {
            String t = String.format("09:%02d", m);
            double share = m % 2 == 0 ? 0.001 : -0.001;
            long volume = m >= 39 ? 2000L : 1000L;
            assertThat(d.at(t, 22600, PositionView.FLAT, "options.atmBandShare3m", share, "futures.volumeLastMinute", volume)
                    .orders()).isEmpty();
            quiet.at(t, 22600, PositionView.FLAT, "options.atmBandShare3m", share, "futures.volumeLastMinute", 1000L);
        }
        assertThat(d.at("09:41", 22610, PositionView.FLAT, "options.atmBandShare3m", 0.004, "futures.volumeLastMinute", 2000L)
                .orders()).singleElement().satisfies(o -> {
                    assertThat(o.side()).isEqualTo(OptionSide.CE);
                    assertThat(o.reason()).isEqualTo("OI_BURST_UP");
                });
        assertThat(quiet.at("09:41", 22610, PositionView.FLAT, "options.atmBandShare3m", 0.004, "futures.volumeLastMinute", 1000L)
                .orders()).as("no volume, no entry").isEmpty();

        PositionView holding = new PositionView(true, OptionSide.CE, 1, 50, null, 52);
        assertThat(d.at("09:45", 22620, holding, "options.atmBandShare3m", -0.0015, "futures.volumeLastMinute", 1000L).orders())
                .as("ordinary flow against: hold").isEmpty();
        assertThat(d.at("09:50", 22600, holding, "options.atmBandShare3m", -0.005, "futures.volumeLastMinute", 1000L).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("OPPOSITE_BURST");
    }

    /**
     * A-159 aob-v2: the same entry as v1; an opposite burst no longer exits; the premium trail does: armed at +20 % (bid 60
     * on a 50 premium), out once the bid is 15 % below its peak (70 -> 59.5).
     */
    @Test
    void atmOiBurstV2HoldsThroughTheOppositeBurstAndExitsOnThePremiumTrail() {
        Day d = new Day("atm-oi-burst.v2.yaml", Map.of());
        for (int m = 16; m <= 40; m++) {
            d.at(String.format("09:%02d", m), 22600, PositionView.FLAT, "options.atmBandShare3m", m % 2 == 0 ? 0.001 : -0.001,
                    "futures.volumeLastMinute", m >= 39 ? 2000L : 1000L);
        }
        assertThat(d.at("09:41", 22610, PositionView.FLAT, "options.atmBandShare3m", 0.004, "futures.volumeLastMinute", 2000L)
                .orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("OI_BURST_UP");
        Object[] quiet = {"options.atmBandShare3m", 0.001, "futures.volumeLastMinute", 1000L};
        assertThat(d.at("09:45", 22600, new PositionView(true, OptionSide.CE, 1, 50, null, 48),
                "options.atmBandShare3m", -0.005, "futures.volumeLastMinute", 1000L).orders()).as("opposite burst: v2 holds").isEmpty();
        assertThat(d.at("09:50", 22640, new PositionView(true, OptionSide.CE, 1, 50, null, 60), quiet).orders()).isEmpty();
        assertThat(d.at("09:55", 22660, new PositionView(true, OptionSide.CE, 1, 50, null, 70), quiet).orders()).isEmpty();
        assertThat(d.at("10:00", 22645, new PositionView(true, OptionSide.CE, 1, 50, null, 59.5), quiet).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("TRAIL_15");
    }

    /**
     * A-161 aob-v4: a call from 09:41; puts written at 4x the day's mean at a new high exit it (OI_CLIMAX) only once it is
     * 10 minutes old, in profit and at a new extreme: not at 09:45 (too young), not at 09:52 (no new high), yes at 09:53.
     */
    @Test
    void atmOiBurstV4ExitsOnTheDrivingOiClimaxAtANewExtreme() {
        Day d = new Day("atm-oi-burst.v4.yaml", Map.of());
        for (int m = 16; m <= 40; m++) {
            double x = m % 2 == 0 ? 0.001 : -0.001;
            d.at(String.format("09:%02d", m), 22600, PositionView.FLAT, "options.atmBandShare3m", x,
                    "options.atmBandCallShare3m", -x / 2, "options.atmBandPutShare3m", x / 2,
                    "futures.volumeLastMinute", m >= 39 ? 2000L : 1000L);
        }
        assertThat(d.at("09:41", 22610, PositionView.FLAT, "options.atmBandShare3m", 0.004, "options.atmBandCallShare3m", -0.001,
                "options.atmBandPutShare3m", 0.003, "futures.volumeLastMinute", 2000L).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("OI_BURST_UP");
        java.time.Instant opened = Snapshots.SESSION.atTime(9, 41).atZone(com.autotrade.core.time.MarketTime.IST).toInstant();
        PositionView held = new PositionView(true, OptionSide.CE, 1, 50, opened, 55);
        Object[] climax = {"options.atmBandShare3m", 0.0015, "options.atmBandCallShare3m", 0.0005, "options.atmBandPutShare3m", 0.002,
                "futures.volumeLastMinute", 1000L};
        Object[] calm = {"options.atmBandShare3m", 0.0, "options.atmBandCallShare3m", 0.0, "options.atmBandPutShare3m", 0.0,
                "futures.volumeLastMinute", 1000L};
        assertThat(d.at("09:42", 22615, held, calm).orders()).isEmpty();
        assertThat(d.at("09:45", 22630, held, climax).orders()).as("4 minutes old").isEmpty();
        assertThat(d.at("09:50", 22640, held, calm).orders()).isEmpty();
        assertThat(d.at("09:52", 22635, held, climax).orders()).as("no new high").isEmpty();
        assertThat(d.at("09:53", 22650, held, climax).orders()).singleElement().extracting(OrderIntent::reason)
                .isEqualTo("OI_CLIMAX");
    }

    /**
     * A-179 aob-v5: the same 4x up burst at 09:41 with volume. Driven by calls closing (call share -0.003 against a put
     * share +0.001) it enters; driven by puts written (put share +0.003, calls -0.001) it is a writing burst and v5 skips it,
     * while v2 takes both.
     */
    @Test
    void atmOiBurstV5EntersOnlyOnACoveringBurst() {
        for (boolean coveringBurst : new boolean[] {true, false}) {
            Day v5 = new Day("atm-oi-burst.v5.yaml", Map.of());
            Day v2 = new Day("atm-oi-burst.v2.yaml", Map.of());
            for (int m = 16; m <= 40; m++) {
                double x = m % 2 == 0 ? 0.001 : -0.001;
                Object[] f = {"options.atmBandShare3m", x, "options.atmBandCallShare3m", -x / 2, "options.atmBandPutShare3m", x / 2,
                        "futures.volumeLastMinute", m >= 39 ? 2000L : 1000L};
                v5.at(String.format("09:%02d", m), 22600, PositionView.FLAT, f);
                v2.at(String.format("09:%02d", m), 22600, PositionView.FLAT, f);
            }
            Object[] burst = {"options.atmBandShare3m", 0.004,
                    "options.atmBandCallShare3m", coveringBurst ? -0.003 : -0.001, "options.atmBandPutShare3m", coveringBurst ? 0.001 : 0.003,
                    "futures.volumeLastMinute", 2000L};
            assertThat(v2.at("09:41", 22610, PositionView.FLAT, burst).orders()).singleElement()
                    .extracting(OrderIntent::reason).isEqualTo("OI_BURST_UP");
            if (coveringBurst) {
                assertThat(v5.at("09:41", 22610, PositionView.FLAT, burst).orders()).singleElement()
                        .extracting(OrderIntent::reason).isEqualTo("OI_BURST_UP");
            } else {
                assertThat(v5.at("09:41", 22610, PositionView.FLAT, burst).orders()).as("writing burst: v5 skips").isEmpty();
            }
        }
    }

    /**
     * A-180 aob-v6, the 23 Oct morning shape: a loud open (3-minute shares of +-0.01 to 09:29), a quiet half hour (+-0.001),
     * then from 09:50 a steady -0.0025 a minute: 2.5x the 09:30 mean, never a 3x spike, on ordinary volume. v6's
     * sustained-flow trigger buys the put (OI_FLOW_DOWN) once four of the five 3-minute shares are on the put side; v2
     * never enters.
     */
    @Test
    void atmOiBurstV6BuysASustainedFlowThatNeverSpikes() {
        Day v6 = new Day("atm-oi-burst.v6.yaml", Map.of());
        Day v2 = new Day("atm-oi-burst.v2.yaml", Map.of());
        String entered = null, reason = null;
        for (int m = 16; m <= 70; m++) {
            String t = String.format("%02d:%02d", 9 + m / 60, m % 60);
            double share = m < 30 ? (m % 2 == 0 ? 0.01 : -0.01) : m < 50 ? (m % 2 == 0 ? 0.001 : -0.001) : -0.0025;
            Object[] f = {"options.atmBandShare3m", share, "futures.volumeLastMinute", 1000L};
            assertThat(v2.at(t, 22600 - Math.max(0, m - 50) * 5, PositionView.FLAT, f).orders()).as("v2 at " + t).isEmpty();
            List<OrderIntent> o = v6.at(t, 22600 - Math.max(0, m - 50) * 5, PositionView.FLAT, f).orders();
            if (entered == null && !o.isEmpty()) {
                entered = t;
                reason = o.get(0).reason();
                assertThat(o.get(0).side()).isEqualTo(OptionSide.PE);
            }
        }
        assertThat(entered).as("v6 enters during the run").isNotNull().isBetween("09:55", "10:05");
        assertThat(reason).isEqualTo("OI_FLOW_DOWN");
    }

    /**
     * A-181 aob-v7, the 23 Oct 13:56 shape: no flow, one big window (+0.03 at 10:00), small ones on its side (+0.002) and
     * then the newest the other way (-0.003 at 10:12). v6's 15-minute sum rides the spike (OI_FLOW_UP at 10:09: the spike
     * is 83 % of it); v7 skips that and enters only at 10:10, when four +0.002 windows without the spike make the sum. On a
     * steady run (the A-180 morning shape) v7 still buys the put like v6.
     */
    @Test
    void atmOiBurstV7NeedsTheSustainedFlowToBeSpreadAndCurrent() {
        Day v6 = new Day("atm-oi-burst.v6.yaml", Map.of());
        Day v7 = new Day("atm-oi-burst.v7.yaml", Map.of());
        String v6At = null, v7At = null;
        for (int m = 16; m <= 90; m++) {
            String t = String.format("%02d:%02d", 9 + m / 60, m % 60);
            double share = m == 60 ? 0.03 : m > 60 && m < 72 ? 0.002 : m == 72 ? -0.003 : 0.0;
            Object[] f = {"options.atmBandShare3m", share, "futures.volumeLastMinute", 1000L};
            List<OrderIntent> o6 = v6.at(t, 22600, PositionView.FLAT, f).orders();
            if (v6At == null && !o6.isEmpty()) {
                v6At = t;
                assertThat(o6.get(0).reason()).isEqualTo("OI_FLOW_UP");
            }
            if (v7At == null && !v7.at(t, 22600, PositionView.FLAT, f).orders().isEmpty()) {
                v7At = t;
            }
        }
        assertThat(v6At).as("v6 rides the spike").isEqualTo("10:09");
        assertThat(v7At).as("v7 waits for windows without the spike").isEqualTo("10:10");

        Day steady = new Day("atm-oi-burst.v7.yaml", Map.of());
        String entered = null;
        for (int m = 16; m <= 70; m++) {
            String t = String.format("%02d:%02d", 9 + m / 60, m % 60);
            double share = m < 30 ? (m % 2 == 0 ? 0.01 : -0.01) : m < 50 ? (m % 2 == 0 ? 0.001 : -0.001) : -0.0025;
            List<OrderIntent> o = steady.at(t, 22600, PositionView.FLAT, "options.atmBandShare3m", share, "futures.volumeLastMinute", 1000L).orders();
            if (entered == null && !o.isEmpty()) {
                entered = t;
                assertThat(o.get(0).reason()).isEqualTo("OI_FLOW_DOWN");
            }
        }
        assertThat(entered).as("v7 still takes a steady run").isNotNull();
    }

    /**
     * A-186 aob-v9: a 4x burst with 2x volume at 09:41 (before 09:45: v9 waits), then another at 09:51 with the index above
     * or below the day's open. v2 (shown only the 09:51 burst) takes the call either way; v9 only above the open.
     */
    @Test
    void atmOiBurstV9WaitsFor0945AndTradesWithTheDay() {
        for (boolean aboveOpen : new boolean[] {true, false}) {
            Day v2 = new Day("atm-oi-burst.v2.yaml", Map.of());
            Day v9 = new Day("atm-oi-burst.v9.yaml", Map.of());
            double open = aboveOpen ? 22590 : 22630;
            for (int m = 16; m <= 50; m++) {
                String t = String.format("09:%02d", m);
                boolean early = m == 41;
                Object[] f = {"options.atmBandShare3m", early ? 0.004 : m % 2 == 0 ? 0.001 : -0.001,
                        "futures.volumeLastMinute", m >= 39 && m <= 41 || m >= 49 ? 2000L : 1000L, "structure.dayOpen", open};
                List<OrderIntent> o9 = v9.at(t, 22610, PositionView.FLAT, f).orders();
                assertThat(o9).as("v9 before 09:51 at " + t).isEmpty();
                v2.at(t, 22610, PositionView.FLAT, early ? new Object[] {"options.atmBandShare3m", 0.001, "futures.volumeLastMinute", 2000L,
                        "structure.dayOpen", open} : f);                    // v2 sees no 09:41 burst, so its 15-minute gap is free
            }
            Object[] burst = {"options.atmBandShare3m", 0.004, "futures.volumeLastMinute", 2000L, "structure.dayOpen", open};
            assertThat(v2.at("09:51", 22610, PositionView.FLAT, burst).orders()).singleElement()
                    .extracting(OrderIntent::reason).isEqualTo("OI_BURST_UP");
            List<OrderIntent> o9 = v9.at("09:51", 22610, PositionView.FLAT, burst).orders();
            if (aboveOpen) {
                assertThat(o9).singleElement().extracting(OrderIntent::reason).isEqualTo("OI_BURST_UP");
            } else {
                assertThat(o9).as("call below the day's open: v9 skips").isEmpty();
            }
        }
    }

    /**
     * A-172 odm-v9, the 22 Oct shape: open 22,690, a flush to 22,560 by 09:18, then a flat base. At 09:24 the drive holds
     * (−0.47 %, near the low) and v4 buys the put; v9 sees no new low since 09:21 and waits; a new low at 09:26 makes the
     * 09:27 check fresh and v9 enters then.
     */
    @Test
    void odmV9NeedsAFreshExtremeAndWaitsOutAStaleDrive() {
        Map<String, Object> open = Map.of("structure.dayOpen", 22690.0, "breadth.moverBreadth", -77.0);
        Day v4 = new Day("opening-drive-momentum.v4.yaml", open), v9 = new Day("opening-drive-momentum.v9.yaml", open);
        double[] path = {22690, 22600, 22570, 22560, 22590, 22605, 22615, 22590, 22579};   // 09:15 .. 09:23 (a bounce at 09:21, as on 22 Oct)
        for (int i = 0; i < path.length; i++) {
            for (Day d : new Day[] {v4, v9}) {
                d.at(String.format("09:%02d", 15 + i), path[i]);
            }
        }
        assertThat(v4.at("09:24", 22584).orders()).singleElement().extracting(OrderIntent::side).isEqualTo(OptionSide.PE);
        assertThat(v9.at("09:24", 22584).orders()).as("stale: no new low since 09:21").isEmpty();
        v9.at("09:25", 22570);
        v9.at("09:26", 22550);                                                    // a new session low
        assertThat(v9.at("09:27", 22552).orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("OPENING_DRIVE_DOWN");
        });
    }

    /**
     * A-078: an ORL break at 09:51, before EMA20 exists (as on 12 Oct). orc-v1 has no trend then and stays out; orc-v2
     * takes the VWAP side and the close below EMA9 as the trend and buys the put, stop 1 ATR back above the ORL.
     */
    @Test
    void orcV2TakesAnOpeningRangeBreakBeforeEma20ExistsAndV1DoesNot() {
        Map<String, Object> or = Map.of("structure.orComplete", true, "structure.orHigh", 22647.0,
                "structure.orLow", 22515.0, "futures.rvolTod", 2.0, "structure.ema9", 22530.0);
        for (String version : new String[] {"v1", "v2"}) {
            Day d = new Day("opening-range-continuation." + version + ".yaml", or);
            d.dte = 1;                                              // the index does not expire today
            LocalTime t = LocalTime.of(9, 16);
            for (; t.isBefore(LocalTime.of(9, 51)); t = t.plusMinutes(1)) {
                assertThat(d.at(t.toString(), 22540).orders()).isEmpty();   // inside the range, below the VWAP
            }
            Decision brk = d.at("09:51", 22505);                    // first 3-minute close below ORL - 0.1 ATR (22,513)
            if (version.equals("v1")) {
                assertThat(brk.orders()).as("v1: EMA20 unknown, no trend").isEmpty();
            } else {
                assertThat(brk.orders()).singleElement().satisfies(o -> {
                    assertThat(o.side()).isEqualTo(OptionSide.PE);
                    assertThat(o.reason()).isEqualTo("OR_CONTINUATION_DOWN");
                });
            }
        }
    }

    /**
     * A-103: mac-v4 skips a cross signalled 10:30-12:30 unless A-grade (IV pct >= 50 or rising, breadth >= 50 its way);
     * outside the window it is mac-v1. 14 Oct: the 10:42 calls had breadth +45 / +33 (skipped), 12:54 is outside.
     */
    @Test
    void maCrossV4NeedsTheAGradeOnlyInsideTheQuietWindow() {
        Map<String, Object> swings = Map.of("structure.lastSwingLow", 22580.0, "structure.lastSwingHigh", 22700.0);
        Day v1 = new Day("ma-cross.v1.yaml", swings), v4 = new Day("ma-cross.v4.yaml", swings);
        for (Day d : new Day[] {v1, v4}) {
            d.at("10:41", 22600, PositionView.FLAT, "structure.ema9", 22598.0, "structure.ema20", 22602.0);
        }
        Object[] cross = {"structure.ema9", 22605.0, "structure.ema20", 22601.0, "volatility.atmIvPercentile", 81.0,
                "breadth.moverBreadth", 44.8};
        assertThat(v1.at("10:42", 22610, PositionView.FLAT, cross).orders()).as("v1 takes it").hasSize(1);
        assertThat(v4.at("10:42", 22610, PositionView.FLAT, cross).orders()).as("v4: breadth 44.8 < 50 in the window").isEmpty();

        Day strong = new Day("ma-cross.v4.yaml", swings);
        strong.at("11:00", 22600, PositionView.FLAT, "structure.ema9", 22598.0, "structure.ema20", 22602.0);
        assertThat(strong.at("11:01", 22610, PositionView.FLAT, "structure.ema9", 22605.0, "structure.ema20", 22601.0,
                "volatility.atmIvPercentile", 60.0, "breadth.moverBreadth", 62.0).orders()).as("A-grade passes").hasSize(1);

        Day after = new Day("ma-cross.v4.yaml", swings);
        after.at("12:53", 22600, PositionView.FLAT, "structure.ema9", 22598.0, "structure.ema20", 22602.0);
        assertThat(after.at("12:54", 22610, PositionView.FLAT, "structure.ema9", 22605.0, "structure.ema20", 22601.0,
                "breadth.moverBreadth", 10.0).orders()).as("after 12:30: mac-v1 rules, no grade needed").hasSize(1);
    }

    /** A-079: mac-v2 takes a cross only on the VWAP's side (VWAP 22,600 here); v1 takes both. */
    @Test
    void maCrossV2TakesOnlyCrossesOnTheVwapSide() {
        for (String version : new String[] {"v1", "v2"}) {
            Day below = new Day("ma-cross." + version + ".yaml", Map.of("structure.lastSwingLow", 22570.0,
                    "structure.lastSwingHigh", 22700.0));
            below.at("12:38", 22585, PositionView.FLAT, "structure.ema9", 22584.0, "structure.ema20", 22586.0);
            Decision up = below.at("12:39", 22590, PositionView.FLAT, "structure.ema9", 22588.0, "structure.ema20", 22587.0);
            if (version.equals("v1")) {
                assertThat(up.orders()).as("v1: a cross up below VWAP is taken").singleElement()
                        .extracting(OrderIntent::side).isEqualTo(OptionSide.CE);
            } else {
                assertThat(up.orders()).as("v2: a cross up below VWAP is skipped").isEmpty();
            }
            Day above = new Day("ma-cross." + version + ".yaml", Map.of("structure.lastSwingLow", 22580.0,
                    "structure.lastSwingHigh", 22700.0));
            above.at("10:00", 22600, PositionView.FLAT, "structure.ema9", 22598.0, "structure.ema20", 22602.0);
            assertThat(above.at("10:01", 22610, PositionView.FLAT, "structure.ema9", 22605.0, "structure.ema20", 22601.0)
                    .orders()).as(version + ": a cross up above VWAP is taken").singleElement()
                    .extracting(OrderIntent::side).isEqualTo(OptionSide.CE);
        }
    }

    /**
     * A-081: tsa-v1 waits for EMA20, then buys the put once at the first 3-minute close 10:15-10:30 with the index
     * below VWAP, EMA9 below EMA20 and below the ORL (12 Oct NIFTY's state at 10:18); not again that day.
     */
    @Test
    void trendStateBuysThePutOnceWhenTheDownTrendStateHoldsAfterEma20Exists() {
        Map<String, Object> or = Map.of("structure.orComplete", true, "structure.orHigh", 22647.0,
                "structure.orLow", 22515.0, "structure.vwapSpotProxy", 22558.0);
        Day d = new Day("trend-state.v1.yaml", or);
        d.dte = 1;
        LocalTime t = LocalTime.of(9, 16);
        for (; t.isBefore(LocalTime.of(10, 15)); t = t.plusMinutes(1)) {
            assertThat(d.at(t.toString(), 22490, PositionView.FLAT, "structure.ema9", 22505.0).orders()).isEmpty();
        }
        assertThat(d.at("10:15", 22486, PositionView.FLAT, "structure.ema9", 22505.0).orders())
                .as("EMA20 not there yet").isEmpty();
        d.at("10:16", 22486, PositionView.FLAT, "structure.ema9", 22505.0, "structure.ema20", 22529.0);
        d.at("10:17", 22486, PositionView.FLAT, "structure.ema9", 22505.0, "structure.ema20", 22529.0);
        Decision entry = d.at("10:18", 22480, PositionView.FLAT, "structure.ema9", 22503.0, "structure.ema20", 22527.0);
        assertThat(entry.orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("TREND_STATE_DOWN");
        });
        d.at("10:19", 22478, PositionView.FLAT, "structure.ema9", 22500.0, "structure.ema20", 22525.0);
        d.at("10:20", 22476, PositionView.FLAT, "structure.ema9", 22500.0, "structure.ema20", 22525.0);
        assertThat(d.at("10:21", 22470, PositionView.FLAT, "structure.ema9", 22498.0, "structure.ema20", 22523.0).orders())
                .as("one trade per index per day").isEmpty();
    }

    /**
     * A-082: the 10:00-10:15 candle closes above the ORH (22,647) with futures volume 2x normal; with put OI building the
     * ORB buys the call, stop at the candle's lowest minute close (22,640), target 2R; without the OI build it stays out.
     */
    @Test
    void orbBuysTheCallOnA15MinuteCloseAboveTheRangeOnlyWithPutOiBuilding() {
        for (String putFlow : new String[] {"BUILD", "UNWIND"}) {
            Map<String, Object> or = Map.of("structure.orComplete", true, "structure.orHigh", 22647.0,
                    "structure.orLow", 22515.0, "futures.rvolTod", 2.0, "options.peOiFlow", putFlow);
            Day d = new Day("opening-range-breakout.v1.yaml", or);
            d.dte = 1;
            LocalTime t = LocalTime.of(9, 16);
            for (; t.isBefore(LocalTime.of(10, 1)); t = t.plusMinutes(1)) {
                assertThat(d.at(t.toString(), 22600).orders()).isEmpty();
            }
            for (; t.isBefore(LocalTime.of(10, 15)); t = t.plusMinutes(1)) {
                assertThat(d.at(t.toString(), 22640 + (t.getMinute() - 1) * 0.5).orders()).isEmpty();
            }
            Decision close = d.at("10:15", 22660);
            if (putFlow.equals("BUILD")) {
                assertThat(close.orders()).singleElement().satisfies(o -> {
                    assertThat(o.side()).isEqualTo(OptionSide.CE);
                    assertThat(o.reason()).isEqualTo("ORB_UP");
                });
            } else {
                assertThat(close.orders()).as("put OI not building: no entry").isEmpty();
            }
        }
    }

    /** A-085: 12 Oct NIFTY's opening: open 22,593, high 22,644 at 09:17, 22,524 at 09:24 (-0.31 %, at the low): the put. */
    @Test
    void openingDriveMomentumBuysThePutWhenTheDriveIsStillPressingAt0924() {
        Day d = new Day("opening-drive-momentum.v1.yaml", Map.of("structure.dayOpen", 22593.0));
        d.dte = 1;
        double[] path = {22633, 22644, 22635, 22610, 22588};          // 09:16 .. 09:20
        LocalTime t = LocalTime.of(9, 16);
        for (double spot : path) {
            assertThat(d.at(t.toString(), spot).orders()).isEmpty();
            t = t.plusMinutes(1);
        }
        assertThat(d.at("09:21", 22586).orders()).as("-0.03 % from the open: no drive yet").isEmpty();
        d.at("09:22", 22585);
        d.at("09:23", 22560);
        assertThat(d.at("09:24", 22524).orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("OPENING_DRIVE_DOWN");
        });
        d.at("09:25", 22530);
        d.at("09:26", 22529);
        assertThat(d.at("09:27", 22520).orders()).as("one trade per index per day").isEmpty();
    }

    /** A-087 / A-088: odm-v2 (IV) and odm-v3 (breadth) veto odm-v1's 09:24 put unless their filter holds. */
    @Test
    void ivAndBreadthFiltersVetoTheDriveUnlessTheyHold() {
        Object[][] cases = {
            {"opening-drive-momentum.v2.yaml", "volatility.atmIvPercentile", 30.0, "options.atmIvChange3m", -0.01, false},
            {"opening-drive-momentum.v2.yaml", "volatility.atmIvPercentile", 30.0, "options.atmIvChange3m", 0.02, true},
            {"opening-drive-momentum.v2.yaml", "volatility.atmIvPercentile", 60.0, "options.atmIvChange3m", -0.01, true},
            {"opening-drive-momentum.v3.yaml", "breadth.moverBreadth", -93.0, "breadth.momentumBreadth", 0.0, true},
            {"opening-drive-momentum.v3.yaml", "breadth.moverBreadth", -20.0, "breadth.momentumBreadth", 0.0, false},
        };
        for (Object[] k : cases) {
            Day d = new Day((String) k[0], Map.of("structure.dayOpen", 22593.0, (String) k[1], k[2], (String) k[3], k[4]));
            d.dte = 1;
            double[] path = {22633, 22644, 22635, 22610, 22588, 22586, 22585, 22560};   // 09:16 .. 09:23
            LocalTime t = LocalTime.of(9, 16);
            for (double spot : path) {
                d.at(t.toString(), spot);
                t = t.plusMinutes(1);
            }
            Decision at0924 = d.at("09:24", 22524);
            if ((Boolean) k[5]) {
                assertThat(at0924.orders()).as(k[0] + " " + k[2] + " " + k[4]).singleElement()
                        .extracting(OrderIntent::side).isEqualTo(OptionSide.PE);
            } else {
                assertThat(at0924.orders()).as(k[0] + " " + k[2] + " " + k[4]).isEmpty();
            }
        }
    }

    /** A-091: odm-v4's peak trail arms at +50 % and exits when the bid is 15 % below its peak; odm-v3 has no trail. */
    @Test
    void odmV4TrailsTheHeldOptionFromItsPeak() {
        Day d = new Day("opening-drive-momentum.v4.yaml", Map.of("structure.dayOpen", 22593.0));
        d.dte = 1;
        java.time.Instant opened = Snapshots.SESSION.atTime(9, 24).atZone(com.autotrade.core.time.MarketTime.IST).toInstant();
        double[] bids = {130, 155, 170, 145};                       // +30 % (not armed), armed at 155, peak 170, 145 > 144.5
        LocalTime t = LocalTime.of(10, 0);
        for (double bid : bids) {
            assertThat(d.at(t.toString(), 22500, new PositionView(true, OptionSide.PE, 1, 100, opened, bid)).orders())
                    .as("bid " + bid).isEmpty();
            t = t.plusMinutes(1);
        }
        assertThat(d.at(t.toString(), 22500, new PositionView(true, OptionSide.PE, 1, 100, opened, 144)).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("TRAIL_15");

        Day v3 = new Day("opening-drive-momentum.v3.yaml", Map.of("structure.dayOpen", 22593.0));
        v3.dte = 1;
        LocalTime u = LocalTime.of(10, 0);
        for (double bid : new double[] {155, 170, 144, 120}) {
            assertThat(v3.at(u.toString(), 22500, new PositionView(true, OptionSide.PE, 1, 100, opened, bid)).orders()).isEmpty();
            u = u.plusMinutes(1);
        }
    }

    /**
     * A-131: 1 Oct NIFTY-like. ORL 22,508 (ATR 20). Quiet minutes around 22,540 with futures volume 1,000 a minute; at 12:07
     * the close breaks below ORL with call OI building, put OI unwinding and 5,000 futures volume (5x): the put. It is held
     * while the index stays below ORL; back above ORL with put OI building and call OI unwinding it exits.
     */
    @Test
    void zoneBreakFlowBuysThePutOnABreakWithFlowAndVolumeThenExitsOnLevelBackWithOiReversed() {
        Map<String, Object> lv = Map.of("structure.orComplete", true, "structure.orHigh", 22589.0, "structure.orLow", 22508.0);
        Day d = new Day("zone-break-flow.v1.yaml", lv);
        LocalTime t = LocalTime.of(9, 20);
        for (int i = 0; t.isBefore(LocalTime.of(12, 6)); t = t.plusMinutes(1), i++) {
            assertThat(d.at(t.toString(), 22540 + (i % 5) * 3.0, PositionView.FLAT, "futures.volumeLastMinute", 1000L)
                    .orders()).isEmpty();
        }
        assertThat(d.at("12:06", 22512, PositionView.FLAT, "futures.volumeLastMinute", 5000L,
                "options.ceOiChange1m", -300000.0, "options.peOiChange1m", 200000.0).orders())
                .as("no break yet, flow the other way").isEmpty();
        Decision entry = d.at("12:07", 22503, PositionView.FLAT, "futures.volumeLastMinute", 5000L,
                "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -700000.0);
        assertThat(entry.orders()).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.PE);
            assertThat(o.reason()).isEqualTo("ZONE_BREAK_FLOW_DOWN");
        });
        PositionView holding = new PositionView(true, OptionSide.PE, 1, 50, null, 52);
        assertThat(d.at("12:20", 22515, holding, "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -100000.0).orders())
                .as("above ORL but the flow is still bearish: hold").isEmpty();
        assertThat(d.at("12:21", 22490, holding, "options.ceOiChange1m", -300000.0, "options.peOiChange1m", 400000.0).orders())
                .as("flow reversed but below ORL: hold").isEmpty();
        assertThat(d.at("12:22", 22516, holding, "options.ceOiChange1m", -300000.0, "options.peOiChange1m", 400000.0).orders())
                .singleElement().extracting(OrderIntent::reason).isEqualTo("LEVEL_BACK_OI_REVERSED");
    }

    /** A-133: the same 1 Oct-like break; v2 exits on the close back above ORL without the OI condition, v3 on 1 ATR. */
    @Test
    void zoneBreakFlowStopModes() {
        Map<String, Object> lv = Map.of("structure.orComplete", true, "structure.orHigh", 22589.0, "structure.orLow", 22508.0);
        for (String v : new String[] {"v2", "v3"}) {
            Day d = new Day("zone-break-flow." + v + ".yaml", lv);
            LocalTime t = LocalTime.of(9, 20);
            for (int i = 0; t.isBefore(LocalTime.of(12, 7)); t = t.plusMinutes(1), i++) {
                d.at(t.toString(), 22540 + (i % 5) * 3.0, PositionView.FLAT, "futures.volumeLastMinute", 1000L);
            }
            assertThat(d.at("12:07", 22503, PositionView.FLAT, "futures.volumeLastMinute", 5000L,
                    "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -700000.0).orders()).hasSize(1);
            PositionView holding = new PositionView(true, OptionSide.PE, 1, 50, null, 52);
            if (v.equals("v2")) {
                assertThat(d.at("12:20", 22515, holding, "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -100000.0)
                        .orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("LEVEL_BACK");
            } else {
                assertThat(d.at("12:20", 22515, holding).orders()).as("above ORL, inside 1 ATR (22,523)").isEmpty();
                assertThat(d.at("12:21", 22524, holding).orders()).singleElement()
                        .extracting(OrderIntent::reason).isEqualTo("STOP");
            }
        }
    }
    /** A-135: zbf-v4 takes profit at 2 ATR (22,503 - 40 = 22,463) below the entry. */
    @Test
    void zoneBreakFlowTwoAtrTarget() {
        Day d = new Day("zone-break-flow.v4.yaml", Map.of("structure.orComplete", true, "structure.orHigh", 22589.0,
                "structure.orLow", 22508.0));
        LocalTime t = LocalTime.of(9, 20);
        for (int i = 0; t.isBefore(LocalTime.of(12, 7)); t = t.plusMinutes(1), i++) {
            d.at(t.toString(), 22540 + (i % 5) * 3.0, PositionView.FLAT, "futures.volumeLastMinute", 1000L);
        }
        assertThat(d.at("12:07", 22503, PositionView.FLAT, "futures.volumeLastMinute", 5000L,
                "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -700000.0).orders()).hasSize(1);
        PositionView holding = new PositionView(true, OptionSide.PE, 1, 50, null, 52);
        assertThat(d.at("12:15", 22470, holding).orders()).isEmpty();
        assertThat(d.at("12:16", 22462, holding).orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("TARGET");
    }
    /**
     * A-136 zbf-v5: ORL 22,508 breaks at 12:07 without flow; the bearish flow and RVOL 2.5 arrive at 12:09 (within 3 closes,
     * still below ORL): the put. Armed once 1 ATR (20) in favour; three closes in a row of bullish OI flow exit it.
     */
    @Test
    void zoneBreakFlowV5ConfirmsWithinThreeClosesAndProtectsTheProfit() {
        Day d = new Day("zone-break-flow.v5.yaml", Map.of("structure.orComplete", true, "structure.orHigh", 22589.0,
                "structure.orLow", 22508.0));
        LocalTime t = LocalTime.of(9, 20);
        for (int i = 0; t.isBefore(LocalTime.of(12, 7)); t = t.plusMinutes(1), i++) {
            d.at(t.toString(), 22540 + (i % 5) * 3.0);
        }
        assertThat(d.at("12:07", 22503).orders()).as("the break, no flow, no volume").isEmpty();
        assertThat(d.at("12:08", 22500, PositionView.FLAT, "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -700000.0)
                .orders()).as("flow, but RVOL unknown").isEmpty();
        assertThat(d.at("12:09", 22498, PositionView.FLAT, "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -700000.0,
                "futures.rvolTod", 2.5).orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("ZONE_BREAK_FLOW_DOWN");
        PositionView holding = new PositionView(true, OptionSide.PE, 1, 50, null, 52);
        assertThat(d.at("12:15", 22470, holding).orders()).as("28 in favour: armed").isEmpty();
        Object[] against = {"options.ceOiChange1m", -300000.0, "options.peOiChange1m", 400000.0};
        assertThat(d.at("12:16", 22475, holding, against).orders()).isEmpty();
        assertThat(d.at("12:17", 22476, holding, against).orders()).isEmpty();
        assertThat(d.at("12:18", 22477, holding, against).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("PROTECT_OI_AGAINST");

        Day reclaim = new Day("zone-break-flow.v5.yaml", Map.of("structure.pdl", 22500.0));
        reclaim.at("09:40", 22495);
        assertThat(reclaim.at("09:41", 22503, PositionView.FLAT, "options.ceOiChange1m", -300000.0, "options.peOiChange1m",
                400000.0, "futures.rvolTod", 2.2).orders()).singleElement().extracting(OrderIntent::reason)
                .as("PDL reclaimed from below").isEqualTo("ZONE_BREAK_FLOW_UP");
    }
    /** A-137 zbf-v6: on the index's expiry day (dte 0) the same three closes of OI flow against the put do not exit it. */
    @Test
    void zoneBreakFlowV6KeepsTheExpiryDayRunner() {
        Day d = new Day("zone-break-flow.v6.yaml", Map.of("structure.orComplete", true, "structure.orHigh", 22589.0,
                "structure.orLow", 22508.0));
        d.dte = 0;
        LocalTime t = LocalTime.of(9, 20);
        for (int i = 0; t.isBefore(LocalTime.of(12, 7)); t = t.plusMinutes(1), i++) {
            d.at(t.toString(), 22540 + (i % 5) * 3.0);
        }
        assertThat(d.at("12:07", 22503, PositionView.FLAT, "options.ceOiChange1m", 300000.0, "options.peOiChange1m", -700000.0,
                "futures.rvolTod", 2.5).orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("ZONE_BREAK_FLOW_DOWN");
        PositionView holding = new PositionView(true, OptionSide.PE, 1, 50, null, 52);
        d.at("12:15", 22470, holding);
        Object[] against = {"options.ceOiChange1m", -300000.0, "options.peOiChange1m", 400000.0};
        for (String m : new String[] {"12:16", "12:17", "12:18", "12:19"}) {
            assertThat(d.at(m, 22476, holding, against).orders()).as(m).isEmpty();
        }
        assertThat(d.at("12:20", 22510, holding).orders()).singleElement().extracting(OrderIntent::reason).isEqualTo("LEVEL_BACK");
    }
    /**
     * A-141 zbf-v9: 16 Oct-like. The close back above ORL 22,942 from below is a call break; so is the close above the day
     * open 23,065. Each needs the same-minute bullish flow and 3x volume of v2.
     */
    @Test
    void zoneBreakFlowV9CountsTheOrlReclaimAndTheDayOpen() {
        Map<String, Object> lv = Map.of("structure.orComplete", true, "structure.orHigh", 23200.0, "structure.orLow", 22942.0,
                "structure.dayOpen", 23065.0);
        Object[] bull = {"options.ceOiChange1m", -300000.0, "options.peOiChange1m", 400000.0, "futures.volumeLastMinute", 5000L};
        Day reclaim = new Day("zone-break-flow.v9.yaml", lv);
        LocalTime t = LocalTime.of(9, 20);
        for (int i = 0; t.isBefore(LocalTime.of(9, 40)); t = t.plusMinutes(1), i++) {
            reclaim.at(t.toString(), 22930 - (i % 3) * 2.0, PositionView.FLAT, "futures.volumeLastMinute", 1000L);
        }
        assertThat(reclaim.at("09:40", 22950, PositionView.FLAT, bull).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("ZONE_BREAK_FLOW_UP");

        Day open = new Day("zone-break-flow.v9.yaml", lv);
        t = LocalTime.of(9, 20);
        for (int i = 0; t.isBefore(LocalTime.of(10, 7)); t = t.plusMinutes(1), i++) {
            open.at(t.toString(), 23050 + (i % 3) * 2.0, PositionView.FLAT, "futures.volumeLastMinute", 1000L);
        }
        assertThat(open.at("10:07", 23068, PositionView.FLAT, bull).orders()).singleElement()
                .extracting(OrderIntent::reason).isEqualTo("ZONE_BREAK_FLOW_UP");

        Day v2 = new Day("zone-break-flow.v2.yaml", lv);
        t = LocalTime.of(9, 20);
        for (int i = 0; t.isBefore(LocalTime.of(10, 7)); t = t.plusMinutes(1), i++) {
            v2.at(t.toString(), 23050 + (i % 3) * 2.0, PositionView.FLAT, "futures.volumeLastMinute", 1000L);
        }
        assertThat(v2.at("10:07", 23068, PositionView.FLAT, bull).orders()).as("v2: the day open is not a level").isEmpty();
    }
    /** A-145 zbf-v10: the v5 entry on NIFTY; the same minutes on SENSEX do not enter (NIFTY only, set in the file). */
    @Test
    void zoneBreakFlowV10TradesNiftyOnly() {
        for (String u : new String[] {"NIFTY", "SENSEX"}) {
            Day d = new Day("zone-break-flow.v10.yaml", Map.of("structure.orComplete", true, "structure.orHigh", 22589.0,
                    "structure.orLow", 22508.0, "underlying", u));
            LocalTime t = LocalTime.of(9, 20);
            for (int i = 0; t.isBefore(LocalTime.of(12, 7)); t = t.plusMinutes(1), i++) {
                d.at(t.toString(), 22540 + (i % 5) * 3.0);
            }
            var orders = d.at("12:07", 22503, PositionView.FLAT, "options.ceOiChange1m", 300000.0, "options.peOiChange1m",
                    -700000.0, "futures.rvolTod", 2.5).orders();
            if (u.equals("NIFTY")) {
                assertThat(orders).singleElement().extracting(OrderIntent::reason).isEqualTo("ZONE_BREAK_FLOW_DOWN");
            } else {
                assertThat(orders).as("SENSEX: not traded").isEmpty();
            }
        }
    }
}
