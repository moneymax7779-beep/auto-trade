package com.autotrade.md.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;

/**
 * Records a live feed into auto-trade's own md.* tables, so live sessions stay replayable after
 * zt-tiger-v2 has rotated them out, and data only the live feed carries (per-stock auction data,
 * futures book totals, India VIX) is kept.
 *
 * <p>One CAPTURE manifest per session and underlying (INDIA_VIX included), ACTIVE from the start and
 * appended to by later runs of the same day (a restart does not hide the morning). Events are
 * buffered in memory and written with COPY on {@link #flush()}; call it every few seconds.
 */
public final class LiveCapture implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveCapture.class);
    private static final String KIND = "CAPTURE";

    private final DataSource dataSource;
    private final LocalDate session;
    private final String sourceName;
    private final String toolVersion;
    private final MdRowEncoder encoder = new MdRowEncoder();
    private final Map<String, Long> manifests = new HashMap<>();
    private Map<MdTable, ByteArrayOutputStream> buffers = new EnumMap<>(MdTable.class);
    private final Map<String, Long> counts = new TreeMap<>();
    private long written;
    private long failures;

    public LiveCapture(DataSource dataSource, LocalDate session, String sourceName, String toolVersion) {
        this.dataSource = dataSource;
        this.session = session;
        this.sourceName = sourceName;
        this.toolVersion = toolVersion;
    }

    /** Buffers one event (called on the feed thread). Never throws. */
    public synchronized void accept(MarketEvent event) {
        try {
            long manifest = manifest(event.underlying());
            MdTable table;
            byte[] row;
            switch (event) {
                case IndexTick tick -> {
                    table = MdTable.INDEX_TICK;
                    row = encoder.index(tick, meta(manifest, tick.underlying(), "INDEX"));
                }
                case FutureTick tick -> {
                    table = MdTable.FUTURE_TICK;
                    row = encoder.future(tick, meta(manifest, tick.underlying(), "FO"));
                }
                case OptionTick tick -> {
                    table = MdTable.OPTION_TICK;
                    row = encoder.option(tick, meta(manifest, tick.underlying(), "FO"));
                }
                case ConstituentTick tick -> {
                    table = MdTable.CONSTITUENT_TICK;
                    row = encoder.constituent(tick, meta(manifest, tick.underlying(), "EQ"));
                }
                case SessionPhaseEvent phase -> {
                    table = MdTable.SESSION_PHASE_EVENT;
                    row = encoder.sessionPhase(new SessionPhaseRow(phase, null, null, null, sourceName,
                            sourceName + ":" + phase.underlying() + ":" + phase.sessionPhase() + ":"
                                    + phase.receivedAt().toEpochMilli()), manifest, session);
                }
                case AuctionTick tick -> {
                    table = MdTable.AUCTION_TICK;
                    row = encoder.auction(tick, manifest, session);
                }
            }
            buffers.computeIfAbsent(table, t -> new ByteArrayOutputStream(1 << 16)).writeBytes(row);
            counts.merge(table.name(), 1L, Long::sum);
        } catch (Exception e) {
            if (failures++ % 1000 == 0) {
                log.warn("live capture could not buffer an event ({} so far): {}", failures, e.getMessage());
            }
        }
    }

    /** Writes the buffered rows (one transaction). Never throws; a failed batch is logged and dropped. */
    public void flush() {
        Map<MdTable, ByteArrayOutputStream> batch;
        synchronized (this) {
            if (buffers.isEmpty()) {
                return;
            }
            batch = buffers;
            buffers = new EnumMap<>(MdTable.class);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            long rows = 0;
            for (Map.Entry<MdTable, ByteArrayOutputStream> entry : batch.entrySet()) {
                rows += CopyLoader.copy(connection, entry.getKey(), new ByteArrayInputStream(entry.getValue().toByteArray()));
            }
            connection.commit();
            written += rows;
        } catch (Exception e) {
            log.error("live capture batch lost: {}", e.getMessage());
        }
    }

    public synchronized Map<String, Long> counts() {
        return Map.copyOf(counts);
    }

    public long written() {
        return written;
    }

    @Override
    public void close() {
        flush();
        StringBuilder loaded = new StringBuilder("{");
        counts().forEach((table, count) -> loaded.append(loaded.length() > 1 ? "," : "").append('"').append(table)
                .append("\":").append(count));
        loaded.append('}');
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("update md.load_manifest set finished_at = now(), "
                     + "loaded_counts = ?::jsonb where id = ?")) {
            for (long id : manifests.values()) {
                update.setString(1, loaded.toString());
                update.setLong(2, id);
                update.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("live capture manifests not finalised: {}", e.getMessage());
        }
        log.info("live capture for {}: {} rows written {}", session, written, counts());
    }

    private RowMeta meta(long manifest, String underlying, String kind) {
        String exchange = "SENSEX".equals(underlying) ? "BSE" : "NSE";
        return new RowMeta(manifest, session, null, exchange + "_" + kind, sourceName);
    }

    /** Today's capture manifest for {@code underlying}: the existing one, else a new ACTIVE one. */
    private long manifest(String underlying) throws SQLException {
        Long id = manifests.get(underlying);
        if (id != null) {
            return id;
        }
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement find = connection.prepareStatement("select id, kind from md.load_manifest "
                    + "where session_date = ? and underlying = ? and status = 'ACTIVE'")) {
                find.setObject(1, session);
                find.setString(2, underlying);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        if (!KIND.equals(rs.getString(2))) {
                            throw new IllegalStateException(underlying + " " + session
                                    + " already has an active " + rs.getString(2) + " load; not capturing over it");
                        }
                        id = rs.getLong(1);
                    }
                }
            }
            if (id == null) {
                try (PreparedStatement insert = connection.prepareStatement("insert into md.load_manifest (kind, "
                        + "session_date, underlying, source_name, source_detail, datasets, status, tool_version) "
                        + "values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?) returning id")) {
                    insert.setString(1, KIND);
                    insert.setObject(2, session);
                    insert.setString(3, underlying);
                    insert.setString(4, sourceName);
                    insert.setString(5, "live feed capture");
                    insert.setArray(6, connection.createArrayOf("text", List.of("ticks", "cas", "auction").toArray()));
                    insert.setString(7, toolVersion);
                    try (ResultSet rs = insert.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
            }
        }
        manifests.put(underlying, id);
        return id;
    }
}
