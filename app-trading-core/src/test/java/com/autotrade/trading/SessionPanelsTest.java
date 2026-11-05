package com.autotrade.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The session page's analysis panels: 3-minute structure as ma-cross sees it, trade excursions, the day's context. */
class SessionPanelsTest {

    private static final LocalDate DAY = LocalDate.of(2025, 12, 29);

    private static List<LevelsService.Bar> minutes(double... closes) {
        List<LevelsService.Bar> out = new ArrayList<>();
        LocalTime t = LocalTime.of(9, 15);
        for (double c : closes) {
            out.add(new LevelsService.Bar(t, c, c + 1, c - 1, c));
            t = t.plusMinutes(1);
        }
        return out;
    }

    @Test
    void threeMinuteBarsAlignTo0915AndAnUnfinishedDayDropsItsFormingBar() {
        List<LevelsService.Bar> three = SessionPanelsController.threeMinute(minutes(10, 11, 12, 13, 14, 15, 16), true);
        assertThat(three).extracting(b -> b.t().toString()).containsExactly("09:15", "09:18", "09:21");
        LevelsService.Bar first = three.getFirst();
        assertThat(List.of(first.open(), first.high(), first.low(), first.close())).containsExactly(10.0, 13.0, 9.0, 12.0);
        assertThat(SessionPanelsController.threeMinute(minutes(10, 11, 12, 13, 14, 15, 16), false)).hasSize(2);
    }

    @Test
    void emasStartAtTheCloseOfTheirNinthAndTwentiethBarAndSwingsAreConfirmedTwoBarsLater() {
        List<LevelsService.Bar> three = new ArrayList<>();
        double[] closes = {100, 102, 104, 106, 104, 102, 100, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122};
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            three.add(new LevelsService.Bar(LocalTime.of(9, 15).plusMinutes(3L * i), c, c + 1, c - 1, c));
        }
        SessionPanelsController.Structure s = SessionPanelsController.structure(DAY, three);
        assertThat(s.ema9().getFirst().get(0)).isEqualTo("09:42");             // the 9th bar (09:39-09:42) closes
        assertThat((Double) s.ema9().getFirst().get(1)).isCloseTo(101.78, within(0.01));   // the simple average seeds it
        assertThat(s.ema20()).singleElement().satisfies(p -> assertThat(p.get(0)).isEqualTo("10:15"));
        // the high of the 09:24 bar (107) is a pivot once the 09:30 bar closes; the low of 09:36 (97) once 09:42 closes
        assertThat(s.swings()).containsExactly(new SessionPanelsController.Swing("09:24", "09:33", 107, true),
                new SessionPanelsController.Swing("09:36", "09:45", 97, false));
        assertThat(SessionPanelsController.stopAt(s.swings(), "09:44", true)).isNull();     // not yet confirmed
        assertThat(SessionPanelsController.stopAt(s.swings(), "09:45", true)).isEqualTo(97.0);
        assertThat(SessionPanelsController.stopAt(s.swings(), "09:45", false)).isEqualTo(107.0);
    }

    @Test
    void aPositionSymbolNamesItsContract() {
        assertThat(SessionPanelsController.contract("NIFTY 25950 CE 30 DEC 25"))
                .isEqualTo(new SessionPanelsController.Contract("NIFTY", 25950, "CE", LocalDate.of(2025, 12, 30)));
        assertThat(SessionPanelsController.contract("SENSEX 84700 PE 1 Jan 26").expiry()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(SessionPanelsController.contract("NIFTY 25950 STRADDLE")).isNull();
    }

    @Test
    void excursionIsTheBestAndWorstPremiumFromTheEntryMinuteToTheExitMinute() {
        List<SessionPanelsController.Minute> bars = List.of(
                new SessionPanelsController.Minute("14:11", 50, 70, 40, 55, null),         // before the entry: ignored
                new SessionPanelsController.Minute("14:12", 55, 57, 54, 56, null),
                new SessionPanelsController.Minute("14:13", 56, 58, 53, 54, null),
                new SessionPanelsController.Minute("14:15", 54, 55, 52, 53, null),
                new SessionPanelsController.Minute("14:16", 53, 80, 30, 60, null));        // after the exit: ignored
        Map<String, Object> e = SessionPanelsController.excursion(bars, "14:12", "14:15", 55.5, 1500);
        assertThat(e).containsEntry("bestPrice", 58.0).containsEntry("bestAt", "14:13").containsEntry("mfe", 2.5)
                .containsEntry("mfeRupees", 3750L).containsEntry("worstPrice", 52.0).containsEntry("mae", 3.5)
                .containsEntry("maeRupees", 5250L);
    }

    @Test
    void theStraddleUsesTheStrikeNearestTheIndexEachMinuteAndCarriesALegsLastPrice() {
        List<LevelsService.Bar> index = List.of(new LevelsService.Bar(LocalTime.of(10, 0), 25970, 25971, 25969, 25970),
                new LevelsService.Bar(LocalTime.of(10, 1), 25990, 25991, 25989, 25990));
        Map<String, Map<String, Double>> quotes = Map.of(
                "25950.0|CE", Map.of("10:00", 60.0), "25950.0|PE", Map.of("10:00", 40.0),
                "26000.0|CE", Map.of("09:58", 35.0), "26000.0|PE", Map.of("10:01", 45.0));
        List<List<Object>> s = SessionPanelsController.straddle(DAY, index, quotes, 50, LocalDate.of(2025, 12, 30));
        assertThat(s).hasSize(2);
        assertThat(s.get(0).subList(0, 3)).containsExactly("10:00", 100.0, 25950.0);
        assertThat(s.get(1).subList(0, 3)).containsExactly("10:01", 80.0, 26000.0);      // the call's 09:58 price carried
        // 80 / (0.7979 x 25990 x sqrt(29 h 29 min in years)) = 6.65 %
        assertThat((Double) s.get(1).get(3)).isCloseTo(6.65, within(0.01));
        List<List<Object>> expiring = SessionPanelsController.straddle(DAY,
                List.of(new LevelsService.Bar(LocalTime.of(15, 10), 25970, 25971, 25969, 25970)),
                Map.of("25950.0|CE", Map.of("15:10", 20.0), "25950.0|PE", Map.of("15:10", 2.0)), 50, DAY);
        assertThat(expiring.getFirst().get(3)).isNull();                                  // 20 minutes to the expiry close
    }

    @Test
    void contextFiguresNeedEnoughHistoryAndLabelTheDayOnlyByItsShape() {
        assertThat(SessionPanelsController.percentile(List.of(10.0, 12.0), 11)).isNull();
        List<Double> year = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            year.add((double) i);
        }
        assertThat(SessionPanelsController.percentile(year, 50)).isEqualTo(25.0);
        assertThat(SessionPanelsController.shape(100, 130, 95, 128, 25.0, true))
                .containsEntry("type", "trend").containsEntry("direction", "up").containsEntry("rangeVsExpected", 1.4);
        assertThat(SessionPanelsController.shape(100, 110, 90, 101, 25.0, true)).containsEntry("type", "range");
        assertThat(SessionPanelsController.longPct(30, 70)).isEqualTo(30.0);
    }
}
