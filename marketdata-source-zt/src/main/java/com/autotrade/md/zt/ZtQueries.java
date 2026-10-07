package com.autotrade.md.zt;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.autotrade.core.event.SessionPhaseEvent;
import com.autotrade.md.store.SessionPhaseRow;

/** SQL against zt-tiger-v2's tables and the row mappings shared by the cloner and direct replay. */
public final class ZtQueries {

    /** Params: underlying key, session date. Capture order equals receipt order to within milliseconds. */
    public static final String TICKS_BY_SEQUENCE = "select sequence_number, tick_type, instrument_token, symbol, "
            + "exchange_timestamp_ms, received_at, contract_expiry, contract_lot_size, exchange_segment, "
            + "instrument_type, quote_source, analytics_complete, depth_complete, payload_hash, payload_json "
            + "from market_tick_records where underlying_key = ? and session_date = ? order by sequence_number";

    /**
     * {@link #TICKS_BY_SEQUENCE} for one range of sequence numbers. A whole session in one sorted query needs
     * gigabytes of temporary space (the payloads ride along in the sort), more than zt-tiger-v2's temp_file_limit
     * allows; a page of sequence numbers sorts within it. Params: underlying key, session date, from (incl.), to (excl.).
     */
    public static final String TICKS_BY_SEQUENCE_PAGE = "select sequence_number, tick_type, instrument_token, symbol, "
            + "exchange_timestamp_ms, received_at, contract_expiry, contract_lot_size, exchange_segment, "
            + "instrument_type, quote_source, analytics_complete, depth_complete, payload_hash, payload_json "
            + "from market_tick_records where underlying_key = ? and session_date = ? and sequence_number >= ? "
            + "and sequence_number < ? order by sequence_number";

    /** The sequence-number bounds of a session's ticks. Params: underlying key, session date. */
    public static final String TICK_SEQUENCE_BOUNDS = "select min(sequence_number), max(sequence_number) "
            + "from market_tick_records where underlying_key = ? and session_date = ?";

    /** Params: underlying key, session date. */
    public static final String TICK_COUNTS = "select tick_type, count(*), min(sequence_number), max(sequence_number) "
            + "from market_tick_records where underlying_key = ? and session_date = ? group by tick_type";

    public static final String SESSION_PHASE_WHERE = " from market_session_price_events where underlying_key = ? "
            + "and event_time_utc >= ? and event_time_utc < ?";

    /** Params: underlying key, session start, session end (timestamptz). */
    public static final String SESSION_PHASES = "select received_at_utc, event_time_utc, session_phase, "
            + "price_semantics, event_time_phase, received_time_phase, price, official_final_proven, "
            + "underlying_continuously_tradable, source, idempotency_key" + SESSION_PHASE_WHERE
            + " order by coalesce(received_at_utc, event_time_utc), id";

    /** Sessions with ticks, with row counts. Reads the whole session index; run off-hours. */
    public static final String SESSIONS = "select session_date, underlying_key, count(*) from market_tick_records "
            + "group by 1, 2 order by 1, 2";

    private ZtQueries() {
    }

    /** Maps a row of {@link #TICKS_BY_SEQUENCE}. */
    public static SourceTickRow tickRow(ResultSet rs) throws SQLException {
        return new SourceTickRow(
                rs.getLong(1), rs.getString(2), nullableLong(rs, 3), rs.getString(4), nullableLong(rs, 5),
                rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDate.class), nullableInt(rs, 8),
                rs.getString(9), rs.getString(10), rs.getString(11), nullableBoolean(rs, 12), nullableBoolean(rs, 13),
                rs.getString(14), rs.getString(15));
    }

    /** Maps a row of {@link #SESSION_PHASES}. A missing receipt time falls back to the event time. */
    public static SessionPhaseRow sessionPhaseRow(ResultSet rs, String underlying) throws SQLException {
        Instant eventTime = rs.getObject(2, OffsetDateTime.class).toInstant();
        OffsetDateTime receivedAt = rs.getObject(1, OffsetDateTime.class);
        SessionPhaseEvent event = new SessionPhaseEvent(receivedAt == null ? eventTime : receivedAt.toInstant(),
                eventTime, underlying, rs.getString(3), rs.getString(4), rs.getDouble(7), rs.getBoolean(8));
        return new SessionPhaseRow(event, rs.getString(5), rs.getString(6), nullableBoolean(rs, 9), rs.getString(10),
                rs.getString(11));
    }

    public static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public static Double nullableDouble(ResultSet rs, int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public static Boolean nullableBoolean(ResultSet rs, int column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
