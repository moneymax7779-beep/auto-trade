package com.autotrade.core.history;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Facts about sessions before the one being traded or replayed. Implementations must only return
 * sessions strictly before {@code session}, so features stay point-in-time. The one exception is
 * {@link #reference()}'s intraday bars, which callers gate by bar end time themselves.
 */
public interface SessionHistory {

    /** The most recent session before {@code session}, spot OHLC up to {@code until} IST. */
    Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until);

    /**
     * Futures volume per IST minute (bar start) for up to {@code sessions} sessions before
     * {@code session}, newest first.
     */
    List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions);

    /**
     * Spot one-minute bars over continuous trading for up to {@code sessions} sessions before
     * {@code session}, newest session first, bars in time order within a session.
     */
    default List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
        return List.of();
    }

    /** Nearest-expiry ATM IV per minute for up to {@code sessions} sessions before {@code session}, newest first. */
    default List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        return List.of();
    }

    /** Reference series such as India VIX; none unless combined via {@link #withReference}. */
    default ReferenceData reference() {
        return ReferenceData.NONE;
    }

    /** This history with {@code reference} as its reference-series source. */
    default SessionHistory withReference(ReferenceData reference) {
        SessionHistory market = this;
        return new SessionHistory() {
            @Override
            public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
                return market.previousSession(underlying, session, until);
            }

            @Override
            public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
                return market.futuresMinuteVolumes(underlying, session, sessions);
            }

            @Override
            public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
                return market.spotMinuteBars(underlying, session, sessions);
            }

            @Override
            public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
                return market.atmIvMinutes(underlying, session, sessions);
            }

            @Override
            public ReferenceData reference() {
                return reference;
            }
        };
    }

    /** No history: previous-day levels, RVOL and percentiles stay unavailable. */
    SessionHistory NONE = new SessionHistory() {
        @Override
        public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
            return Optional.empty();
        }

        @Override
        public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
            return List.of();
        }
    };
}
