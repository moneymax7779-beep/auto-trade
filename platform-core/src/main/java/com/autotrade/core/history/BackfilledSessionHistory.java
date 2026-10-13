package com.autotrade.core.history;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.autotrade.core.time.MarketTime;

/**
 * Features v12 (A-090): captured history first, the stored one-minute bars ({@code hist.candle}) for the older sessions
 * it lacks. Spot minute bars are merged by session date (a captured session always wins), so percentiles and weekly /
 * monthly levels see months of sessions while the recent ones stay the recorded ticks. Futures volume (RVOL) and IV
 * stay captured-only: the bars' NIFTY futures volume runs 4-44 % below the live feed's on the same contract (13 Oct
 * check), so mixing them would bias RVOL. The OI baseline comes from the bars.
 */
public final class BackfilledSessionHistory implements SessionHistory {

    private final SessionHistory captured;
    private final SessionHistory bars;

    public BackfilledSessionHistory(SessionHistory captured, SessionHistory bars) {
        this.captured = captured;
        this.bars = bars;
    }

    @Override
    public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
        Optional<DailyBar> a = captured.previousSession(underlying, session, until);
        return a.isPresent() ? a : bars.previousSession(underlying, session, until);
    }

    @Override
    public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
        return captured.futuresMinuteVolumes(underlying, session, sessions);
    }

    @Override
    public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
        Map<LocalDate, List<MinuteBar>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (List<MinuteBar> day : captured.spotMinuteBars(underlying, session, sessions)) {
            if (!day.isEmpty()) {
                byDate.putIfAbsent(date(day), day);
            }
        }
        if (byDate.size() < sessions) {
            for (List<MinuteBar> day : bars.spotMinuteBars(underlying, session, sessions)) {
                if (!day.isEmpty()) {
                    byDate.putIfAbsent(date(day), day);
                }
            }
        }
        return new ArrayList<>(byDate.values()).subList(0, Math.min(sessions, byDate.size()));
    }

    @Override
    public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        return captured.atmIvMinutes(underlying, session, sessions);
    }

    @Override
    public ReferenceData reference() {
        return captured.reference();
    }

    @Override
    public OiBaseline oiBaseline(String underlying) {
        return bars.oiBaseline(underlying);
    }

    private static LocalDate date(List<MinuteBar> day) {
        return day.getFirst().start().atZone(MarketTime.IST).toLocalDate();
    }
}
