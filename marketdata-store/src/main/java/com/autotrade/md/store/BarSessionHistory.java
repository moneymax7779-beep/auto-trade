package com.autotrade.md.store;

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
import com.autotrade.core.history.OiBaseline;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;

/**
 * Session history from the stored one-minute bars ({@code hist.candle}): the previous session's daily bar, futures volume
 * per minute of earlier sessions (the nearest-expiry contract of each day), spot minute bars, and India VIX as reference
 * data. No IV history (the bars carry none): IV percentiles stay NaN in a bar replay. Features v12 also back-fills live
 * and tick-replay history with its spot bars and reads the OI baseline built from its option bars (A-090).
 */
public final class BarSessionHistory implements SessionHistory, ReferenceData {

    private final JdbcTemplate jdbc;

    public BarSessionHistory(DataSource target) {
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
        List<LocalDate> dates = dates(underlying, "INDEX", session, sessions);
        if (dates.isEmpty()) {
            return List.of();
        }
        // one range query for every session, grouped by IST date (dates are newest first)
        Map<LocalDate, List<MinuteBar>> byDate = new TreeMap<>(java.util.Comparator.reverseOrder());
        dates.forEach(d -> byDate.put(d, new ArrayList<>()));
        jdbc.query("select bar_start, open, high, low, close from hist.candle where underlying = ? and kind = 'INDEX' "
                + "and bar_start >= ? and bar_start < ? and (bar_start at time zone 'Asia/Kolkata')::time >= '09:15' "
                + "and (bar_start at time zone 'Asia/Kolkata')::time < '15:15' order by bar_start",
                rs -> {
                    Instant start = rs.getTimestamp(1).toInstant();
                    List<MinuteBar> day = byDate.get(start.atZone(MarketTime.IST).toLocalDate());
                    if (day != null) {
                        day.add(new MinuteBar(start, rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
                    }
                }, underlying, ts(dates.getLast(), LocalTime.MIN), ts(dates.getFirst().plusDays(1), LocalTime.MIN));
        return byDate.values().stream().map(List::copyOf).toList();
    }

    @Override
    public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        return List.of();
    }

    @Override
    public ReferenceData reference() {
        return this;
    }

    /** Features v12 (A-090): the near-ATM OI change baseline built from these bars ({@code ref.oi_baseline}). */
    @Override
    public OiBaseline oiBaseline(String underlying) {
        Map<OiBaseline.Cell.Key, OiBaseline.Cell> cells = new java.util.HashMap<>();
        java.util.Set<OiBaseline.Params> params = new java.util.HashSet<>();
        jdbc.query("select side, dte_bucket, minute_bucket, median, mad, n, near_atm_strikes, heavy_weight_strikes, "
                + "outer_strike_weight, window_min from ref.oi_baseline where underlying = ?", rs -> {
                    cells.put(new OiBaseline.Cell.Key(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                            new OiBaseline.Cell(rs.getDouble(4), rs.getDouble(5), rs.getInt(6)));
                    params.add(new OiBaseline.Params(rs.getInt(7), rs.getInt(8), rs.getDouble(9), rs.getInt(10)));
                }, underlying);
        if (cells.isEmpty()) {
            return OiBaseline.NONE;
        }
        // rows built with different definitions are never mixed: no single definition, so the engine refuses it
        return new OiBaseline(params.size() == 1 ? params.iterator().next() : null, cells);
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
