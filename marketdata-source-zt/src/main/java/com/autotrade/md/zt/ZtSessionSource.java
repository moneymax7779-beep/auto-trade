package com.autotrade.md.zt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.EventCursor;
import com.autotrade.md.store.EventStreams;
import com.autotrade.md.store.ReorderingCursor;

/**
 * Replays a session straight from zt-tiger-v2's database, without copying it. The connection must
 * be read-only. Each underlying's ticks are read in capture order and re-sorted within a small window
 * into receipt order; closing-auction phase events are merged in by receipt time.
 */
public final class ZtSessionSource implements SessionEventSource {

    public static final Duration REORDER_WINDOW = Duration.ofSeconds(2);
    private static final int FETCH_SIZE = 10_000;

    private final DataSource source;
    private final boolean verifyHashes;
    private final Map<String, Long> unknownPayloadKeys = new TreeMap<>();

    /**
     * @param verifyHashes check every payload against its stored SHA-256 (slower; catches corrupted rows)
     */
    public ZtSessionSource(DataSource source, boolean verifyHashes) {
        this.source = source;
        this.verifyHashes = verifyHashes;
    }

    @Override
    public String name() {
        return "zt-tiger-v2";
    }

    /** Payload keys the parser did not recognise in the last replay, by tick type (new feed fields show up here). */
    public Map<String, Long> unknownPayloadKeys() {
        return unknownPayloadKeys;
    }

    @Override
    public ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink)
            throws Exception {
        unknownPayloadKeys.clear();
        List<ReorderingCursor> cursors = new ArrayList<>();
        List<TickCursor> tickCursors = new ArrayList<>();
        try {
            for (String underlying : underlyings) {
                TickCursor ticks = new TickCursor(source, session, underlying, verifyHashes);
                tickCursors.add(ticks);
                cursors.add(new ReorderingCursor(ticks, REORDER_WINDOW));
                cursors.add(new ReorderingCursor(new PhaseCursor(source.getConnection(), session, underlying),
                        REORDER_WINDOW));
            }
        } catch (SQLException | RuntimeException e) {
            cursors.forEach(ReorderingCursor::close);
            throw e;
        }
        long delivered = EventStreams.merge(cursors, sink);
        long late = cursors.stream().mapToLong(ReorderingCursor::lateEvents).sum();
        for (TickCursor ticks : tickCursors) {
            ticks.parser.unknownKeys().forEach((key, count) -> unknownPayloadKeys.merge(key, count, Long::sum));
        }
        return new ReplayResult(delivered, late);
    }

    /**
     * A session's ticks of one underlying in capture order, read in pages of {@link #PAGE_SEQUENCES} sequence numbers.
     * Each page is read on its own short connection in autocommit and buffered, so nothing is held open between pages:
     * a single sorted query of a whole session exceeds zt-tiger-v2's temp_file_limit, and a transaction held open while
     * the other underlying's page is read was closed by its idle-in-transaction timeout (24 Sep replays, 7 Oct).
     */
    private static final class TickCursor implements EventCursor {

        static final long PAGE_SEQUENCES = 20_000;

        private final DataSource source;
        private final String underlyingKey;
        private final LocalDate session;
        private final ZtTickParser parser;
        private final boolean verifyHashes;
        private final long last;
        private long next;
        private final java.util.ArrayDeque<MarketEvent> page = new java.util.ArrayDeque<>();
        private MarketEvent head;

        TickCursor(DataSource source, LocalDate session, String underlying, boolean verifyHashes) throws SQLException {
            this.source = source;
            this.underlyingKey = ZtSourceKeys.underlyingKey(underlying);
            this.session = session;
            this.parser = new ZtTickParser(underlying);
            this.verifyHashes = verifyHashes;
            long first = 0;
            long max = -1;
            try (Connection connection = source.getConnection();
                 PreparedStatement bounds = connection.prepareStatement(ZtQueries.TICK_SEQUENCE_BOUNDS)) {
                connection.setAutoCommit(true);
                bounds.setString(1, underlyingKey);
                bounds.setObject(2, session);
                try (ResultSet b = bounds.executeQuery()) {
                    if (b.next() && b.getObject(1) != null) {
                        first = b.getLong(1);
                        max = b.getLong(2);
                    }
                }
            }
            this.next = first;
            this.last = max;
        }

        /** Reads the next non-empty page into memory; false when the session is exhausted. */
        private boolean readNextPage() throws SQLException {
            while (page.isEmpty() && next <= last) {
                try (Connection connection = source.getConnection();
                     PreparedStatement statement = connection.prepareStatement(ZtQueries.TICKS_BY_SEQUENCE_PAGE)) {
                    connection.setAutoCommit(true);
                    statement.setString(1, underlyingKey);
                    statement.setObject(2, session);
                    statement.setLong(3, next);
                    statement.setLong(4, next + PAGE_SEQUENCES);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            SourceTickRow row = ZtQueries.tickRow(rows);
                            if (verifyHashes) {
                                parser.verifiedHash(row);
                            }
                            page.add(parser.parse(row));
                        }
                    }
                }
                next += PAGE_SEQUENCES;
            }
            return !page.isEmpty();
        }

        @Override
        public boolean advance() throws SQLException {
            if (page.isEmpty() && !readNextPage()) {
                head = null;
                return false;
            }
            head = page.poll();
            return true;
        }

        @Override
        public MarketEvent head() {
            return head;
        }

        @Override
        public void close() {
            page.clear();
        }
    }

    /**
     * The session's phase events of one underlying (a few rows a day), read at once and the connection released, so a
     * replay of two underlyings holds two zt-tiger-v2 connections (the tick readers), not four.
     */
    private static final class PhaseCursor implements EventCursor {

        private final java.util.Iterator<MarketEvent> events;
        private MarketEvent head;

        PhaseCursor(Connection connection, LocalDate session, String underlying) throws SQLException {
            List<MarketEvent> list = new ArrayList<>();
            try (connection; PreparedStatement statement = connection.prepareStatement(ZtQueries.SESSION_PHASES)) {
                connection.setAutoCommit(false);
                statement.setString(1, ZtSourceKeys.underlyingKey(underlying));
                statement.setObject(2, MarketTime.sessionStart(session).atOffset(ZoneOffset.UTC));
                statement.setObject(3, MarketTime.sessionEnd(session).atOffset(ZoneOffset.UTC));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        list.add(ZtQueries.sessionPhaseRow(rows, underlying).event());
                    }
                }
                connection.rollback();
            }
            this.events = list.iterator();
        }

        @Override
        public boolean advance() {
            head = events.hasNext() ? events.next() : null;
            return head != null;
        }

        @Override
        public MarketEvent head() {
            return head;
        }

        @Override
        public void close() {
            // nothing held
        }
    }
}
