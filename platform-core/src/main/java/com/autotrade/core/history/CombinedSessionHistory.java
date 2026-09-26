package com.autotrade.core.history;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * zt-tiger-v2's history and auto-trade's own captured history: each lookup uses whichever source
 * holds more earlier sessions (zt-tiger-v2 on a tie), and the more recent previous session. So an
 * underlying zt-tiger-v2 never recorded (BANKNIFTY) builds history from auto-trade's own capture,
 * and sessions zt-tiger-v2 has rotated out stay available.
 */
public final class CombinedSessionHistory implements SessionHistory {

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
        return larger(primary.futuresMinuteVolumes(underlying, session, sessions),
                secondary.futuresMinuteVolumes(underlying, session, sessions));
    }

    @Override
    public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
        return larger(primary.spotMinuteBars(underlying, session, sessions),
                secondary.spotMinuteBars(underlying, session, sessions));
    }

    @Override
    public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        return larger(primary.atmIvMinutes(underlying, session, sessions),
                secondary.atmIvMinutes(underlying, session, sessions));
    }

    @Override
    public ReferenceData reference() {
        return reference;
    }

    private static <T> List<T> larger(List<T> a, List<T> b) {
        return b.size() > a.size() ? b : a;
    }
}
