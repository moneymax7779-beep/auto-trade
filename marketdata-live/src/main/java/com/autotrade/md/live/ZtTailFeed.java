package com.autotrade.md.live;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionPhaseEvent;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.zt.SourceTickRow;
import com.autotrade.md.zt.ZtQueries;
import com.autotrade.md.zt.ZtSourceKeys;
import com.autotrade.md.zt.ZtTickParser;

/**
 * Follows zt-tiger-v2's live capture in its database (read-only). The first poll delivers the
 * session so far, so features warm up from the open; later polls deliver new rows.
 *
 * <p>Rows are found by id. Because a row with a lower id could commit after a higher one, every
 * poll re-reads a short trailing window of ids and drops the ones already delivered, so a late
 * commit is delivered late rather than lost ({@link #lateRows()} counts them).
 */
public final class ZtTailFeed implements LiveFeed {

    private static final String TICKS = "select id, sequence_number, tick_type, instrument_token, symbol, "
            + "exchange_timestamp_ms, received_at, contract_expiry, contract_lot_size, exchange_segment, instrument_type, "
            + "quote_source, analytics_complete, depth_complete, payload_hash, payload_json, underlying_key "
            + "from market_tick_records where id > ? and session_date = ? and underlying_key = any(?) "
            + "order by id limit ?";
    private static final String PHASES = "select id, received_at_utc, event_time_utc, session_phase, price_semantics, "
            + "event_time_phase, received_time_phase, price, official_final_proven, underlying_continuously_tradable, "
            + "source, idempotency_key, underlying_key from market_session_price_events where id > ? "
            + "and underlying_key = any(?) and event_time_utc >= ? and event_time_utc < ? "
            + "and coalesce(received_at_utc, event_time_utc) <= ? order by id";
    private static final int BATCH = 20_000;

    private final DataSource source;
    private final LocalDate session;
    private final List<String> underlyings;
    private final Duration pollInterval;
    private final Duration recheck;
    private final Map<String, String> keyToUnderlying = new HashMap<>();
    private final Map<String, ZtTickParser> parsers = new HashMap<>();
    private final Deque<long[]> recentIds = new ArrayDeque<>(); // [id, seenAtMillis]
    private final Set<Long> delivered = new HashSet<>();
    private volatile boolean stopped;
    private volatile Instant last;
    private long highestId;
    private boolean caughtUp;
    private long highestPhaseId;
    private long lateRows;
    private long rows;

    public ZtTailFeed(DataSource source, LocalDate session, List<String> underlyings, Duration pollInterval,
                      Duration recheck) {
        this.source = source;
        this.session = session;
        this.underlyings = underlyings;
        this.pollInterval = pollInterval;
        this.recheck = recheck;
        for (String underlying : underlyings) {
            keyToUnderlying.put(ZtSourceKeys.underlyingKey(underlying), underlying);
            parsers.put(underlying, new ZtTickParser(underlying));
        }
    }

    @Override
    public String name() {
        return "zt-tiger-v2-tail";
    }

    @Override
    public void run(Consumer<MarketEvent> sink) throws Exception {
        seed();
        while (!stopped) {
            int read = poll(sink);
            if (read < BATCH) {
                Thread.sleep(pollInterval.toMillis());
            }
        }
    }

    /** Positions the tail just before the session's first row (see {@link #startingId()}). */
    void seed() throws SQLException {
        highestId = startingId();
    }

