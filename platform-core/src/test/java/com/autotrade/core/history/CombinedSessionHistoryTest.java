package com.autotrade.core.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.autotrade.core.time.MarketTime;

/** A stale primary (the Mac's zt-tiger-v2 copy, ending 1 Oct) must not hide the secondary's newer sessions. */
class CombinedSessionHistoryTest {

    private static final LocalDate OCT15 = LocalDate.of(2026, 10, 15), OCT14 = LocalDate.of(2026, 10, 14),
            OCT13 = LocalDate.of(2026, 10, 13), OCT1 = LocalDate.of(2026, 10, 1), SEP30 = LocalDate.of(2026, 9, 30),
            SEP29 = LocalDate.of(2026, 9, 29), SEP26 = LocalDate.of(2026, 9, 26);

    private static List<MinuteBar> day(LocalDate d, double price) {
        return List.of(new MinuteBar(d.atTime(9, 15).atZone(MarketTime.IST).toInstant(), price, price + 1, price - 1, price));
    }

    /** A source holding {@code dates}, newest first; each date's close (and volume) is {@code tag} plus its index. */
    private static SessionHistory source(double tag, LocalDate... dates) {
        return new SessionHistory() {
            @Override
            public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
                return dates.length == 0 ? Optional.empty() : Optional.of(new DailyBar(dates[0], tag, tag, tag, tag));
            }

            @Override
            public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
                return java.util.stream.IntStream.range(0, Math.min(sessions, dates.length))
                        .mapToObj(i -> Map.of(LocalTime.of(9, 15), (long) tag + i)).toList();
            }

            @Override
            public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
                return java.util.stream.IntStream.range(0, Math.min(sessions, dates.length))
                        .mapToObj(i -> day(dates[i], tag + i)).toList();
            }

            @Override
            public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
                return java.util.stream.IntStream.range(0, Math.min(sessions, dates.length))
                        .mapToObj(i -> new IvSession(dates[i], false, Map.of(LocalTime.NOON, tag + i))).toList();
            }
        };
    }

    @Test
    void staleLargerPrimaryDoesNotHideNewerSessions() {
        SessionHistory staleZt = source(1000, OCT1, SEP30, SEP29, SEP26);   // more sessions, but ends 1 Oct
        SessionHistory own = source(2000, OCT14, OCT13, OCT1);
        CombinedSessionHistory combined = new CombinedSessionHistory(staleZt, own, ReferenceData.NONE);

        // own's sessions, though fewer (the v12 bar backfill fills the older dates)
        assertThat(combined.spotMinuteBars("SENSEX", OCT15, 4)).extracting(d -> d.getFirst().close())
                .containsExactly(2000.0, 2001.0, 2002.0);
        // the prior opening range comes from the session just before the replayed one
        assertThat(combined.spotMinuteBars("SENSEX", OCT15, 1)).extracting(d -> d.getFirst().close())
                .containsExactly(2000.0);
        // volumes and IV likewise
        assertThat(combined.futuresMinuteVolumes("SENSEX", OCT15, 20)).hasSize(3)
                .first().isEqualTo(Map.of(LocalTime.of(9, 15), 2000L));
        assertThat(combined.atmIvMinutes("SENSEX", OCT15, 20)).extracting(IvSession::session)
                .containsExactly(OCT14, OCT13, OCT1);
        assertThat(combined.previousSession("SENSEX", OCT15, LocalTime.NOON)).get()
                .extracting(DailyBar::session).isEqualTo(OCT14);
    }

    @Test
    void currentSourcesKeepTheLargerOneAsBefore() {
        // GCP: zt current with more sessions, own capture current with fewer -> zt everywhere (former rule)
        SessionHistory zt = source(1000, OCT14, OCT13, OCT1, SEP30);
        SessionHistory own = source(2000, OCT14, OCT13);
        CombinedSessionHistory combined = new CombinedSessionHistory(zt, own, ReferenceData.NONE);

        assertThat(combined.spotMinuteBars("NIFTY", OCT15, 20)).extracting(d -> d.getFirst().close())
                .containsExactly(1000.0, 1001.0, 1002.0, 1003.0);
        assertThat(combined.futuresMinuteVolumes("NIFTY", OCT15, 20)).hasSize(4)
                .first().isEqualTo(Map.of(LocalTime.of(9, 15), 1000L));
        assertThat(combined.atmIvMinutes("NIFTY", OCT15, 20)).hasSize(4)
                .first().extracting(IvSession::atmIvByMinute).isEqualTo(Map.of(LocalTime.NOON, 1000.0));

        // own capture larger on a tie of newest session -> own, as before; full tie -> the primary
        CombinedSessionHistory ownLarger = new CombinedSessionHistory(source(1000, OCT14), own, ReferenceData.NONE);
        assertThat(ownLarger.atmIvMinutes("NIFTY", OCT15, 20)).hasSize(2);
        assertThat(ownLarger.futuresMinuteVolumes("NIFTY", OCT15, 20)).hasSize(2);
        CombinedSessionHistory equal = new CombinedSessionHistory(source(1000, OCT14, OCT13), own, ReferenceData.NONE);
        assertThat(equal.futuresMinuteVolumes("NIFTY", OCT15, 20).getFirst()).containsValue(1000L);
    }

    @Test
    void emptySourceFallsBackToTheOther() {
        // BANKNIFTY: zt-tiger-v2 never recorded it
        CombinedSessionHistory combined = new CombinedSessionHistory(source(1000), source(2000, OCT14, OCT13),
                ReferenceData.NONE);
        assertThat(combined.spotMinuteBars("BANKNIFTY", OCT15, 5)).hasSize(2);
        assertThat(combined.futuresMinuteVolumes("BANKNIFTY", OCT15, 5)).hasSize(2);
        assertThat(combined.atmIvMinutes("BANKNIFTY", OCT15, 5)).hasSize(2);
        assertThat(combined.previousSession("BANKNIFTY", OCT15, LocalTime.NOON)).get()
                .extracting(DailyBar::session).isEqualTo(OCT14);
    }

    @Test
    void withBarBackfillTheStaleCopysOlderSessionsComeFromTheBars() {
        // the Mac: zt ends 1 Oct, own capture 14 and 13 Oct, hist.candle up to 5 Oct (features v12 backfill)
        SessionHistory combined = new CombinedSessionHistory(source(1000, OCT1, SEP30, SEP29, SEP26),
                source(2000, OCT14, OCT13), ReferenceData.NONE);
        SessionHistory history = new BackfilledSessionHistory(combined, source(3000, OCT1, SEP30, SEP29));
        assertThat(history.spotMinuteBars("SENSEX", OCT15, 4)).extracting(d -> d.getFirst().close())
                .containsExactly(2000.0, 2001.0, 3000.0, 3001.0);
    }
}
