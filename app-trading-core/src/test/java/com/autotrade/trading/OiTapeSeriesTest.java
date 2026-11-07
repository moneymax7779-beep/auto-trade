package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The OI tape's per-step arithmetic: carried OI, the band around the money, flow only from strikes seen twice. */
class OiTapeSeriesTest {

    private static OiTapeController.Day day() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19));
        d.steps = 4;
        d.spot[0] = 23_010; d.spot[1] = 23_020; d.spot[3] = 23_030;            // step 2 has no index tick: carried
        d.seedCalls.put(23_000.0, 1_000.0);
        d.seedPuts.put(23_000.0, 2_000.0);
        d.calls.put(23_000.0, steps(1_100, Double.NaN, 1_300, Double.NaN));    // published in steps 0 and 2
        d.puts.put(23_000.0, steps(Double.NaN, 1_900, Double.NaN, Double.NaN));
        d.calls.put(22_950.0, steps(Double.NaN, Double.NaN, 500, 650));        // first recorded in step 2: no flow then
        d.calls.put(23_600.0, steps(10, 20, 30, 40));                          // beyond ATM ± 10 strikes: left out
        return d;
    }

    private static double[] steps(double... v) {
        double[] a = new double[OiTapeController.STEPS];
        java.util.Arrays.fill(a, Double.NaN);
        System.arraycopy(v, 0, a, 0, v.length);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> row(List<Object> series, int i) {
        return (List<Object>) series.get(i);
    }

    @Test
    void flowCountsOnlyStrikesWithOiInBothStepsInsideTheBand() {
        List<Object> s = OiTapeController.series(day(), 50);
        assertThat(s).hasSize(4);
        // [time, spot, futPx, futOi, atm, ce, pe, dce, dpe, pcr]
        assertThat(row(s, 0)).containsExactly("09:15:00", 23_010.0, null, null, 23_000.0, 1_100L, 2_000L, 100L, 0L, 1.818);
        assertThat(row(s, 1).subList(5, 9)).containsExactly(1_100L, 1_900L, 0L, -100L);
        // step 2: the 22,950 call appears (500 added to the band's OI, none to its flow); the 23,000 call +200
        assertThat(row(s, 2).get(1)).isEqualTo(23_020.0);
        assertThat(row(s, 2).subList(5, 9)).containsExactly(1_800L, 1_900L, 200L, 0L);
        // step 3: the 22,950 call +150 counts now
        assertThat(row(s, 3).subList(5, 9)).containsExactly(1_950L, 1_900L, 150L, 0L);
    }

    @Test
    void theBandParameterSetsHowManyStrikesAroundTheMoneyCount() {
        OiTapeController.Day d = day();
        d.calls.put(23_150.0, steps(300, 400, 400, 400));                     // 3 strikes above the 23,000 ATM
        // band 3 counts it (OI 300 at step 0, +100 flow at step 1); band 2 leaves it out
        assertThat(row(OiTapeController.series(d, 50, 3), 1).subList(5, 8)).containsExactly(1_500L, 1_900L, 100L);
        assertThat(row(OiTapeController.series(d, 50, 2), 1).subList(5, 8)).containsExactly(1_100L, 1_900L, 0L);
    }

    @Test
    void stepIndexAndLabel() {
        assertThat(OiTapeController.index(LocalTime.of(9, 15))).isZero();
        assertThat(OiTapeController.index(LocalTime.of(9, 34, 29))).isEqualTo(38);
        assertThat(OiTapeController.time(38)).isEqualTo("09:34:00");
        assertThat(OiTapeController.time(39)).isEqualTo("09:34:30");
        assertThat(OiTapeController.STEPS).isEqualTo(750);
    }

    /** Today is read to whole steps the capture has written (15 s back), never past the close. */
    @org.junit.jupiter.api.Test
    void readsTodayOnlyToStepsTheCaptureHasWritten() {
        java.time.Instant close = java.time.Instant.parse("2026-10-19T10:00:00Z");
        org.assertj.core.api.Assertions.assertThat(OiTapeController.readableUntil(java.time.Instant.parse("2026-10-19T04:00:40Z"), close))
                .isEqualTo(java.time.Instant.parse("2026-10-19T04:00:00Z"));      // 04:00:25 written -> the 04:00:00 step boundary
        org.assertj.core.api.Assertions.assertThat(OiTapeController.readableUntil(java.time.Instant.parse("2026-10-19T04:00:46Z"), close))
                .isEqualTo(java.time.Instant.parse("2026-10-19T04:00:30Z"));
        org.assertj.core.api.Assertions.assertThat(OiTapeController.readableUntil(java.time.Instant.parse("2026-10-19T10:00:20Z"), close))
                .isEqualTo(close);
    }

    @Test
    void maxPainIsTheStrikeWhereTheBuyersPayoutIsSmallestAndIsReportedWhenItMoves() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10));
        d.steps = 3;
        // calls written above, puts below: settling at 24,400 pays least (step 0)
        d.seedCalls.put(24_400.0, 100.0);
        d.seedCalls.put(24_500.0, 1_000.0);
        d.seedPuts.put(24_300.0, 1_000.0);
        d.seedPuts.put(24_400.0, 100.0);
        // step 2: heavy call OI at 24,300 pulls it there (settling higher would pay those calls)
        d.calls.put(24_300.0, steps(10, Double.NaN, 5_000));
        List<Object> s = OiTapeController.maxPainSeries(d);
        // [time, max pain, strikes with OI]: step 0, unchanged step 1 left out, step 2 moved (and the last step)
        assertThat(s).hasSize(2);
        assertThat(row(s, 0)).containsExactly("09:15:00", 24_400.0, 3);
        assertThat(row(s, 1)).containsExactly("09:16:00", 24_300.0, 3);
        assertThat(OiTapeController.maxPainSeries(new OiTapeController.Day(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10))))
                .isEmpty();
    }

    private static float[] fsteps(double... v) {
        float[] a = new float[OiTapeController.STEPS];
        java.util.Arrays.fill(a, Float.NaN);
        for (int i = 0; i < v.length; i++) {
            a[i] = (float) v[i];
        }
        return a;
    }

    @Test
    void whoTradedReadsTheOiChangeAgainstTheIvOrPremiumChange() {
        double iv = OiTapeController.MIN_IV_CHANGE;
        assertThat(OiTapeController.who(1_100, 1_000, 0.140, 0.150, iv)).isEqualTo("WRITING");    // OI up, IV down
        assertThat(OiTapeController.who(1_100, 1_000, 0.160, 0.150, iv)).isEqualTo("BUYING");     // OI up, IV up
        assertThat(OiTapeController.who(900, 1_000, 0.160, 0.150, iv)).isEqualTo("COVERING");     // OI down, IV up
        assertThat(OiTapeController.who(900, 1_000, 0.140, 0.150, iv)).isEqualTo("CLOSING");      // OI down, IV down
        assertThat(OiTapeController.who(1_004, 1_000, 0.140, 0.150, iv)).isNull();                // OI change under 0.5 %
        assertThat(OiTapeController.who(1_100, 1_000, 0.1505, 0.150, iv)).isNull();               // IV change under 0.1 point
        assertThat(OiTapeController.who(1_100, Double.NaN, 0.140, 0.150, iv)).isNull();
        assertThat(OiTapeController.who(1_100, 1_000, Double.NaN, 0.150, iv)).isNull();
    }

    @Test
    void theStrikeLadderCarriesIvPremiumAndWindowVolumeAndLabelsWhoTraded() {
        OiTapeController.Day d = day();
        d.callIv.put(23_000.0, fsteps(0.150, 0.150, 0.140, 0.140));
        d.callPx.put(23_000.0, fsteps(100, 101, 96, 96));
        d.callVol.put(23_000.0, fsteps(5_000, 6_000, 9_000, 9_500));
        java.util.Map<String, Object> l = OiTapeController.ladder(d, 2, 0, 50, t -> true);
        @SuppressWarnings("unchecked") List<List<Object>> rows = (List<List<Object>>) l.get("rows");
        List<Object> r = rows.stream().filter(x -> x.get(0).equals(23_000.0)).findFirst().orElseThrow();
        // call OI 1,100 -> 1,300 while its IV fell 0.150 -> 0.140: writers opening
        assertThat(r.subList(1, 3)).containsExactly(1_300.0, 1_100.0);
        assertThat(r.subList(5, 7)).containsExactly((double) 0.14f, (double) 0.15f);
        assertThat(r.get(13)).isEqualTo(4_000.0);                                          // 9,000 - 5,000 traded
        assertThat(r.get(15)).isEqualTo("WRITING");
        assertThat(r.get(16)).isNull();                                                    // no put IV recorded
        // the calls' gamma × OI from their IV; the puts', with no put IV recorded, from the calls' IV
        double g = OiTapeController.gamma(23_020, 23_000, 0.14f, OiTapeController.yearsToExpiry(d.date, d.expiry, 2));
        assertThat((Double) r.get(17)).isCloseTo(g * 1_300, org.assertj.core.data.Offset.offset(1e-9));
        assertThat((Double) r.get(18)).isCloseTo(g * 1_900, org.assertj.core.data.Offset.offset(1e-9));
        // the 1/5/15/30-minute and 1-hour changes at step 2 (calls 1,300, puts 1,900): 1 min back is step 0 (calls 1,100,
        // puts 2,000); the longer windows reach back to the pre-open values (calls 1,000, puts 2,000); since the open: the
        // first print ran from step 0 into step 1, so from step 1 (calls 1,100, puts 1,900)
        assertThat(r.get(19)).isEqualTo(java.util.Arrays.asList(200.0, 300.0, 300.0, 300.0, 300.0, 200.0,
                -100.0, -100.0, -100.0, -100.0, -100.0, 0.0));
        assertThat(l.get("extras")).isEqualTo(true);
    }

    @Test
    void aWallMovesOnlyWhenAnotherStrikeHoldsFivePercentMoreAndRemembersWhenAndFromWhere() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10));
        d.steps = 4;
        for (int s = 0; s < 4; s++) {
            d.spot[s] = 23_010;
        }
        d.calls.put(23_100.0, steps(1_000, 1_000, 1_000, 1_000));
        d.calls.put(23_150.0, steps(900, 1_040, 1_100, 1_100));   // 4 % more at step 1: no move; 10 % at step 2: the wall moves
        d.puts.put(22_950.0, steps(2_000, 2_000, 2_000, 2_000));
        java.util.Map<String, Object> l = OiTapeController.ladder(d, 3, 1, 50, t -> true);
        @SuppressWarnings("unchecked") java.util.Map<String, Object> st = (java.util.Map<String, Object>) l.get("structure");
        @SuppressWarnings("unchecked") java.util.Map<String, Object> ceiling = (java.util.Map<String, Object>) st.get("nearCeiling");
        assertThat(ceiling).containsEntry("strike", 23_150.0).containsEntry("since", "09:16:00").containsEntry("from", 23_100.0)
                .containsEntry("oi", 1_100.0).containsEntry("change", 60.0);
        assertThat(OiTapeController.ladder(d, 1, 0, 50, t -> true).get("structure"))
                .extracting(m -> ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) m).get("nearCeiling")).get("strike")).isEqualTo(23_100.0);
        @SuppressWarnings("unchecked") java.util.Map<String, Object> floor = (java.util.Map<String, Object>) st.get("nearFloor");
        assertThat(floor).containsEntry("strike", 22_950.0).containsEntry("since", "09:15:00");
        assertThat(floor.get("from")).isNull();
        assertThat(st.get("atmIv")).isNull();                                             // no IV recorded: no ±1σ either
        assertThat(st.get("sigmaClose")).isNull();
    }

    @Test
    void aWallThatLeavesItsZoneKeepsItsPastWhenItComesBack() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10));
        d.steps = 5;
        double[] spot = {23_010, 23_120, 23_400, 23_010, 23_010};      // step 2: above every call strike, no ceiling at all
        System.arraycopy(spot, 0, d.spot, 0, 5);
        d.calls.put(23_100.0, steps(1_000, 1_000, 1_000, 1_000, 1_000));
        d.calls.put(23_150.0, steps(900, 900, 900, 900, 1_200));
        // step 1 moves the ceiling to 23,150 (23,100 is below spot); after the gap it comes back to 23,100: moved from 23,150
        @SuppressWarnings("unchecked") java.util.Map<String, Object> back = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) OiTapeController.ladder(d, 3, 2, 50, t -> true).get("structure")).get("nearCeiling");
        assertThat(back).containsEntry("strike", 23_100.0).containsEntry("from", 23_150.0).containsEntry("since", "09:16:30");
        // the same strike after the gap: no move, its own past kept
        d.calls.put(23_100.0, steps(1_000, 1_000, 1_000, 10, 10));
        @SuppressWarnings("unchecked") java.util.Map<String, Object> same = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) OiTapeController.ladder(d, 3, 2, 50, t -> true).get("structure")).get("nearCeiling");
        assertThat(same).containsEntry("strike", 23_150.0).containsEntry("from", 23_100.0).containsEntry("since", "09:15:30");
    }

    @Test
    void printsCountTheExchangesOiUpdatesPerContractAndTheLastStepNearTheMoneyOiChanged() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 9));
        d.steps = 7;
        for (int s = 0; s < 7; s++) {
            d.spot[s] = 23_010;
        }
        // first values in step 0 (no pre-open OI: not a print); a print whose ticks straddle steps 2 and 3 (the 23,000 call
        // changes in step 2, the 23,050 put in step 3), and one in step 5 that leaves the put unchanged
        d.calls.put(23_000.0, steps(100, 100, 110, 110, 110, 120, 120));
        d.puts.put(23_050.0, steps(200, 200, 200, 205, 205, 205, 205));
        d.calls.put(23_600.0, steps(1, 2, 3, 4, 5, 6, 7));                      // outside ATM ± 5: never counts
        assertThat(OiTapeController.ladder(d, 6, 1, 50, t -> true)).containsEntry("prints", 2).containsEntry("lastPrint", "09:17:30");
        assertThat(OiTapeController.ladder(d, 4, 0, 50, t -> true)).containsEntry("prints", 1).containsEntry("lastPrint", "09:16:30");
        assertThat(OiTapeController.ladder(d, 1, 0, 50, t -> true)).containsEntry("prints", 0).containsEntry("lastPrint", null);
        // a print's late tick in step 3 and the next print in step 4 that changes the same contract: two prints, the last in 4
        d.calls.put(23_000.0, steps(100, 100, 110, 115, 120, 120, 120));
        d.puts.put(23_050.0, steps(200, 200, 205, 205, 210, 210, 210));
        assertThat(OiTapeController.ladder(d, 4, 2, 50, t -> true)).containsEntry("prints", 2).containsEntry("lastPrint", "09:17:00");
    }

    @Test
    void sinceTheOpenStartsAfterTheDaysFirstOiPrintIncludingTicksThatRanIntoTheNextStep() {
        OiTapeController.Day d = new OiTapeController.Day(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 9));
        d.steps = 6;
        for (int s = 0; s < 6; s++) {
            d.spot[s] = 23_010;
        }
        d.seedCalls.put(23_000.0, 100.0);
        d.seedPuts.put(23_050.0, 200.0);
        // the first print straddles steps 1 and 2 (the call in step 1, the put in step 2); the next print is in step 4
        d.calls.put(23_000.0, steps(100, 110, 110, 110, 120, 120));
        d.puts.put(23_050.0, steps(200, 200, 205, 205, 210, 210));
        assertThat(OiTapeController.openingStep(d, 5, 50)).isEqualTo(2);
        assertThat(OiTapeController.openingStep(d, 0, 50)).isEqualTo(0);              // no print yet: no change
        // a contract changing in two adjacent steps is two prints: the first ends in its own step
        d.puts.put(23_050.0, steps(200, 200, 200, 200, 210, 210));
        d.calls.put(23_000.0, steps(100, 110, 115, 115, 120, 120));
        assertThat(OiTapeController.openingStep(d, 5, 50)).isEqualTo(1);
        // no pre-open OI near the money: the step the first values came in
        d.seedCalls.clear();
        d.seedPuts.clear();
        d.calls.put(23_000.0, steps(Double.NaN, Double.NaN, 110, 120, 120, 120));
        d.puts.put(23_050.0, steps(Double.NaN, Double.NaN, Double.NaN, 205, 205, 205));
        assertThat(OiTapeController.openingStep(d, 5, 50)).isEqualTo(2);
    }

    @Test
    void aWindowFromThePreviousCloseTakesTheOpensIvForWhoTradedOnlyWhenAsked() {
        OiTapeController.Day d = day();
        d.callIv.put(23_000.0, fsteps(Double.NaN, 0.150, 0.140, 0.140));
        d.callPx.put(23_000.0, fsteps(Double.NaN, 101, 96, 96));
        // call OI 1,000 at the previous close -> 1,300 at step 2; IV 0.150 at its first print of the day -> 0.140
        List<Object> r = ladderRow(OiTapeController.ladder(d, 2, -1, 50, t -> true, true), 23_000.0);
        assertThat(r.subList(1, 3)).containsExactly(1_300.0, 1_000.0);
        assertThat(r.subList(5, 7)).containsExactly((double) 0.14f, (double) 0.15f);
        assertThat(r.subList(9, 11)).containsExactly(96.0, 101.0);
        assertThat(r.get(15)).isEqualTo("WRITING");
        // the windows up to 30 minutes keep no IV then when they reach the pre-open
        List<Object> plain = ladderRow(OiTapeController.ladder(d, 2, -1, 50, t -> true), 23_000.0);
        assertThat(plain.get(6)).isNull();
        assertThat(plain.get(15)).isNull();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ladderRow(java.util.Map<String, Object> ladder, double k) {
        return ((List<List<Object>>) ladder.get("rows")).stream().filter(x -> x.get(0).equals(k)).findFirst().orElseThrow();
    }

    @Test
    void oneSigmaToTheCloseSharesTheOptionsVarianceOverTheTradingMinutesLeft() {
        LocalDate thu = LocalDate.of(2026, 11, 5), tue = LocalDate.of(2026, 11, 10);
        int i = OiTapeController.index(java.time.LocalTime.of(14, 29, 30));            // ends 14:30: 60 minutes left
        // expiry day: all of the variance to 15:30 is today's
        double years = 60.0 / (365 * 1_440);
        assertThat(OiTapeController.sigmaToClose(24_000, 0.15, tue, tue, i, t -> true))
                .isCloseTo(24_000 * 0.15 * Math.sqrt(years), org.assertj.core.data.Offset.offset(1e-6));
        // Thursday to Tuesday: Fri, Mon, Tue trade (weekend not), so today's hour is 60 of 60 + 3 × 375 trading minutes
        java.util.function.Predicate<LocalDate> weekdays = t -> t.getDayOfWeek().getValue() <= 5;
        double y = OiTapeController.yearsToExpiry(thu, tue, i);
        assertThat(OiTapeController.sigmaToClose(24_000, 0.15, thu, tue, i, weekdays))
                .isCloseTo(24_000 * 0.15 * Math.sqrt(y * 60 / (60 + 3 * 375)), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(OiTapeController.sigmaToClose(24_000, Double.NaN, thu, tue, i, weekdays)).isNaN();
    }

    @Test
    void aKeptDayRoundTripsItsPricesIvAndVolumeAndAFormatOneDayReadsWithoutThem() {
        OiTapeController.Day d = day();
        d.callIv.put(23_000.0, fsteps(0.15, 0.14));
        d.putVol.put(23_000.0, fsteps(10, 20, 30));
        OiTapeController.Day back = OiTapeController.decode(OiTapeController.encode(d));
        assertThat(back.extras).isTrue();
        assertThat(back.callIv.get(23_000.0)[1]).isEqualTo(0.14f);
        assertThat(back.putVol.get(23_000.0)[2]).isEqualTo(30f);
        assertThat(back.calls.get(23_000.0)[2]).isEqualTo(1_300.0);
        // a format-1 payload: the same bytes up to the seeds, no extras
        byte[] v1 = ChartDb.encode(out -> {
            out.writeInt(1);
            out.writeLong(d.date.toEpochDay());
            out.writeLong(d.expiry.toEpochDay());
            out.writeBoolean(false);
            out.writeInt(d.steps);
            ChartDb.writeDoubles(out, d.spot);
            ChartDb.writeDoubles(out, d.futPx);
            ChartDb.writeDoubles(out, d.futOi);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
        });
        OiTapeController.Day old = OiTapeController.decode(v1);
        assertThat(old).isNotNull();
        assertThat(old.extras).isFalse();
        assertThat(old.callIv).isEmpty();
    }
}
