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
import com.autotrade.core.history.GiftQuote;
import com.autotrade.core.history.MinuteBar;
import com.autotrade.core.history.ParticipantOi;
import com.autotrade.core.history.ReferenceData;
import com.autotrade.core.time.MarketTime;

/**
 * Reference data in auto-trade's own database: candles (India VIX, {@code ref.index_candle}), GIFT Nifty quotes
 * ({@code ref.gift_nifty_quote}) and NSE participant-wise open interest ({@code ref.participant_oi}).
 */
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
        // Daily rows come from a one-off backfill; later sessions have only the captured one-minute bars, so a session
        // without a 1day row is summarised from its 1minute rows (first open, high, low, last close).
        String sql = "with d as (select session_date, open, high, low, close from ref.index_candle where symbol = ? "
                + "and timeframe = '1day' and session_date < ?), "
                + "m as (select session_date, (array_agg(open order by bar_start))[1] open, max(high) high, min(low) low, "
                + "(array_agg(close order by bar_start desc))[1] close from ref.index_candle where symbol = ? "
                + "and timeframe = '1minute' and session_date < ? and session_date not in (select session_date from d) "
                + "group by session_date) "
                + "select session_date, open, high, low, close from (select * from d union all select * from m) x "
                + "order by session_date desc limit ?";
        List<DailyBar> bars = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, session);
            statement.setString(3, symbol);
            statement.setObject(4, session);
            statement.setInt(5, sessions);
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

    @Override
    public List<java.util.Map<java.time.LocalTime, Double>> auctionTurnover(String underlying, LocalDate session,
                                                                           int sessions) {
        String sql = "with last as (select distinct on (a.session_date, a.symbol, date_trunc('minute', a.recv_ts)) "
                + "a.session_date, (date_trunc('minute', a.recv_ts) at time zone 'Asia/Kolkata')::time m, "
                + "a.iep * coalesce(a.eq_qty, 0) v from md.auction_tick a join md.load_manifest l on l.id = a.manifest_id "
                + "and l.status = 'ACTIVE' where a.underlying = ? and a.session_date < ? and a.session_date >= ? "
                + "order by a.session_date, a.symbol, date_trunc('minute', a.recv_ts), a.recv_ts desc) "
                + "select session_date, m, sum(v) from last group by 1, 2 order by 1 desc, 2";
        java.util.Map<LocalDate, java.util.Map<java.time.LocalTime, Double>> byDay = new java.util.TreeMap<>(
                java.util.Comparator.reverseOrder());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, underlying);
            statement.setObject(2, session);
            statement.setObject(3, session.minusDays(120));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    byDay.computeIfAbsent(rs.getObject(1, LocalDate.class), d -> new java.util.TreeMap<>())
                            .put(rs.getObject(2, java.time.LocalTime.class), rs.getDouble(3));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("auction turnover history lookup failed", e);
        }
        return byDay.values().stream().limit(sessions).toList();
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
    /** A participant row as published, with the stock-option columns the features do not use. */
    public record ParticipantRow(ParticipantOi oi, long optionStockCallLong, long optionStockPutLong,
                                 long optionStockCallShort, long optionStockPutShort) {
    }

    /** Inserts or replaces one session's participant rows; returns how many were written. */
    public int upsertParticipantOi(List<ParticipantRow> rows) {
        String sql = "insert into ref.participant_oi (session_date, client_type, future_index_long, future_index_short, "
                + "future_stock_long, future_stock_short, option_index_call_long, option_index_put_long, "
                + "option_index_call_short, option_index_put_short, option_stock_call_long, option_stock_put_long, "
                + "option_stock_call_short, option_stock_put_short, total_long, total_short) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (session_date, client_type) do update set "
                + "future_index_long = excluded.future_index_long, future_index_short = excluded.future_index_short, "
                + "future_stock_long = excluded.future_stock_long, future_stock_short = excluded.future_stock_short, "
                + "option_index_call_long = excluded.option_index_call_long, option_index_put_long = excluded.option_index_put_long, "
                + "option_index_call_short = excluded.option_index_call_short, option_index_put_short = excluded.option_index_put_short, "
                + "option_stock_call_long = excluded.option_stock_call_long, option_stock_put_long = excluded.option_stock_put_long, "
                + "option_stock_call_short = excluded.option_stock_call_short, option_stock_put_short = excluded.option_stock_put_short, "
                + "total_long = excluded.total_long, total_short = excluded.total_short, loaded_at = now()";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ParticipantRow row : rows) {
                ParticipantOi o = row.oi();
                statement.setObject(1, o.session());
                statement.setString(2, o.clientType());
                statement.setLong(3, o.futureIndexLong());
                statement.setLong(4, o.futureIndexShort());
                statement.setLong(5, o.futureStockLong());
                statement.setLong(6, o.futureStockShort());
                statement.setLong(7, o.optionIndexCallLong());
                statement.setLong(8, o.optionIndexPutLong());
                statement.setLong(9, o.optionIndexCallShort());
                statement.setLong(10, o.optionIndexPutShort());
                statement.setLong(11, row.optionStockCallLong());
                statement.setLong(12, row.optionStockPutLong());
                statement.setLong(13, row.optionStockCallShort());
                statement.setLong(14, row.optionStockPutShort());
                statement.setLong(15, o.totalLong());
                statement.setLong(16, o.totalShort());
                statement.addBatch();
            }
            int written = 0;
            for (int count : statement.executeBatch()) {
                written += Math.max(count, 0);
            }
            return written;
        } catch (SQLException e) {
            throw new IllegalStateException("participant OI upsert failed", e);
        }
    }

    /** Stores GIFT Nifty quotes; a quote already stored (same contract and exchange time) is kept. Returns new rows. */
    public int insertGiftQuotes(List<GiftQuote> quotes) {
        String sql = "insert into ref.gift_nifty_quote (expiry, exchange_ts, fetched_at, last_price, day_change, "
                + "day_change_pct, contracts_traded) values (?, ?, ?, ?, ?, ?, ?) on conflict (expiry, exchange_ts) do nothing";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (GiftQuote q : quotes) {
                statement.setObject(1, q.expiry());
                statement.setTimestamp(2, Timestamp.from(q.exchangeTime()));
                statement.setTimestamp(3, Timestamp.from(q.fetchedAt()));
                statement.setDouble(4, q.last());
                statement.setObject(5, Double.isFinite(q.change()) ? q.change() : null);
                statement.setObject(6, Double.isFinite(q.changePct()) ? q.changePct() : null);
                statement.setLong(7, q.contractsTraded());
                statement.addBatch();
            }
            int written = 0;
            for (int count : statement.executeBatch()) {
                written += Math.max(count, 0);
            }
            return written;
        } catch (SQLException e) {
            throw new IllegalStateException("GIFT Nifty quote insert failed", e);
        }
    }

    @Override
    public List<ParticipantOi> participantOi(LocalDate session, int sessions) {
        String sql = "select session_date, client_type, future_index_long, future_index_short, future_stock_long, "
                + "future_stock_short, option_index_call_long, option_index_put_long, option_index_call_short, "
                + "option_index_put_short, total_long, total_short from ref.participant_oi where session_date in "
                + "(select distinct session_date from ref.participant_oi where session_date < ? order by session_date desc limit ?) "
                + "order by session_date desc, client_type";
        List<ParticipantOi> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, session);
            statement.setInt(2, sessions);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(new ParticipantOi(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                            rs.getLong(10), rs.getLong(11), rs.getLong(12)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("participant OI read failed", e);
        }
        return out;
    }

    /**
     * Quotes fetched from 09:00 IST four calendar days before {@code session} (so a Monday sees Friday's Indian hours,
     * the reference for the overnight move) to the end of the session; the feature uses the latest quote at or before
     * its time and reports how old it is.
     */
    @Override
    public List<GiftQuote> giftNiftyQuotes(LocalDate session) {
        String sql = "select exchange_ts, fetched_at, expiry, last_price, day_change, day_change_pct, contracts_traded "
                + "from ref.gift_nifty_quote where fetched_at >= ? and fetched_at < ? order by fetched_at, expiry";
        List<GiftQuote> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(session.minusDays(4).atTime(9, 0).atZone(MarketTime.IST).toInstant()));
            statement.setTimestamp(2, Timestamp.from(session.plusDays(1).atStartOfDay(MarketTime.IST).toInstant()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    double change = rs.getDouble(5);
                    boolean noChange = rs.wasNull();
                    double pct = rs.getDouble(6);
                    boolean noPct = rs.wasNull();
                    out.add(new GiftQuote(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(),
                            rs.getObject(3, LocalDate.class), rs.getDouble(4), noChange ? Double.NaN : change,
                            noPct ? Double.NaN : pct, rs.getLong(7)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("GIFT Nifty quote read failed", e);
        }
        return out;
    }

    public static Instant dayStart(LocalDate session) {
        return session.atStartOfDay(MarketTime.IST).toInstant();
    }
}
