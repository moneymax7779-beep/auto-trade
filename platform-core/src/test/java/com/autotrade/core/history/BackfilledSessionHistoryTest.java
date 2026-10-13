package com.autotrade.core.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.autotrade.core.time.MarketTime;

/** Features v12 (A-090): captured sessions win; bars fill the older dates; volumes, IV stay captured-only. */
class BackfilledSessionHistoryTest {

    private static List<MinuteBar> day(LocalDate d, double price) {
        return List.of(new MinuteBar(d.atTime(9, 15).atZone(MarketTime.IST).toInstant(), price, price + 1, price - 1, price));
    }

    private static SessionHistory source(List<List<MinuteBar>> spot, List<Map<LocalTime, Long>> volumes, OiBaseline baseline) {
        return new SessionHistory() {
            @Override
            public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
                return Optional.empty();
            }

            @Override
            public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
                return volumes;
            }

            @Override
            public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
                return spot.subList(0, Math.min(sessions, spot.size()));
            }

            @Override
            public OiBaseline oiBaseline(String underlying) {
                return baseline;
            }
        };
    }

    @Test
    void capturedSessionsWinAndBarsFillOlderDates() {
        LocalDate oct12 = LocalDate.of(2026, 10, 12), oct9 = LocalDate.of(2026, 10, 9), sep30 = LocalDate.of(2026, 9, 30),
                sep29 = LocalDate.of(2026, 9, 29);
        SessionHistory captured = source(List.of(day(oct9, 100), day(sep30, 200)), List.of(Map.of(LocalTime.NOON, 5L)),
                OiBaseline.NONE);
        OiBaseline baseline = new OiBaseline(new OiBaseline.Params(3, 2, 0.5, 5),
                Map.of(new OiBaseline.Cell.Key("CE", 1, 2), new OiBaseline.Cell(0, 1, 50)));
        SessionHistory bars = source(List.of(day(sep30, 999), day(sep29, 300)), List.of(Map.of(), Map.of()), baseline);
        BackfilledSessionHistory merged = new BackfilledSessionHistory(captured, bars);

        List<List<MinuteBar>> spot = merged.spotMinuteBars("NIFTY", oct12, 3);
        assertThat(spot).extracting(d -> d.getFirst().close()).containsExactly(100.0, 200.0, 300.0);   // 30 Sep captured
        assertThat(merged.futuresMinuteVolumes("NIFTY", oct12, 20)).hasSize(1);                       // captured only
        assertThat(merged.oiBaseline("NIFTY")).isSameAs(baseline);
        assertThat(merged.spotMinuteBars("NIFTY", oct12, 2)).hasSize(2);
    }

    @Test
    void robustZScoreNeedsEnoughObservations() {
        OiBaseline b = new OiBaseline(new OiBaseline.Params(3, 2, 0.5, 5), Map.of(
                new OiBaseline.Cell.Key("PE", 0, 3), new OiBaseline.Cell(0.001, 0.002, 40),
                new OiBaseline.Cell.Key("PE", 1, 3), new OiBaseline.Cell(0.001, 0.002, 10)));
        assertThat(b.z("PE", 0, 3, 0.001 + 1.4826 * 0.002 * 2)).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(b.z("PE", 1, 3, 0.01)).isNaN();                     // 10 observations < 30
        assertThat(b.z("CE", 0, 3, 0.01)).isNaN();                     // no cell
        assertThat(OiBaseline.dteBucket(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10))).isEqualTo(4);
        assertThat(OiBaseline.NONE.isEmpty()).isTrue();
    }
}