    /**
     * The id just before the session's first row, found through the (underlying, session, sequence)
     * index; before the session has any rows, the table's current maximum id. Either way the polls
     * that follow are short primary-key range scans instead of a walk from id 0.
     */
    long startingId() throws SQLException {
        long first = Long.MAX_VALUE;
        try (Connection connection = source.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("select id from market_tick_records "
                    + "where underlying_key = ? and session_date = ? order by sequence_number limit 1")) {
                for (String key : keyToUnderlying.keySet()) {
                    statement.setString(1, key);
                    statement.setObject(2, session);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (rs.next()) {
                            first = Math.min(first, rs.getLong(1));
                        }
                    }
                }
            }
            if (first != Long.MAX_VALUE) {
                // capture order and id order agree to within a batch; step back to be safe
                return Math.max(0, first - BATCH);
            }
            try (PreparedStatement statement = connection.prepareStatement("select coalesce(max(id), 0) from market_tick_records");
                 ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** One poll: new ticks (with the trailing recheck window) and new session-phase rows. Returns rows read. */
    int poll(Consumer<MarketEvent> sink) throws SQLException {
        long now = System.currentTimeMillis();
        while (!recentIds.isEmpty() && recentIds.peekFirst()[1] < now - recheck.toMillis()) {
            delivered.remove(recentIds.removeFirst()[0]);
        }
        // Re-read the trailing window only once caught up; during the catch-up at start the window
        // could hold a whole batch and the poll would never advance.
        long from = !caughtUp || recentIds.isEmpty() ? highestId : Math.min(highestId, recentIds.peekFirst()[0] - 1);
        int read = 0;
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(TICKS)) {
                statement.setFetchSize(5_000);
                statement.setLong(1, from);
                statement.setObject(2, session);
                statement.setArray(3, connection.createArrayOf("text", keyToUnderlying.keySet().toArray()));
                statement.setInt(4, BATCH);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        read++;
                        long id = rs.getLong(1);
                        if (!delivered.add(id)) {
                            continue;
                        }
                        recentIds.addLast(new long[] {id, now});
                        if (id < highestId) {
                            lateRows++;
                        }
                        highestId = Math.max(highestId, id);
                        SourceTickRow row = new SourceTickRow(rs.getLong(2), rs.getString(3),
                                ZtQueries.nullableLong(rs, 4), rs.getString(5), ZtQueries.nullableLong(rs, 6),
                                rs.getObject(7, LocalDateTime.class), rs.getObject(8, LocalDate.class),
                                ZtQueries.nullableInt(rs, 9), rs.getString(10), rs.getString(11), rs.getString(12),
                                ZtQueries.nullableBoolean(rs, 13), ZtQueries.nullableBoolean(rs, 14), rs.getString(15),
                                rs.getString(16));
                        String underlying = keyToUnderlying.get(rs.getString(17));
                        MarketEvent event = parsers.get(underlying).parse(row);
                        last = event.receivedAt();
                        rows++;
                        sink.accept(event);
                    }
                }
            }
            // Session-phase rows are released only once ticks have reached their time, so a catch-up
            // (which reads the whole day) cannot deliver 15:15 auction rows before the morning's ticks.
            Instant ticksReached = last;
            if (ticksReached != null) try (PreparedStatement statement = connection.prepareStatement(PHASES)) {
                statement.setLong(1, highestPhaseId);
                statement.setArray(2, connection.createArrayOf("text", keyToUnderlying.keySet().toArray()));
                statement.setObject(3, MarketTime.sessionStart(session).atOffset(ZoneOffset.UTC));
                statement.setObject(4, MarketTime.sessionEnd(session).atOffset(ZoneOffset.UTC));
                statement.setObject(5, ticksReached.atOffset(ZoneOffset.UTC));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        highestPhaseId = Math.max(highestPhaseId, rs.getLong(1));
                        String underlying = keyToUnderlying.get(rs.getString(13));
                        sink.accept(phaseRow(rs, underlying));
                    }
                }
            }
            connection.rollback();
        }
        caughtUp = read < BATCH;
        return read;
    }

    private static MarketEvent phaseRow(ResultSet rs, String underlying) throws SQLException {
        Instant eventTime = rs.getObject(3, OffsetDateTime.class).toInstant();
        OffsetDateTime received = rs.getObject(2, OffsetDateTime.class);
        return new SessionPhaseEvent(received == null ? eventTime : received.toInstant(),
                eventTime, underlying, rs.getString(4), rs.getString(5), rs.getDouble(8), rs.getBoolean(9));
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public Instant lastEventTime() {
        return last;
    }

    @Override
    public boolean replay() {
        return false;
    }

    public long lateRows() {
        return lateRows;
    }

    public boolean caughtUp() {
        return caughtUp;
    }

    public long rows() {
        return rows;
    }
}
