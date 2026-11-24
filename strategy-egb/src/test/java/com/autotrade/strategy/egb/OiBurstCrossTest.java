package com.autotrade.strategy.egb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autotrade.strategy.OptionSide;
import com.autotrade.strategy.PositionView;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.Strategy;

/** A-251: obc-v1 enters on the day's first OI-burst EMA cross only when it is a continuation of the move from the open. */
class OiBurstCrossTest {

    private static Strategy load() {
        return Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v1.yaml")).create("NIFTY", Snapshots.SESSION);
    }

    private static Snapshots snap(double spot, double dayOpen, double s3, double s1) {
        return new Snapshots().set("spot", spot).set("structure.prevClose", 22700.0).set("structure.dayOpen", dayOpen)
                .set("regime.dteTradingDays", 2).set("options.atmBandShare3m", s3).set("options.atmBandShare1m", s1);
    }

    /** 09:16-09:30 at 22,690 (below the EMA seeded at 22,700), then 09:31 at 22,720 with the given flow. */
    private static java.util.List<com.autotrade.strategy.OrderIntent> cross(Strategy s, double dayOpen, double s3, double s1) {
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22690, dayOpen, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        return s.decide(snap(22720, dayOpen, s3, s1).at("09:31"), PositionView.FLAT).orders();
    }

    @Test
    void anUpCrossWithAPutWritingBurstAboveTheOpenBuysTheCall() {
        assertThat(cross(load(), 22680, 0.07, 0.01)).singleElement().satisfies(o -> {
            assertThat(o.side()).isEqualTo(OptionSide.CE);
            assertThat(o.reason()).isEqualTo("OBC_UP");
        });
    }

    /** obc-v1 with extra lines under {@code setup:} or {@code exits:} (A-472 obc-v31's keys on the v1 cross). */
    private static Strategy withLines(Path dir, String section, String lines) throws java.io.IOException {
        String v1 = java.nio.file.Files.readString(Path.of("..", "config", "strategy", "oi-burst-cross.v1.yaml"));
        assertThat(v1).contains("\n" + section + ":\n");
        Path f = dir.resolve(Integer.toHexString(lines.hashCode())).resolve("oi-burst-cross.v1.yaml");
        java.nio.file.Files.createDirectories(f.getParent());
        java.nio.file.Files.writeString(f, v1.replace("\n" + section + ":\n", "\n" + section + ":\n" + lines));
        return Strategies.load(f).create("NIFTY", Snapshots.SESSION);
    }

    /** As {@link #cross} with the dealer-gamma proxy at the 09:31 burst. */
    private static java.util.List<com.autotrade.strategy.OrderIntent> crossGex(Strategy s, double gex) {
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        return s.decide(snap(22720, 22680, 0.07, 0.01).set("options.gexShare", gex).at("09:31"), PositionView.FLAT).orders();
    }

    /** A-471/A-472: gex_veto_ce 0.25 skips the call burst above 0.25 only; a missing gexShare never vetoes; the PE limit leaves calls alone. */
    @Test
    void gexVetoSkipsTheBurstWhenTheGammaShareIsAboveTheSidesLimit(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        String both = "  gex_veto_pe: 0.06\n  gex_veto_ce: 0.25\n";
        assertThat(crossGex(withLines(dir, "setup", both), 0.30)).as("0.30 > 0.25").isEmpty();
        assertThat(crossGex(withLines(dir, "setup", both), 0.20)).as("0.20 <= 0.25").singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
        assertThat(crossGex(withLines(dir, "setup", both), Double.NaN)).as("no gamma data").singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
        assertThat(crossGex(withLines(dir, "setup", "  gex_veto_pe: 0.06\n"), 0.30)).as("the PE limit only").singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
    }

    /**
     * A-469/A-470: on the traded index's expiry day the call bought at 09:31 for 100 is sold once a minute closes with the
     * bid below 100, 15 minutes or more after the entry; a one-tick dip inside a minute does not count, and on a day the
     * chain does not expire nothing happens.
     */
    @Test
    void belowEntryExitOnTheExpiryDayJudgedOnMinuteCloses(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        String lines = "  below_entry_exit_min: 15\n  below_entry_0dte_only: true\n  below_entry_minute_close: true\n"
                + "  below_entry_no_reentry: true\n";
        for (String expiry : new String[] {Snapshots.SESSION.toString(), Snapshots.SESSION.plusDays(2).toString()}) {
            Strategy s = withLines(dir, "exits", lines);
            assertThat(cross(s, 22680, 0.07, 0.01)).singleElement().satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
            java.util.function.BiFunction<String, Double, java.util.List<com.autotrade.strategy.OrderIntent>> at = (t, bid) ->
                    s.decide(snap(22720, 22680, 0.0, 0.0).set("options.expiry", expiry).at(t),
                            new PositionView(true, OptionSide.CE, 1, 100.0, null, bid)).orders();
            assertThat(at.apply("09:44:00", 90.0)).as("13 minutes in").isEmpty();
            assertThat(at.apply("09:45:00", 104.0)).as("14 minutes in, 09:44 closed at 90").isEmpty();
            assertThat(at.apply("09:45:30", 95.0)).as("a dip inside 09:45").isEmpty();
            assertThat(at.apply("09:45:50", 101.0)).isEmpty();
            assertThat(at.apply("09:46:00", 101.0)).as("15 minutes in, 09:45 closed at 101").isEmpty();
            assertThat(at.apply("09:47:00", 99.0)).as("09:46 closed at 101").isEmpty();
            java.util.List<com.autotrade.strategy.OrderIntent> at49 = at.apply("09:48:00", 99.0);
            if (expiry.equals(Snapshots.SESSION.toString())) {
                assertThat(at49).as("09:47 closed at 99 on the expiry day").singleElement()
                        .satisfies(o -> assertThat(o.reason()).isEqualTo("BELOW_ENTRY_15"));
            } else {
                assertThat(at49).as("not the expiry day").isEmpty();
            }
        }
    }

    @Test
    void noBurstOrAWrongOneMinuteSignMeansNoTrade() {
        assertThat(cross(load(), 22680, 0.05, 0.01)).as("below the 3-minute threshold").isEmpty();
        assertThat(cross(load(), 22680, 0.07, -0.01)).as("1-minute flow against").isEmpty();
    }

