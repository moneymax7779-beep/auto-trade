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
                TickCursor ticks = new TickCursor(source.getConnection(), session, underlying, verifyHashes);
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

    /** Base for cursors that hold a server-side result set inside a read-only transaction. */
    private abstract static class JdbcCursor implements EventCursor {

        private final Connection connection;
        private final PreparedStatement statement;
        protected final ResultSet rows;
        private MarketEvent head;

        JdbcCursor(Connection connection, String sql, Binder binder) throws SQLException {
            this.connection = connection;
            PreparedStatement prepared = null;
            try {
                connection.setAutoCommit(false);
                prepared = connection.prepareStatement(sql);
                prepared.setFetchSize(FETCH_SIZE);
                binder.bind(prepared);
                rows = prepared.executeQuery();
                statement = prepared;
            } catch (SQLException e) {
                if (prepared != null) {
                    prepared.close();
                }
                connection.close();
                throw e;
            }
        }

        @Override
        public boolean advance() throws SQLException {
            if (!rows.next()) {
                head = null;
                return false;
            }
            head = map();
            return true;
        }

        abstract MarketEvent map() throws SQLException;

        @Override
        public MarketEvent head() {
            return head;
        }

        @Override
        public void close() {
            try {
                rows.close();
                statement.close();
                connection.rollback();
            } catch (SQLException ignored) {
                // read-only cursor; nothing to undo
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // pool handles broken connections
                }
            }
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static final class TickCursor extends JdbcCursor {

        private final ZtTickParser parser;
        private final boolean verifyHashes;

        TickCursor(Connection connection, LocalDate session, String underlying, boolean verifyHashes)
                throws SQLException {
            super(connection, ZtQueries.TICKS_BY_SEQUENCE, statement -> {
                statement.setString(1, ZtSourceKeys.underlyingKey(underlying));
                statement.setObject(2, session);
            });
            this.parser = new ZtTickParser(underlying);
            this.verifyHashes = verifyHashes;
        }

        @Override
        MarketEvent map() throws SQLException {
            SourceTickRow row = ZtQueries.tickRow(rows);
            if (verifyHashes) {
                parser.verifiedHash(row);
            }
            return parser.parse(row);
        }
    }

    private static final class PhaseCursor extends JdbcCursor {

        private final String underlying;

        PhaseCursor(Connection connection, LocalDate session, String underlying) throws SQLException {
            super(connection, ZtQueries.SESSION_PHASES, statement -> {
                statement.setString(1, ZtSourceKeys.underlyingKey(underlying));
                statement.setObject(2, MarketTime.sessionStart(session).atOffset(ZoneOffset.UTC));
                statement.setObject(3, MarketTime.sessionEnd(session).atOffset(ZoneOffset.UTC));
            });
            this.underlying = underlying;
        }

        @Override
        MarketEvent map() throws SQLException {
            return ZtQueries.sessionPhaseRow(rows, underlying).event();
        }
    }
}
