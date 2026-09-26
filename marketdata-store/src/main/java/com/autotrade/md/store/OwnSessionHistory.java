package com.autotrade.md.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import javax.sql.DataSource;

import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.IvSession;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;

/**
 * History from auto-trade's own database: md.* rows of ACTIVE loads (live captures and clones) and
 * the feature snapshots saved by live sessions (ATM IV per minute). Only sessions strictly before the
 * requested one are returned.
 */
public final class OwnSessionHistory implements SessionHistory {

    private static final String ACTIVE = " join md.load_manifest l on l.id = t.manifest_id and l.status = 'ACTIVE' ";
    private static final String IST_TIME = "(t.recv_ts at time zone 'Asia/Kolkata')::time";

    private final DataSource dataSource;

    public OwnSessionHistory(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
        List<LocalDate> dates = dates("md.index_tick", underlying, session, 1);
        if (dates.isEmpty()) {
            return Optional.empty();
        }
        String sql = "select (array_agg(t.price order by t.recv_ts))[1], max(t.price), min(t.price), "
                + "(array_agg(t.price order by t.recv_ts desc))[1] from md.index_tick t" + ACTIVE
                + "where t.underlying = ? and t.session_date = ? and " + IST_TIME + " >= '09:15' and " + IST_TIME + " < ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, underlying);
            statement.setObject(2, dates.getFirst());
            statement.setObject(3, until);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) {
                    return Optional.of(new DailyBar(dates.getFirst(), rs.getDouble(1), rs.getDouble(2), rs.getDouble(3),
                            rs.getDouble(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("own previous-session lookup failed", e);
        }
        return Optional.empty();
    }

    @Override
    public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
        List<LocalDate> dates = dates("md.future_tick", underlying, session, sessions);
        List<Map<LocalTime, Long>> result = new ArrayList<>();
        String sql = "with near as (select min(t.expiry) e from md.future_tick t" + ACTIVE + "where t.underlying = ? "
                + "and t.session_date = ? and t.expiry >= t.session_date), "
                + "m as (select date_trunc('minute', t.recv_ts) m, max(t.volume) v from md.future_tick t" + ACTIVE
                + "where t.underlying = ? and t.session_date = ? and t.expiry = (select e from near) group by 1) "
                + "select (m at time zone 'Asia/Kolkata')::time, v - coalesce(lag(v) over (order by m), v) from m order by m";
        try (Connection connection = dataSource.getConnection()) {
            for (LocalDate date : dates) {
                Map<LocalTime, Long> minutes = new TreeMap<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, underlying);
                    statement.setObject(2, date);
                    statement.setString(3, underlying);
                    statement.setObject(4, date);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            minutes.put(rs.getObject(1, LocalTime.class), Math.max(0, rs.getLong(2)));
                        }
                    }
                }
                result.add(minutes);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("own futures volume lookup failed", e);
        }
        return result;
    }

    @Override
    public List<List<MinuteBar>> spotMinuteBars(String underlying, LocalDate session, int sessions) {
        List<LocalDate> dates = dates("md.index_tick", underlying, session, sessions);
        List<List<MinuteBar>> result = new ArrayList<>();
        String sql = "select date_trunc('minute', t.recv_ts at time zone 'Asia/Kolkata') m, "
                + "(array_agg(t.price order by t.recv_ts))[1], max(t.price), min(t.price), "
                + "(array_agg(t.price order by t.recv_ts desc))[1] from md.index_tick t" + ACTIVE
                + "where t.underlying = ? and t.session_date = ? and " + IST_TIME + " >= '09:15' and " + IST_TIME
                + " < '15:15' group by 1 order by 1";
        try (Connection connection = dataSource.getConnection()) {
            for (LocalDate date : dates) {
                List<MinuteBar> bars = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, underlying);
                    statement.setObject(2, date);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            bars.add(new MinuteBar(MarketTime.fromIstLocal(rs.getObject(1, LocalDateTime.class)),
                                    rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
                        }
                    }
                }
                result.add(List.copyOf(bars));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("own spot bar lookup failed", e);
        }
        return result;
    }

    /** ATM IV per minute from the feature snapshots live sessions saved (kind LIVE_FEATURES). */
    @Override
    public List<IvSession> atmIvMinutes(String underlying, LocalDate session, int sessions) {
        String sql = "select s.session_date, ((s.snap_time - interval '1 minute') at time zone 'Asia/Kolkata')::time, "
                + "(s.features ->> 'options.atmIv')::float, (s.features ->> 'regime.dteTradingDays')::int "
                + "from feat.snapshot s join research.run r on r.id = s.run_id and r.kind = 'LIVE_FEATURES' "
                + "where s.underlying = ? and s.session_date < ? and s.session_date >= ? "
                + "and (s.features ->> 'options.atmIv') is not null order by 1 desc, 2";
        Map<LocalDate, Map<LocalTime, Double>> byDay = new TreeMap<>(Comparator.reverseOrder());
        Map<LocalDate, Boolean> expiry = new TreeMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, underlying);
            statement.setObject(2, session);
            statement.setObject(3, session.minusDays(400));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate date = rs.getObject(1, LocalDate.class);
                    byDay.computeIfAbsent(date, d -> new TreeMap<>()).put(rs.getObject(2, LocalTime.class), rs.getDouble(3));
                    expiry.merge(date, rs.getInt(4) == 0, Boolean::logicalOr);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("own IV history lookup failed", e);
        }
        return byDay.entrySet().stream().limit(sessions)
                .map(e -> new IvSession(e.getKey(), expiry.get(e.getKey()), Map.copyOf(e.getValue()))).toList();
    }

    /** Session dates with ACTIVE rows in {@code table} strictly before {@code session}, newest first. */
    private List<LocalDate> dates(String table, String underlying, LocalDate session, int limit) {
        String sql = "select distinct l.session_date from md.load_manifest l where l.status = 'ACTIVE' "
                + "and l.underlying = ? and l.session_date < ? and exists (select 1 from " + table + " t "
                + "where t.manifest_id = l.id limit 1) order by 1 desc limit ?";
        List<LocalDate> dates = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, underlying);
            statement.setObject(2, session);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    dates.add(rs.getObject(1, LocalDate.class));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("own session list lookup failed", e);
        }
        return dates;
    }
}
