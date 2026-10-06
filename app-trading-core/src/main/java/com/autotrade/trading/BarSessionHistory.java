package com.autotrade.trading;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.IvSession;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;

/**
 * Session history from the stored one-minute bars ({@code hist.candle}): the previous session's daily bar, futures volume
 * per minute of earlier sessions (the nearest-expiry contract of each day), spot minute bars, and India VIX as reference
 * data. No IV history (the bars carry none): IV percentiles stay NaN in a bar replay.
 */
final class BarSessionHistory implements SessionHistory, ReferenceData {

    private final JdbcTemplate jdbc;

    BarSessionHistory(DataSource target) {
        this.jdbc = new JdbcTemplate(target);
    }

    @Override
    public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
        List<LocalDate> dates = dates(underlying, "INDEX", session, 1);
        if (dates.isEmpty()) {
            return Optional.empty();
        }
        LocalDate day = dates.getFirst();
        var rows = jdbc.queryForList("select (array_agg(open order by bar_start))[1] o, max(high) h, min(low) l, "
                + "(array_agg(close order by bar_start desc))[1] c from hist.candle where underlying = ? and kind = 'INDEX' "
                + "and bar_start >= ? and bar_start < ?", underlying, ts(day, LocalTime.of(9, 15)), ts(day, until));
        var r = rows.getFirst();
        return r.get("o") == null ? Optional.empty() : Optional.of(new DailyBar(day, num(r.get("o")), num(r.get("h")),
                num(r.get("l")), num(r.get("c"))));
    }

    @Override
    public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
        List<Map<LocalTime, Long>> result = new ArrayList<>();
        for (LocalDate day : dates(underlying, "FUTURE", session, sessions)) {
            Map<LocalTime, Long> minutes = new TreeMap<>();
            jdbc.query("with near as (select min(expiry) e from hist.candle where underlying = ? and kind = 'FUTURE' "
                    + "and bar_start >= ? and bar_start < ? and expiry >= ?::date) "
                    + "select (bar_start at time zone 'Asia/Kolkata')::time t, volume from hist.candle where underlying = ? "
                    + "and kind = 'FUTURE' and bar_start >= ? and bar_start < ? and expiry = (select e from near) order by bar_start",
                    rs -> {
                        minutes.put(rs.getObject(1, LocalTime.class), Math.max(0, rs.getLong(2)));
                    }, underlying, ts(day, LocalTime.MIN), ts(day.plusDays(1), LocalTime.MIN), day,
                    underlying, ts(day, LocalTime.MIN), ts(day.plusDays(1), LocalTime.MIN));
            result.add(minutes);
        }
        return result;
    }

    @Override
    public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
        List<List<MinuteBar>> result = new ArrayList<>();
        for (LocalDate day : dates(underlying, "INDEX", session, sessions)) {
            result.add(List.copyOf(bars(underlying, "INDEX", day, LocalTime.of(9, 15), LocalTime.of(15, 15))));
        }
        return result;
    }

    @Override
    public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        return List.of();
    }

    @Override
    public ReferenceData reference() {
        return this;
    }

    // ---------------------------------------------------------------- ReferenceData (India VIX from the bars)

    @Override
    public List<DailyBar> dailyBars(String symbol, LocalDate session, int sessions) {
        List<DailyBar> out = new ArrayList<>();
        for (LocalDate day : dates(symbol, "VIX", session, sessions)) {
            var r = jdbc.queryForList("select (array_agg(open order by bar_start))[1] o, max(high) h, min(low) l, "
                    + "(array_agg(close order by bar_start desc))[1] c from hist.candle where underlying = ? and kind = 'VIX' "
                    + "and bar_start >= ? and bar_start < ?", symbol, ts(day, LocalTime.MIN), ts(day.plusDays(1), LocalTime.MIN)).getFirst();
            if (r.get("o") != null) {
                out.add(new DailyBar(day, num(r.get("o")), num(r.get("h")), num(r.get("l")), num(r.get("c"))));
            }
        }
        return out;
    }

    @Override
    public List<MinuteBar> intradayBars(String symbol, LocalDate session) {
        return bars(symbol, "VIX", session, LocalTime.MIN, LocalTime.MAX);
    }

    // ---------------------------------------------------------------- helpers

    private List<MinuteBar> bars(String underlying, String kind, LocalDate day, LocalTime from, LocalTime to) {
        return jdbc.query("select bar_start, open, high, low, close from hist.candle where underlying = ? and kind = ? "
                + "and bar_start >= ? and bar_start < ? order by bar_start",
                (rs, i) -> new MinuteBar(rs.getTimestamp(1).toInstant(), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)),
                underlying, kind, ts(day, from), to == LocalTime.MAX ? ts(day.plusDays(1), LocalTime.MIN) : ts(day, to));
    }

    /** Stored session dates of {@code kind} strictly before {@code session}, newest first. */
    private List<LocalDate> dates(String underlying, String kind, LocalDate session, int limit) {
        return jdbc.query("select distinct (bar_start at time zone 'Asia/Kolkata')::date d from hist.candle where underlying = ? "
                + "and kind = ? and bar_start < ? order by 1 desc limit ?",
                (rs, i) -> rs.getObject(1, LocalDate.class), underlying, kind, ts(session, LocalTime.MIN), limit);
    }

    private static Timestamp ts(LocalDate day, LocalTime time) {
        Instant at = day.atTime(time).atZone(MarketTime.IST).toInstant();
        return Timestamp.from(at);
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }
}
