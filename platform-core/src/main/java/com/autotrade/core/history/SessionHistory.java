package com.autotrade.core.history;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Facts about sessions before the one being traded or replayed. Implementations must only return
 * sessions strictly before {@code session}, so features stay point-in-time.
 */
public interface SessionHistory {

    /** The most recent session before {@code session}, spot OHLC up to {@code until} IST. */
    Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until);

    /**
     * Futures volume per IST minute (bar start) for up to {@code sessions} sessions before
     * {@code session}, newest first.
     */
    List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions);

    /** No history: previous-day levels and RVOL stay unavailable. */
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
