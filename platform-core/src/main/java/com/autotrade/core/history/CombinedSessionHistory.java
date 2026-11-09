package com.autotrade.core.history;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.autotrade.core.time.MarketTime;

/**
 * zt-tiger-v2's history and auto-trade's own captured history. Each lookup uses one source: the one whose newest
 * session is more recent, on a tie the one holding more sessions, on a full tie the primary (zt-tiger-v2). So an
 * underlying zt-tiger-v2 never recorded (BANKNIFTY) builds history from auto-trade's own capture, sessions
 * zt-tiger-v2 has rotated out stay available, and a stale zt-tiger-v2 copy (the Mac's ends 1 Oct 2026) never hides
 * newer captured sessions just because it holds more older ones. Futures volumes carry no dates, so their recency is
 * the source's {@link #previousSession previous session}. With both sources current, as on GCP, this is the former
 * "more sessions wins" rule unchanged.
 */
public final class CombinedSessionHistory implements SessionHistory {

    private static final LocalTime SESSION_END = LocalTime.of(15, 30);

    private final SessionHistory primary;
    private final SessionHistory secondary;
    private final ReferenceData reference;

    public CombinedSessionHistory(SessionHistory primary, SessionHistory secondary, ReferenceData reference) {
        this.primary = primary;
        this.secondary = secondary;
        this.reference = reference;
    }

    @Override
    public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
        Optional<DailyBar> a = primary.previousSession(underlying, session, until);
        Optional<DailyBar> b = secondary.previousSession(underlying, session, until);
        if (a.isEmpty()) {
            return b;
        }
        return b.isPresent() && b.get().session().isAfter(a.get().session()) ? b : a;
    }

    @Override
    public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
        List<Map<LocalTime, Long>> a = primary.futuresMinuteVolumes(underlying, session, sessions);
        List<Map<LocalTime, Long>> b = secondary.futuresMinuteVolumes(underlying, session, sessions);
        if (a.isEmpty() || b.isEmpty()) {
            return larger(a, b);
        }
        return pick(a, b, newestSession(primary, underlying, session), newestSession(secondary, underlying, session));
    }

    @Override
    public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
        List<List<MinuteBar>> a = primary.spotMinuteBars(underlying, session, sessions);
        List<List<MinuteBar>> b = secondary.spotMinuteBars(underlying, session, sessions);
        return pick(a, b, newest(a, CombinedSessionHistory::date), newest(b, CombinedSessionHistory::date));
    }

    @Override
    public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        List<IvSession> a = primary.atmIvMinutes(underlying, session, sessions);
        List<IvSession> b = secondary.atmIvMinutes(underlying, session, sessions);
        return pick(a, b, newest(a, IvSession::session), newest(b, IvSession::session));
    }

    @Override
    public ReferenceData reference() {
        return reference;
    }

    @Override
    public OiBaseline oiBaseline(String underlying) {
        OiBaseline a = primary.oiBaseline(underlying);
        return a.isEmpty() ? secondary.oiBaseline(underlying) : a;
    }

    /** The list whose newest session is more recent (null: none); on a tie the larger, on a full tie {@code a}. */
    static <T> List<T> pick(List<T> a, List<T> b, LocalDate newestA, LocalDate newestB) {
        if (a.isEmpty() || b.isEmpty() || newestA == null || newestB == null || newestA.equals(newestB)) {
            return larger(a, b);
        }
        return newestB.isAfter(newestA) ? b : a;
    }

    private static LocalDate newestSession(SessionHistory source, String underlying, LocalDate session) {
        return source.previousSession(underlying, session, SESSION_END).map(DailyBar::session).orElse(null);
    }

    /** The first (newest) session's date that is known; null when none is. */
    private static <T> LocalDate newest(List<T> sessions, Function<T, LocalDate> date) {
        return sessions.stream().map(date).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static LocalDate date(List<MinuteBar> day) {
        return day.isEmpty() ? null : day.getFirst().start().atZone(MarketTime.IST).toLocalDate();
    }

    private static <T> List<T> larger(List<T> a, List<T> b) {
        return b.size() > a.size() ? b : a;
    }
}