    @Test
    void aFirstEventThatIsAReversalEndsTheDay() {
        Strategy s = load();
        assertThat(cross(s, 22750, 0.07, 0.01)).as("up cross below the open: a reversal").isEmpty();
        for (int m = 572; m <= 590; m++) {                                    // back below the EMA
            s.decide(snap(22650, 22600, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22760, 22600, 0.08, 0.02).at("09:51"), PositionView.FLAT).orders())
                .as("a later continuation is not the day's first event").isEmpty();
    }

    /** A-252 v3: already above the EMA for 15 minutes (no fresh cross), a burst its way enters; v1 does not. */
    @Test
    void v3EntersOnABurstWithoutAFreshCross() {
        for (String file : new String[] {"oi-burst-cross.v1.yaml", "oi-burst-cross.v3.yaml"}) {
            Strategy s = Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22720 + (m - 556), 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            var orders = s.decide(snap(22740, 22680, 0.07, 0.01).at("09:31"), PositionView.FLAT).orders();
            assertThat(orders).as(file).hasSize(file.contains("v3") ? 1 : 0);
        }
    }

    /** A-252 v2: a put's stop is the PDH above the entry plus 0.1 %, so a 6-point poke above the PDH does not stop it. */
    @Test
    void v2PutsItsStopAtThePdhPlusTheBuffer() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v2.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22710, 22600, 0.0, 0.0).set("structure.pdh", 22580.5).set("structure.dayHigh", 22720.0)
                    .at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22530, 22600, -0.07, -0.01).set("structure.pdh", 22580.5).set("structure.dayHigh", 22720.0)
                .at("09:31"), PositionView.FLAT).orders()).singleElement()
                .satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));
        PositionView open = new PositionView(true, OptionSide.PE, 1, 100, null, 90);
        assertThat(s.decide(snap(22586.6, 22600, 0.0, 0.0).set("structure.pdh", 22580.5).at("09:35"), open).orders())
                .as("22,586.6 is below 22,580.5 x 1.001 = 22,603.1").isEmpty();
        assertThat(s.decide(snap(22605, 22600, 0.0, 0.0).set("structure.pdh", 22580.5).at("09:36"), open).orders())
                .singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("STOP"));
    }

    /** A-253 v5: a 5-minute burst at 09:28 is remembered; at 09:31 the close is above the EMA and above the open: a call. */
    @Test
    void v5EntersOnABurstRememberedFromEarlierMinutes() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v5.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            Snapshots snap = snap(22690, 22680, 0.0, 0.0).set("options.atmBandShare5m", m == 568 ? 0.10 : 0.0)
                    .set("structure.dayLow", 22670.0);
            assertThat(s.decide(snap.at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT).orders()).isEmpty();
        }
        assertThat(s.decide(snap(22720, 22680, 0.0, 0.0).set("options.atmBandShare5m", 0.0).set("structure.dayLow", 22670.0)
                .at("09:31"), PositionView.FLAT)
                .orders()).singleElement().satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.CE));
    }

    /** A-254 v6: a call's stop is the tighter of the structure stop and entry - 2 x ATR3m (ATR 10: 20 points). */
    @Test
    void v6UsesTheTighterOfTheStructureAndTheAtrStop() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v6.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22720 + (m - 556), 22680, 0.0, 0.0).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                    .at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22740, 22680, 0.07, 0.01).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                .at("09:31"), PositionView.FLAT).orders()).hasSize(1);
        PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 95);
        assertThat(s.decide(snap(22721, 22680, 0.0, 0.0).at("09:32"), open).orders()).as("19 points down: kept").isEmpty();
        assertThat(s.decide(snap(22719, 22680, 0.0, 0.0).at("09:33"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("STOP"));
    }

    /** A-255 v8: entry 22,740 (ATR 10): armed at +20, best close 22,800, exit on a close at or below 22,770. */
    @Test
    void v8TrailsThreeAtrBelowTheBestCloseOnceUpTwoAtr() {
        Strategy s = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v8.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22720 + (m - 556), 22680, 0.0, 0.0).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                    .at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22740, 22680, 0.07, 0.01).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                .at("09:31"), PositionView.FLAT).orders()).hasSize(1);
        PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 130);
        assertThat(s.decide(snap(22800, 22680, 0.0, 0.0).at("09:40"), open).orders()).isEmpty();
        assertThat(s.decide(snap(22771, 22680, 0.0, 0.0).at("09:45"), open).orders()).as("29 below the best").isEmpty();
        assertThat(s.decide(snap(22770, 22680, 0.0, 0.0).at("09:46"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("TRAIL_ATR"));
    }

    /**
     * A-258 oba-v1 (open 22,800): an up burst at 09:31 above the EMA but below the open (a reversal) is skipped, not the end
     * of the day; a new up burst above the open at 10:00 enters; after that trade the same burst still on does not
     * re-enter; a new burst does; three entries a day at most. v8, on the same minutes, stops at the 09:31 reversal.
     */
    @Test
    void allDayEntersAgainOnlyOnANewBurstAndSkipsReversals() {
        for (String file : new String[] {"oi-burst-allday.v1.yaml", "oi-burst-cross.v8.yaml"}) {
            Strategy s = Strategies.load(Path.of("..", "config", "strategy", file)).create("NIFTY", Snapshots.SESSION);
            PositionView call = new PositionView(true, OptionSide.CE, 1, 100, null, 110);
            java.util.List<String> entries = new java.util.ArrayList<>();
            for (int m = 556; m <= 610; m++) {
                String t = String.format("%02d:%02d", m / 60, m % 60);
                double spot = m <= 571 ? 22710 + (m - 556) : 22730 + (m - 572) * 3.0;
                double s3 = switch (t) {
                    case "09:31", "10:00", "10:01", "10:02", "10:04", "10:07", "10:10" -> 0.2;
                    default -> 0.0;
                };
                boolean held = t.equals("10:01") || t.equals("10:05") || t.equals("10:08");
                Snapshots snap = snap(spot, 22800, s3, Math.signum(s3) * 0.01).set("structure.dayLow", 22600.0)
                        .set("structure.dayHigh", 22900.0).set("structure.atr3m", 10.0);
                if (!s.decide(snap.at(t), held ? call : PositionView.FLAT).orders().isEmpty() && !held) {
                    entries.add(t);
                }
            }
            assertThat(entries).as(file).isEqualTo(file.contains("allday") ? java.util.List.of("10:00", "10:04", "10:07")
                    : java.util.List.of());
        }
    }

    /**
     * A-266: with the previous close 22,700, an open at 22,800 is a +0.44 % gap: obc-v13 takes no call (with the gap) but
     * still takes a put (against it) on another day; an open at 22,750 (+0.22 %) is under 0.4 % and the call is taken.
     */
    @Test
    void aBigOpeningGapBlocksEntriesInItsDirectionOnly() {
        java.util.function.BiFunction<Double, Double, Snapshots> at = (spot, s3) -> snap(spot, 22800, s3, Math.signum(s3) * 0.01)
                .set("structure.dayLow", 22600.0).set("structure.dayHigh", 22950.0).set("structure.atr3m", 10.0);
        Strategy up = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v13.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            up.decide(at.apply(22790.0 + (m - 556), 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(up.decide(at.apply(22830.0, 0.2).at("09:31"), PositionView.FLAT).orders()).as("call with a +0.44 % gap").isEmpty();

        Strategy down = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v13.yaml")).create("NIFTY", Snapshots.SESSION);
        for (int m = 556; m <= 570; m++) {
            down.decide(at.apply(22810.0 - (m - 556), 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(down.decide(at.apply(22770.0, -0.2).at("09:31"), PositionView.FLAT).orders()).as("put against the gap").hasSize(1);

        Strategy small = Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v13.yaml")).create("NIFTY", Snapshots.SESSION);
        java.util.function.BiFunction<Double, Double, Snapshots> at2 = (spot, s3) -> snap(spot, 22750, s3, Math.signum(s3) * 0.01)
                .set("structure.dayLow", 22600.0).set("structure.dayHigh", 22950.0).set("structure.atr3m", 10.0);
        for (int m = 556; m <= 570; m++) {
            small.decide(at2.apply(22740.0 + (m - 556), 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(small.decide(at2.apply(22780.0, 0.2).at("09:31"), PositionView.FLAT).orders()).as("+0.22 % gap").hasSize(1);
    }

    @Test
    void theFileLoadsForBothIndices() {
        assertThat(Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v1.yaml")).create("SENSEX",
                Snapshots.SESSION)).isNotNull();
        assertThat(Strategies.load(Path.of("..", "config", "strategy", "oi-burst-cross.v11.yaml")).create("SENSEX",
                Snapshots.SESSION)).as("A-260 obc-v11").isNotNull();
        assertThat(Map.of()).isEmpty();
    }

    /** A-583: max_ema_dist_atr skips a burst whose close is too far beyond obc's EMA (in ATR3m), keeps one inside the limit. */
    @Test
    void anEntryTooFarBeyondTheEmaIsSkipped(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        // the 09:31 close 22,720 sits about 26 points above the EMA (seeded 22,700, then 15 minutes at 22,690)
        java.util.function.BiFunction<Strategy, Double, java.util.List<com.autotrade.strategy.OrderIntent>> run = (s, atr) -> {
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22690, 22680, 0.0, 0.0).set("structure.atr3m", atr).at(String.format("%02d:%02d", m / 60, m % 60)),
                        PositionView.FLAT);
            }
            return s.decide(snap(22720, 22680, 0.07, 0.01).set("structure.atr3m", atr).at("09:31"), PositionView.FLAT).orders();
        };
        assertThat(run.apply(withLines(dir, "setup", "  max_ema_dist_atr: 1.5\n"), 10.0)).as("~2.6 ATR beyond: skipped").isEmpty();
        assertThat(run.apply(withLines(dir, "setup", "  max_ema_dist_atr: 1.5\n"), 40.0)).as("~0.7 ATR beyond: entered").hasSize(1);
        assertThat(run.apply(load(), 10.0)).as("off by default").hasSize(1);
    }

    /** A-584 Combo 1: a per-index VWAP-extension cap and a put veto on fresh futures longs; both off by default. */
    @Test
    void combo1VetoesAStretchedBurstAndAPutIntoFreshFuturesLongs(@org.junit.jupiter.api.io.TempDir Path dir)
            throws java.io.IOException {
        java.util.function.BiFunction<Strategy, Double, java.util.List<com.autotrade.strategy.OrderIntent>> up = (s, vwapAtr) -> {
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            return s.decide(snap(22720, 22680, 0.07, 0.01).set("structure.distVwapAtr", vwapAtr).at("09:31"),
                    PositionView.FLAT).orders();
        };
        assertThat(up.apply(withLines(dir, "setup", "  max_vwap_ext_atr_nifty: 2.0\n"), 2.5)).as("2.5 ATR above VWAP").isEmpty();
        assertThat(up.apply(withLines(dir, "setup", "  max_vwap_ext_atr_nifty: 2.0\n"), 1.5)).hasSize(1);
        assertThat(up.apply(withLines(dir, "setup", "  max_vwap_ext_atr_sensex: 2.0\n"), 2.5)).as("the cap is per index").hasSize(1);
        assertThat(up.apply(load(), 2.5)).as("off by default").hasSize(1);
        // a down cross with a call-writing burst below the open buys the put, unless futures show fresh longs
        java.util.function.BiFunction<Strategy, String, java.util.List<com.autotrade.strategy.OrderIntent>> down = (s, state) -> {
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22710, 22720, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            return s.decide(snap(22680, 22720, -0.07, -0.01).set("futures.oiState", state).at("09:31"),
                    PositionView.FLAT).orders();
        };
        assertThat(down.apply(withLines(dir, "setup", "  veto_pe_fresh_long: true\n"), "FRESH_LONG")).isEmpty();
        assertThat(down.apply(withLines(dir, "setup", "  veto_pe_fresh_long: true\n"), "LONG_UNWIND")).hasSize(1);
        assertThat(down.apply(load(), "FRESH_LONG")).as("off by default").hasSize(1);
    }

    /** A-586: scope.expiry_day_only enters only when the index expires that day (dteTradingDays 0). */
    @Test
    void expiryDayOnlyTradesOnlyOnTheExpiryDay(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.BiFunction<Strategy, Integer, java.util.List<com.autotrade.strategy.OrderIntent>> run = (s, dte) -> {
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22690, 22680, 0.0, 0.0).set("regime.dteTradingDays", dte).at(String.format("%02d:%02d", m / 60, m % 60)),
                        PositionView.FLAT);
            }
            return s.decide(snap(22720, 22680, 0.07, 0.01).set("regime.dteTradingDays", dte).at("09:31"), PositionView.FLAT).orders();
        };
        assertThat(run.apply(withLines(dir, "scope", "  expiry_day_only: true\n"), 2)).as("not the expiry day").isEmpty();
        assertThat(run.apply(withLines(dir, "scope", "  expiry_day_only: true\n"), 0)).hasSize(1);
        assertThat(run.apply(load(), 2)).as("off by default").hasSize(1);
    }

    /** A-587: burst_mode print takes a 1-minute share at the threshold from 09:18; ema_seed open seeds the EMA at the day open. */
    @Test
    void perPrintBurstAndOpenSeededEma(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        String lines = "  burst_mode: print\n  print_share_min_nifty: 0.0612\n  print_share_min_sensex: 0.0816\n";
        // 09:16-09:30 below the EMA (seeded 22,700), then 09:31 above it with only a 1-minute burst (3-minute share small)
        java.util.function.Function<Strategy, java.util.List<com.autotrade.strategy.OrderIntent>> run = s -> {
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            return s.decide(snap(22720, 22680, 0.02, 0.07).at("09:31"), PositionView.FLAT).orders();
        };
        assertThat(run.apply(load())).as("window mode: the 3-minute share 0.02 is no burst").isEmpty();
        assertThat(run.apply(withLines(dir, "setup", lines))).as("print mode: the 1-minute share 0.07 is").hasSize(1);
        // a gap-up open 22,800 above the previous close 22,700: at 09:18 price 22,760 is below the open-seeded EMA, a put burst
        java.util.function.Function<Strategy, java.util.List<com.autotrade.strategy.OrderIntent>> gap = s -> {
            s.decide(snap(22800, 22800, 0.0, 0.0).at("09:16"), PositionView.FLAT);
            s.decide(snap(22790, 22800, 0.0, 0.0).at("09:17"), PositionView.FLAT);
            return s.decide(snap(22760, 22800, -0.01, -0.09).at("09:18"), PositionView.FLAT).orders();
        };
        java.util.function.BiFunction<String, String, Strategy> from0918 = (name, setup) -> {
            try {
                String v1 = java.nio.file.Files.readString(Path.of("..", "config", "strategy", "oi-burst-cross.v1.yaml"));
                assertThat(v1).contains("entry_from: \"09:21\"");
                Path f = dir.resolve(name).resolve("oi-burst-cross.v1.yaml");
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.writeString(f, v1.replace("entry_from: \"09:21\"", "entry_from: \"09:18\"")
                        .replace("\nsetup:\n", "\nsetup:\n" + setup));
                return Strategies.load(f).create("NIFTY", Snapshots.SESSION);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        String v31like = lines + "  fresh_cross: false\n";   // as v31: the side of the EMA, no fresh cross needed
        assertThat(gap.apply(from0918.apply("open", v31like + "  ema_seed: open\n"))).as("EMA from the open: the 09:18 put burst")
                .singleElement().satisfies(o -> assertThat(o.side()).isEqualTo(OptionSide.PE));
        assertThat(gap.apply(from0918.apply("prev", v31like))).as("EMA from the previous close: price still above it").isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withLines(dir, "setup", "  burst_mode: tick\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A-595: burst_mode relative: s1 or s3 against relative_k x the median of today's earlier non-zero minutes; no fixed share. */
    @Test
    void relativeBurstIsMeasuredAgainstTheDaysOwnFlow(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        String lines = "  burst_mode: relative\n  relative_k: 3.0\n  relative_min_samples: 3\n";
        // 09:16-09:30 below the EMA with small flows (|s1| = |s3| = 0.01, zero minutes ignored), then 09:31 above it
        java.util.function.BiFunction<Strategy, double[], java.util.List<com.autotrade.strategy.OrderIntent>> run = (s, last) -> {
            for (int m = 556; m <= 570; m++) {
                double f = m % 2 == 0 ? 0.0 : -0.01;
                s.decide(snap(22690, 22680, f, f).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            return s.decide(snap(22720, 22680, last[0], last[1]).at("09:31"), PositionView.FLAT).orders();
        };
        assertThat(run.apply(withLines(dir, "setup", lines), new double[] {0.02, 0.035})).as("s1 0.035 >= 3 x 0.01").hasSize(1);
        assertThat(run.apply(withLines(dir, "setup", lines), new double[] {0.02, 0.025})).as("neither reaches 3 x 0.01").isEmpty();
        assertThat(run.apply(withLines(dir, "setup", lines), new double[] {0.035, 0.005})).as("s3 0.035 with s1 on its side").hasSize(1);
        assertThat(run.apply(load(), new double[] {0.02, 0.035})).as("window mode: fixed 0.0612 not reached").isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withLines(dir, "setup", "  burst_mode: relative\n  relative_k: 0\n  relative_min_samples: 3\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A-596: setup.premium_rising_veto needs the ATM option of the side rising (1- and 3-minute mid change > 0). */
    @Test
    void premiumRisingVetoNeedsTheOptionAlreadyRising(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        String lines = "  premium_rising_veto: true\n";
        java.util.function.BiFunction<Double, Double, java.util.List<com.autotrade.strategy.OrderIntent>> run = (ch1, ch3) -> {
            try {
                Strategy s = withLines(dir, "setup", lines);
                for (int m = 556; m <= 570; m++) {
                    s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                return s.decide(snap(22720, 22680, 0.07, 0.01).set("premium.ceChange1mPct", ch1).set("premium.ceChange3mPct", ch3)
                        .at("09:31"), PositionView.FLAT).orders();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertThat(run.apply(-1.0, 5.0)).as("1-minute falling").isEmpty();
        assertThat(run.apply(2.0, -1.0)).as("3-minute falling").isEmpty();
        assertThat(run.apply(2.0, Double.NaN)).as("3-minute not yet known").hasSize(1);
        assertThat(run.apply(2.0, 5.0)).hasSize(1);
        assertThat(run.apply(Double.NaN, Double.NaN)).as("no premium data").isEmpty();
        assertThat(cross(load(), 22680, 0.07, 0.01)).as("off by default").hasSize(1);
    }

    /** A-596: exits.level_mode: an unbroken level stalls the trade out; a broken one is held until a close back below it. */
    @Test
    void levelToLevelExitStallsAtAnUnbrokenLevelAndHoldsABrokenOne(@org.junit.jupiter.api.io.TempDir Path dir)
            throws java.io.IOException {
        String lines = "  level_mode: true\n  level_touch_atr: 1.0\n  level_break_pct: 0.05\n  level_stall_min: 5\n";
        java.util.function.Function<Double, Snapshots> at = close -> snap(close, 22680, 0.0, 0.0).set("structure.pdh", 22760.0)
                .set("structure.atr3m", 10.0).set("structure.lastMinuteClose", close);
        PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 110);
        Strategy a = withLines(dir, "exits", lines);
        for (int m = 556; m <= 570; m++) {
            a.decide(at.apply(22690.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(a.decide(snap(22720, 22680, 0.07, 0.01).set("structure.pdh", 22760.0).set("structure.atr3m", 10.0)
                .at("09:31"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(a.decide(at.apply(22755.0).at("09:32"), open).orders()).as("touch: within 1 ATR of the PDH").isEmpty();
        for (int m = 573; m <= 576; m++) {
            assertThat(a.decide(at.apply(22757.0).at(String.format("%02d:%02d", m / 60, m % 60)), open).orders()).isEmpty();
        }
        assertThat(a.decide(at.apply(22757.0).at("09:37"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("LEVEL_STALL"));

        Strategy b = withLines(dir, "exits", lines);
        for (int m = 556; m <= 570; m++) {
            b.decide(at.apply(22690.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(b.decide(snap(22720, 22680, 0.07, 0.01).set("structure.pdh", 22760.0).set("structure.atr3m", 10.0)
                .at("09:31"), PositionView.FLAT).orders()).hasSize(1);
        assertThat(b.decide(at.apply(22755.0).at("09:32"), open).orders()).isEmpty();
        assertThat(b.decide(at.apply(22775.0).at("09:33"), open).orders()).as("broken by more than 0.05 %").isEmpty();
        for (int m = 574; m <= 580; m++) {
            assertThat(b.decide(at.apply(22770.0).at(String.format("%02d:%02d", m / 60, m % 60)), open).orders())
                    .as("above the broken PDH, nothing ahead: held").isEmpty();
        }
        assertThat(b.decide(at.apply(22745.0).at("09:41"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("LEVEL_LOST"));
    }

    /** A-597: exits.oi_trend_exit holds while the day's OI build-up widens on the trade's side, exits 20 % off its peak. */
    @Test
    void oiTrendExitHoldsWhileTheBuildUpWidensAndExitsOnItsTurn(@org.junit.jupiter.api.io.TempDir Path dir)
            throws java.io.IOException {
        Strategy s = withLines(dir, "exits", "  oi_trend_exit: true\n  oi_trend_retrace: 0.2\n");
        for (int m = 556; m <= 570; m++) {
            s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
        }
        assertThat(s.decide(snap(22720, 22680, 0.07, 0.01).at("09:31"), PositionView.FLAT).orders()).hasSize(1);
        PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 110);
        // build-up so far 0.01; +0.02 a minute to 0.11 by 09:36 (peak), then a one-minute -0.015 (0.095 > 0.088): held
        for (int m = 572; m <= 576; m++) {
            assertThat(s.decide(snap(22725, 22680, 0.0, 0.02).at(String.format("%02d:%02d", m / 60, m % 60)), open).orders())
                    .isEmpty();
        }
        assertThat(s.decide(snap(22725, 22680, 0.0, -0.015).at("09:37"), open).orders()).as("a 14 % dip is held").isEmpty();
        assertThat(s.decide(snap(22725, 22680, 0.0, -0.01).at("09:38"), open).orders()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("OI_TURN"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withLines(dir, "exits", "  oi_trend_exit: true\n  oi_trend_retrace: 1.5\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A-603: after a STOP exit, one same-side re-entry on a minute close beyond the day's extreme at the stop; off by default. */
    @Test
    void reentryAfterAStopOnANewDayExtreme(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.Function<Strategy, java.util.List<String>> run = s -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22690, 22680, 0.0, 0.0).set("structure.dayHigh", 22700.0).at(String.format("%02d:%02d", m / 60, m % 60)),
                        PositionView.FLAT);
            }
            s.decide(snap(22720, 22680, 0.07, 0.01).set("structure.dayHigh", 22730.0).at("09:31"), PositionView.FLAT).orders()
                    .forEach(o -> out.add("09:31 " + o.reason()));
            PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 90);
            s.decide(snap(22680, 22680, 0.0, 0.0).set("structure.dayHigh", 22730.0).at("09:33"), open).orders()
                    .forEach(o -> out.add("09:33 " + o.reason()));
            for (String[] m : new String[][] {{"09:34", "22700"}, {"09:35", "22725"}, {"09:36", "22740"}, {"09:37", "22745"}}) {
                double px = Double.parseDouble(m[1]);
                s.decide(snap(px, 22680, 0.0, 0.0).set("structure.dayHigh", 22730.0).set("structure.lastMinuteClose", px).at(m[0]),
                        PositionView.FLAT).orders().forEach(o -> out.add(m[0] + " " + o.side() + " " + o.reason()));
            }
            return out;
        };
        assertThat(run.apply(withLines(dir, "setup", "  reentry_after_stop_new_extreme: true\n")))
                .contains("09:33 STOP").anySatisfy(x -> assertThat(x).startsWith("09:36 CE"))
                .noneSatisfy(x -> assertThat(x).startsWith("09:35")).noneSatisfy(x -> assertThat(x).startsWith("09:37"));
        assertThat(run.apply(load())).as("off by default").contains("09:33 STOP")
                .noneSatisfy(x -> assertThat(x).startsWith("09:36"));
    }

    /** A-616: setup.refused_entry_refund: an entry the engine refused does not use up the day; a new burst may enter. */
    @Test
    void aRefusedEntryIsRefundedAndANewBurstMayEnter(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.Function<Strategy, java.util.List<String>> run = s -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            s.decide(snap(22720, 22680, 0.07, 0.01).at("09:31"), PositionView.FLAT).orders().forEach(o -> out.add("09:31"));
            // the engine refused it: the position never opens
            s.decide(snap(22722, 22680, 0.0, 0.0).at("09:32"), PositionView.FLAT).orders().forEach(o -> out.add("09:32"));
            s.decide(snap(22724, 22680, 0.07, 0.01).at("09:33"), PositionView.FLAT).orders().forEach(o -> out.add("09:33"));
            return out;
        };
        assertThat(run.apply(withLines(dir, "setup", "  refused_entry_refund: true\n"))).containsExactly("09:31", "09:33");
        assertThat(run.apply(load())).as("off: the refused 09:31 entry was the day's one event").containsExactly("09:31");
    }

    /** A-618: after a STOP, the opposite side re-enters on a close beyond the day's opposite extreme. */
    @Test
    void reverseReentryAfterAStopOnTheOppositeExtreme(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.Function<Strategy, java.util.List<String>> run = s -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            java.util.function.Function<Double, Snapshots> at = px -> snap(px, 22680, 0.0, 0.0).set("structure.dayHigh", 22730.0)
                    .set("structure.dayLow", 22670.0).set("structure.lastMinuteClose", px);
            for (int m = 556; m <= 570; m++) {
                s.decide(at.apply(22690.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            s.decide(snap(22720, 22680, 0.07, 0.01).set("structure.dayHigh", 22730.0).set("structure.dayLow", 22670.0)
                    .at("09:31"), PositionView.FLAT).orders().forEach(o -> out.add("09:31 " + o.side()));
            PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 90);
            s.decide(at.apply(22680.0).at("09:33"), open).orders().forEach(o -> out.add("09:33 " + o.reason()));
            for (String[] m : new String[][] {{"09:34", "22675"}, {"09:35", "22668"}, {"09:36", "22660"}}) {
                s.decide(at.apply(Double.parseDouble(m[1])).at(m[0]), PositionView.FLAT).orders()
                        .forEach(o -> out.add(m[0] + " " + o.side()));
            }
            return out;
        };
        assertThat(run.apply(withLines(dir, "setup", "  reentry_reverse_after_stop: true\n")))
                .containsExactly("09:31 CE", "09:33 STOP", "09:35 PE");
        assertThat(run.apply(load())).as("off by default").containsExactly("09:31 CE", "09:33 STOP");
    }

    /** A-621: setup.entry_share_scale lowers the entry burst threshold only. */
    @Test
    void entryShareScaleEntersOnAHalfStrengthBurst(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        assertThat(cross(load(), 22680, 0.04, 0.01)).as("0.04 < 0.0612").isEmpty();
        assertThat(cross(withLines(dir, "setup", "  entry_share_scale: 0.5\n"), 22680, 0.04, 0.01)).as("0.04 >= 0.0306").hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withLines(dir, "setup", "  entry_share_scale: 0\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A-622: setup.stale_burst_max_min skips a burst whose half-strength flow began long before it qualified. */
    @Test
    void aStaleBurstIsSkipped(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.BiFunction<Strategy, Double, java.util.List<com.autotrade.strategy.OrderIntent>> run = (s, early) -> {
            for (int m = 556; m <= 570; m++) {
                double flow = m >= 560 ? early : 0.0;             // from 09:20 a half-strength up flow (or none)
                s.decide(snap(22690 - (m == 556 ? 5 : 0), 22680, flow, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)),
                        PositionView.FLAT);
            }
            return s.decide(snap(22720, 22680, 0.07, 0.01).at("09:31"), PositionView.FLAT).orders();
        };
        String lines = "  stale_burst_max_min: 5\n";
        assertThat(run.apply(withLines(dir, "setup", lines), 0.035)).as("flow half-strength since 09:20: 11 minutes").isEmpty();
        assertThat(run.apply(withLines(dir, "setup", lines), 0.0)).as("a fresh burst").hasSize(1);
        assertThat(run.apply(load(), 0.035)).as("off by default").hasSize(1);
    }

    /** A-630: a second entry needs a gap after the first trade and strong data (full burst, futures volume, premium rising). */
    @Test
    void aSecondEntryNeedsAGapAndStrongData(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.BiFunction<String, double[], java.util.List<String>> run = (windows, flows) -> {
            try {
                Strategy s = withLines(dir, "setup", "  fresh_cross: false\n  max_entries_per_day: 2\n  second_entry_windows: \"" + windows + "\"\n"
                        + "  second_entry_min_gap_min: 5\n  second_entry_min_fut_rvol: 1.0\n");
                java.util.List<String> out = new java.util.ArrayList<>();
                java.util.function.BiFunction<Double, Double, Snapshots> at = (px, f) -> snap(px, 22680, f, 0.01)
                        .set("futures.rvolTod", 1.2).set("premium.ceChange1mPct", 1.0).set("premium.ceChange3mPct", 2.0);
                for (int m = 556; m <= 570; m++) {
                    s.decide(at.apply(22690.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                s.decide(at.apply(22720.0, 0.07).at("09:31"), PositionView.FLAT).orders().forEach(o -> out.add("09:31"));
                PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 90);
                s.decide(at.apply(22721.0, 0.0).at("09:32"), open);
                s.decide(at.apply(22722.0, 0.0).at("09:33"), PositionView.FLAT);          // the first trade ended at 09:33
                s.decide(at.apply(22725.0, flows[0]).at("09:35"), PositionView.FLAT).orders().forEach(o -> out.add("09:35"));
                s.decide(at.apply(22726.0, 0.0).at("09:36"), PositionView.FLAT);
                s.decide(at.apply(22730.0, flows[1]).at("09:40"), PositionView.FLAT).orders().forEach(o -> out.add("09:40"));
                return out;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertThat(run.apply("09:21-11:01", new double[] {0.07, 0.07})).as("09:35 inside the 5-min gap; 09:40 full burst")
                .containsExactly("09:31", "09:40");
        assertThat(run.apply("09:21-11:01", new double[] {0.0, 0.04})).as("a half-strength burst is not enough for a 2nd entry")
                .containsExactly("09:31");
        assertThat(run.apply("09:21-09:38", new double[] {0.0, 0.07})).as("outside the windows: needs 1.5 x").containsExactly("09:31");
        assertThat(run.apply("09:21-09:38", new double[] {0.0, 0.10})).as("excellent: 0.10 >= 0.0918").containsExactly("09:31", "09:40");
    }

    /** A-636: v8's trail (arm +2 ATR, give back 3) with a 1.5 ATR give-back from +2.5 ATR, or a 20-minute stall exit. */
    @Test
    void tightTrailAndStallExit(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.Function<String, String> run = lines -> {
            try {
                String v8 = java.nio.file.Files.readString(Path.of("..", "config", "strategy", "oi-burst-cross.v8.yaml"));
                Path f = dir.resolve(Integer.toHexString(lines.hashCode())).resolve("oi-burst-cross.v8.yaml");
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.writeString(f, v8.replace("\nexits:\n", "\nexits:\n" + lines));
                Strategy s = Strategies.load(f).create("NIFTY", Snapshots.SESSION);
                for (int m = 556; m <= 570; m++) {
                    s.decide(snap(22720 + (m - 556), 22680, 0.0, 0.0).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                            .at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                assertThat(s.decide(snap(22740, 22680, 0.07, 0.01).set("structure.dayLow", 22600.0).set("structure.atr3m", 10.0)
                        .at("09:31"), PositionView.FLAT).orders()).hasSize(1);
                PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 130);
                // 09:32-09:40 up to 22,770 (+3 ATR), then flat 22,760 to 10:10
                double[] path = new double[40];
                for (int i = 0; i < 40; i++) {
                    path[i] = i < 9 ? 22740 + 30.0 * (i + 1) / 9 : 22760;
                }
                for (int i = 0; i < 40; i++) {
                    int m = 572 + i;
                    var o = s.decide(snap(path[i], 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), open).orders();
                    if (!o.isEmpty()) {
                        return String.format("%02d:%02d ", m / 60, m % 60) + o.get(0).reason();
                    }
                }
                return "held";
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertThat(run.apply("")).as("off: 10 below the best is inside 3 ATR").isEqualTo("held");
        assertThat(run.apply("  trail_tight_after_atr: 2.5\n  trail_tight_atr: 1.0\n")).as("+3 ATR best, 1 ATR give-back")
                .isEqualTo("09:41 TRAIL_ATR");
        assertThat(run.apply("  stall_exit_min: 20\n")).as("best at 09:40, no new best by 10:00").isEqualTo("10:00 STALL");
    }

    /** A-635: a further entry needs the day's and the 1/3/5/10-minute OI flow with it and volume and / or the EMA slope. */
    @Test
    void aFurtherEntryNeedsExcellentOiAndVolumeOrEma(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        record Arm(String confirm, double rvol, double net10) { }
        java.util.function.Function<Arm, java.util.List<String>> run = arm -> {
            try {
                Strategy s = withLines(dir, "setup", "  fresh_cross: false\n  max_entries_per_day: 3\n  second_entry_windows: \"09:21-14:51\"\n"
                        + "  second_entry_min_gap_min: 5\n  second_entry_strong_scale: 0.5\n  second_entry_excellent_scale: 0.5\n"
                        + "  second_entry_require_rising: false\n  second_entry_min_fut_rvol: 1.0\n  second_entry_excellent_oi: true\n"
                        + "  second_entry_confirm: " + arm.confirm() + "\n");
                java.util.List<String> out = new java.util.ArrayList<>();
                java.util.function.BiFunction<Double, Double, Snapshots> at = (px, f) -> snap(px, 22680, f, f > 0 ? 0.01 : 0.0)
                        .set("futures.rvolTod", arm.rvol()).set("options.peOiChange1m", 10.0).set("options.peOiChange3m", 30.0)
                        .set("options.peOiChange5m", 50.0).set("options.peOiChange10m", 100.0 + arm.net10())
                        .set("options.ceOiChange1m", 0.0).set("options.ceOiChange3m", 0.0).set("options.ceOiChange5m", 0.0).set("options.ceOiChange10m", 0.0);
                for (int m = 556; m <= 570; m++) {
                    s.decide(at.apply(22690.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                s.decide(at.apply(22720.0, 0.07).at("09:31"), PositionView.FLAT).orders().forEach(o -> out.add("09:31"));
                s.decide(at.apply(22721.0, 0.0).at("09:32"), new PositionView(true, OptionSide.CE, 1, 100, null, 110));
                s.decide(at.apply(22722.0, 0.0).at("09:33"), PositionView.FLAT);
                for (int m = 574; m <= 579; m++) {                // the EMA keeps rising
                    s.decide(at.apply(22722.0 + (m - 573), 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                s.decide(at.apply(22735.0, 0.07).at("09:40"), PositionView.FLAT).orders().forEach(o -> out.add("09:40"));
                return out;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertThat(run.apply(new Arm("and", 1.2, 0))).as("all OI windows with it, volume and EMA").containsExactly("09:31", "09:40");
        assertThat(run.apply(new Arm("and", 1.2, -150))).as("10-minute flow against").containsExactly("09:31");
        assertThat(run.apply(new Arm("and", 0.8, 0))).as("no volume: AND fails").containsExactly("09:31");
        assertThat(run.apply(new Arm("or", 0.8, 0))).as("no volume but the EMA rises: OR passes").containsExactly("09:31", "09:40");
    }

    /** A-635: a further trade is held while its structure holds and exits on a close below the previous 10 closes. */
    @Test
    void aFurtherTradeIsHeldByStructure(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.Function<Boolean, String> run = hold -> {
            try {
                String v1 = java.nio.file.Files.readString(Path.of("..", "config", "strategy", "oi-burst-cross.v1.yaml"));
                Path f = dir.resolve("h" + hold).resolve("oi-burst-cross.v1.yaml");
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.writeString(f, v1.replace("\nsetup:\n", "\nsetup:\n  fresh_cross: false\n  max_entries_per_day: 3\n")
                        .replace("\nexits:\n", "\nexits:\n  hold_structure_further: " + hold + "\n  trail_arm_atr: 2.0\n  trail_atr: 1.0\n"));
                Strategy s = Strategies.load(f).create("NIFTY", Snapshots.SESSION);
                java.util.function.Function<Double, Snapshots> at = px -> snap(px, 22680, 0.0, 0.0).set("structure.atr3m", 10.0);
                for (int m = 556; m <= 570; m++) {
                    s.decide(at.apply(22690.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                assertThat(s.decide(snap(22720, 22680, 0.07, 0.01).set("structure.atr3m", 10.0).at("09:31"), PositionView.FLAT).orders()).hasSize(1);
                s.decide(at.apply(22721.0).at("09:32"), new PositionView(true, OptionSide.CE, 1, 100, null, 110));
                s.decide(at.apply(22722.0).at("09:33"), PositionView.FLAT);
                assertThat(s.decide(snap(22735, 22680, 0.07, 0.01).set("structure.atr3m", 10.0).at("09:40"), PositionView.FLAT).orders())
                        .as("the second entry").hasSize(1);
                PositionView open = new PositionView(true, OptionSide.CE, 1, 100, null, 120);
                for (int m = 581; m <= 595; m++) {                // 09:41-09:55: up 5 a minute to 22,805
                    var o = s.decide(at.apply(22735.0 + 5 * (m - 580)).at(String.format("%02d:%02d", m / 60, m % 60)), open).orders();
                    if (!o.isEmpty()) {
                        return String.format("%02d:%02d ", m / 60, m % 60) + o.get(0).reason();
                    }
                }
                for (double px : new double[] {22775, 22755}) {   // 09:56 back 30 (3 ATR off the best), 09:57 below the 10-minute low
                    var o = s.decide(at.apply(px).at(px == 22775 ? "09:56" : "09:57"), open).orders();
                    if (!o.isEmpty()) {
                        return (px == 22775 ? "09:56 " : "09:57 ") + o.get(0).reason();
                    }
                }
                return "held";
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertThat(run.apply(true)).as("held through the trail; out when the structure breaks").isEqualTo("09:57 STRUCTURE_BREAK");
        assertThat(run.apply(false)).as("off: the trail takes it").isEqualTo("09:56 TRAIL_ATR");
    }

    /** A-634: no_side_flip refuses the other side after a filled trade; max_losers_per_day stops the index after N losers. */
    @Test
    void noSideFlipAndMaxLosersPerDay(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.BiFunction<String, Double, java.util.List<String>> run = (lines, secondPx) -> {
            try {
                Strategy s = withLines(dir, "setup", "  fresh_cross: false\n  max_entries_per_day: 3\n  reversal_ends_day: false\n" + lines);
                java.util.List<String> out = new java.util.ArrayList<>();
                for (int m = 556; m <= 570; m++) {
                    s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                s.decide(snap(22720, 22680, 0.07, 0.01).at("09:31"), PositionView.FLAT).orders().forEach(o -> out.add("09:31 " + o.side()));
                s.decide(snap(22721, 22680, 0.0, 0.0).at("09:32"), new PositionView(true, OptionSide.CE, 1, 100, null, 80, 0, 1, java.util.List.of()));
                s.decide(snap(22722, 22680, 0.0, 0.0).at("09:33"), PositionView.FLAT);   // closed below cost: a loser
                // the index drops under its EMA: a put burst (other side, below the open would be non-continuation, so keep it above)
                for (int m = 574; m <= 580; m++) {
                    s.decide(snap(22700, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
                }
                s.decide(snap(secondPx, 22680, -0.07, -0.01).at("09:41"), PositionView.FLAT).orders().forEach(o -> out.add("09:41 " + o.side()));
                return out;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertThat(run.apply("", 22660.0)).as("off: the put is taken").containsExactly("09:31 CE", "09:41 PE");
        assertThat(run.apply("  no_side_flip: true\n", 22660.0)).as("no flip after a filled call").containsExactly("09:31 CE");
        assertThat(run.apply("  max_losers_per_day: 1\n", 22660.0)).as("one loser and the index is done").containsExactly("09:31 CE");
    }

    /** A-591: setup.min_fut_rvol skips a burst on below-normal futures volume; a missing value passes; off by default. */
    @Test
    void lowFuturesRvolVetoesTheBurst(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        java.util.function.BiFunction<Strategy, Double, java.util.List<com.autotrade.strategy.OrderIntent>> run = (s, rvol) -> {
            for (int m = 556; m <= 570; m++) {
                s.decide(snap(22690, 22680, 0.0, 0.0).at(String.format("%02d:%02d", m / 60, m % 60)), PositionView.FLAT);
            }
            Snapshots last = snap(22720, 22680, 0.07, 0.01);
            if (rvol != null) {
                last = last.set("futures.rvolTod", rvol);
            }
            return s.decide(last.at("09:31"), PositionView.FLAT).orders();
        };
        String on = "  min_fut_rvol: 1.0\n";
        assertThat(run.apply(withLines(dir, "setup", on), 0.8)).as("RVOL 0.8").isEmpty();
        assertThat(run.apply(withLines(dir, "setup", on), 1.2)).as("RVOL 1.2").hasSize(1);
        assertThat(run.apply(withLines(dir, "setup", on), null)).as("missing RVOL passes").hasSize(1);
        assertThat(run.apply(withLines(dir, "setup", on + "  fut_rvol_source: nifty\n"), 0.8))
                .as("a NIFTY instance reads its own RVOL in nifty mode").isEmpty();
        assertThat(run.apply(load(), 0.8)).as("off by default").hasSize(1);
    }
}
