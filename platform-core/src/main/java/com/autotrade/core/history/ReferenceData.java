package com.autotrade.core.history;

import java.time.LocalDate;
import java.util.List;

/**
 * Reference series that are not the traded underlying, such as India VIX ({@link #INDIA_VIX}).
 */
public interface ReferenceData {

    String INDIA_VIX = "INDIA_VIX";

    /** Daily bars strictly before {@code session}, newest first, at most {@code sessions}. */
    List<DailyBar> dailyBars(String symbol, LocalDate session, int sessions);

    /**
     * One-minute bars of {@code session} in time order. In replay the whole day is returned; live, the
     * list grows as bars complete. Callers must use only bars whose {@link MinuteBar#end()} is at or
     * before the time they evaluate.
     */
    List<MinuteBar> intradayBars(String symbol, LocalDate session);

    ReferenceData NONE = new ReferenceData() {
        @Override
        public List<DailyBar> dailyBars(String symbol, LocalDate session, int sessions) {
            return List.of();
        }

        @Override
        public List<MinuteBar> intradayBars(String symbol, LocalDate session) {
            return List.of();
        }
    };
}
