package com.autotrade.md.zt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import javax.sql.DataSource;

import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.SessionHistory;

/**
 * History from zt-tiger-v2's one-minute candles (kept for every session, unlike raw ticks):
 * spot bars from {@code MULTI_TICK} with {@code BROKER_HISTORICAL_BACKFILL} as fallback, and futures
 * volume from {@code INDEX_FUTURES_MINUTE_VOLUME_LIVE_V4}. Bar times are zone-less IST.
 */
public final class ZtSessionHistory implements SessionHistory {

    static final List<String> SPOT_SOURCES = List.of("MULTI_TICK", "BROKER_HISTORICAL_BACKFILL");
    static final String FUTURES_VOLUME_SOURCE = "INDEX_FUTURES_MINUTE_VOLUME_LIVE_V4";
    private static final LocalTime OPEN = LocalTime.of(9, 15);

    private final DataSource source;

    public ZtSessionHistory(DataSource source) {
        this.source = source;
    }

    @Override
    public Optional<DailyBar> previousSession(String underlying, LocalDate session, LocalTime until) {
        String key = ZtSourceKeys.underlyingKey(underlying);
        try (Connection connection = source.getConnection()) {
            for (String spotSource : SPOT_SOURCES) {
                LocalDate previous = previousDate(connection, key, spotSource, session);
                if (previous == null) {
                    continue;
                }
                String sql = "select open_price, high_price, low_price, close_price from market_candles "
                        + "where underlying_key = ? and source = ? and timeframe = '1minute' "
                        + "and bar_start >= ? and bar_start < ? order by bar_start";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, key);
                    statement.setString(2, spotSource);
                    statement.setObject(3, previous.atTime(OPEN));
                    statement.setObject(4, previous.atTime(until));
                    try (ResultSet rs = statement.executeQuery()) {
                        double open = Double.NaN;
                        double high = Double.NEGATIVE_INFINITY;
                        double low = Double.POSITIVE_INFINITY;
                        double close = Double.NaN;
                        while (rs.next()) {
                            if (Double.isNaN(open)) {
                                open = rs.getDouble(1);
                            }
                            high = Math.max(high, rs.getDouble(2));
                            low = Math.min(low, rs.getDouble(3));
                            close = rs.getDouble(4);
                        }
                        if (!Double.isNaN(open)) {
                            return Optional.of(new DailyBar(previous, open, high, low, close));
                        }
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("previous session lookup failed", e);
        }
    }

    @Override
    public List<Map<LocalTime, Long>> futuresMinuteVolumes(String underlying, LocalDate session, int sessions) {
        String key = ZtSourceKeys.underlyingKey(underlying);
        String sql = "select bar_start, volume from market_candles where underlying_key = ? and source = ? "
                + "and timeframe = '1minute' and volume_complete and bar_start >= ? and bar_start < ? "
                + "order by bar_start";
        List<Map<LocalTime, Long>> result = new ArrayList<>();
        try (Connection connection = source.getConnection()) {
            List<LocalDate> dates = previousDates(connection, key, FUTURES_VOLUME_SOURCE, session, sessions);
            for (LocalDate date : dates) {
                Map<LocalTime, Long> minutes = new TreeMap<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, key);
                    statement.setString(2, FUTURES_VOLUME_SOURCE);
                    statement.setObject(3, date.atStartOfDay());
                    statement.setObject(4, date.plusDays(1).atStartOfDay());
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            minutes.put(rs.getObject(1, LocalDateTime.class).toLocalTime(), Math.round(rs.getDouble(2)));
                        }
                    }
                }
                result.add(minutes);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("futures volume history lookup failed", e);
        }
        return result;
    }

    /**
     * Index weights (percent) per constituent symbol as zt-tiger-v2 last recorded them before
     * {@code session}: the latest weight of each symbol in the most recent session with constituent ticks.
     */
    public Map<String, Double> constituentWeights(String underlying, LocalDate session) {
        String key = ZtSourceKeys.underlyingKey(underlying);
        Map<String, Double> weights = new TreeMap<>();
        try (Connection connection = source.getConnection()) {
            LocalDate latest = null;
            try (PreparedStatement statement = connection.prepareStatement("select session_date from market_tick_records "
                    + "where underlying_key = ? and session_date < ? and tick_type = 'COMPONENT' "
                    + "order by session_date desc limit 1")) {
                statement.setString(1, key);
                statement.setObject(2, session);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        latest = rs.getObject(1, LocalDate.class);
                    }
                }
            }
            if (latest == null) {
                return weights;
            }
            try (PreparedStatement statement = connection.prepareStatement("select distinct on (symbol) symbol, "
                    + "(payload_json::json ->> 'weight')::double precision from market_tick_records "
                    + "where underlying_key = ? and session_date = ? and tick_type = 'COMPONENT' "
                    + "order by symbol, sequence_number desc")) {
                statement.setString(1, key);
                statement.setObject(2, latest);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        double weight = rs.getDouble(2);
                        if (!rs.wasNull()) {
                            weights.put(rs.getString(1), weight);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("constituent weight lookup failed", e);
        }
        return weights;
    }

    private static LocalDate previousDate(Connection connection, String key, String source, LocalDate session)
            throws SQLException {
        List<LocalDate> dates = previousDates(connection, key, source, session, 1);
        return dates.isEmpty() ? null : dates.getFirst();
    }

    /** Distinct session dates with candles strictly before {@code session}, newest first. */
    private static List<LocalDate> previousDates(Connection connection, String key, String source, LocalDate session,
                                                 int limit) throws SQLException {
        String sql = "select distinct bar_start::date d from market_candles where underlying_key = ? and source = ? "
                + "and timeframe = '1minute' and bar_start < ? order by d desc limit ?";
        Map<LocalDate, Boolean> dates = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, source);
            statement.setObject(3, session.atStartOfDay());
            statement.setInt(4, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    dates.put(rs.getObject(1, LocalDate.class), true);
                }
            }
        }
        return List.copyOf(dates.keySet());
    }
}
