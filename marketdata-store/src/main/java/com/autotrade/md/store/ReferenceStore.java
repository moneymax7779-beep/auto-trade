package com.autotrade.md.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.autotrade.core.history.DailyBar;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.time.MarketTime;

/** Reference candles (India VIX) in auto-trade's own database, table {@code ref.index_candle}. */
public final class ReferenceStore implements ReferenceData {

    public static final String DAY = "1day";
    public static final String MINUTE = "1minute";

    /** A candle to store; {@code start} is the bar start (IST midnight of the session for daily bars). */
    public record Candle(Instant start, LocalDate session, double open, double high, double low, double close) {
    }

    private final DataSource dataSource;

    public ReferenceStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Inserts or replaces candles; returns how many rows were written. */
    public int upsert(String symbol, String timeframe, String source, List<Candle> candles) {
        String sql = "insert into ref.index_candle (symbol, timeframe, bar_start, session_date, open, high, low, close, "
                + "source) values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (symbol, timeframe, bar_start) do update set "
                + "open = excluded.open, high = excluded.high, low = excluded.low, close = excluded.close, "
                + "source = excluded.source, loaded_at = now()";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Candle candle : candles) {
                statement.setString(1, symbol);
                statement.setString(2, timeframe);
                statement.setTimestamp(3, Timestamp.from(candle.start()));
                statement.setObject(4, candle.session());
                statement.setDouble(5, candle.open());
                statement.setDouble(6, candle.high());
                statement.setDouble(7, candle.low());
                statement.setDouble(8, candle.close());
                statement.setString(9, source);
                statement.addBatch();
            }
            int written = 0;
            for (int count : statement.executeBatch()) {
                written += Math.max(count, 0);
            }
            return written;
        } catch (SQLException e) {
            throw new IllegalStateException("reference candle upsert failed", e);
        }
    }

    @Override
    public List<DailyBar> dailyBars(String symbol, LocalDate session, int sessions) {
        String sql = "select session_date, open, high, low, close from ref.index_candle where symbol = ? "
                + "and timeframe = '1day' and session_date < ? order by session_date desc limit ?";
        List<DailyBar> bars = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, session);
            statement.setInt(3, sessions);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    bars.add(new DailyBar(rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3),
                            rs.getDouble(4), rs.getDouble(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reference daily lookup failed", e);
        }
        return bars;
    }

    @Override
    public List<MinuteBar> intradayBars(String symbol, LocalDate session) {
        String sql = "select bar_start, open, high, low, close from ref.index_candle where symbol = ? "
                + "and timeframe = '1minute' and session_date = ? order by bar_start";
        List<MinuteBar> bars = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, session);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    bars.add(new MinuteBar(rs.getTimestamp(1).toInstant(), rs.getDouble(2), rs.getDouble(3),
                            rs.getDouble(4), rs.getDouble(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reference intraday lookup failed", e);
        }
        return bars;
    }

    /** Stored bar counts and date range per timeframe, for the CLI. */
    public List<String> coverage(String symbol) {
        String sql = "select timeframe, count(*), min(session_date), max(session_date) from ref.index_candle "
                + "where symbol = ? group by 1 order by 1";
        List<String> lines = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    lines.add(symbol + " " + rs.getString(1) + ": " + rs.getLong(2) + " bars, " + rs.getObject(3)
                            + " .. " + rs.getObject(4));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reference coverage lookup failed", e);
        }
        return lines;
    }

    /** IST midnight of a session date, the bar start used for daily candles. */
    public static Instant dayStart(LocalDate session) {
        return session.atStartOfDay(MarketTime.IST).toInstant();
    }
}
