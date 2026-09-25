package com.autotrade.md.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

import javax.sql.DataSource;

import org.postgresql.PGConnection;

/**
 * Buffers feature snapshots and bulk-loads them into feat.snapshot with COPY. The caller supplies
 * the features already serialised as a JSON object.
 */
public final class SnapshotWriter implements AutoCloseable {

    private static final String COPY = "COPY feat.snapshot (run_id, session_date, underlying, snap_time, phase, spot, "
            + "features) FROM STDIN";
    private static final int FLUSH_BYTES = 4 << 20;

    private final DataSource dataSource;
    private final long runId;
    private final CopyTextRow row = new CopyTextRow();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private long written;

    public SnapshotWriter(DataSource dataSource, long runId) {
        this.dataSource = dataSource;
        this.runId = runId;
    }

    public void add(LocalDate session, String underlying, Instant time, String phase, double spot, String featuresJson) {
        byte[] line = row.int64(runId).date(session).text(underlying).timestamp(time).text(phase)
                .float64(Double.isFinite(spot) ? spot : null).text(featuresJson).endRow();
        buffer.writeBytes(line);
        written++;
        if (buffer.size() >= FLUSH_BYTES) {
            flush();
        }
    }

    public long written() {
        return written;
    }

    public void flush() {
        if (buffer.size() == 0) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY, new ByteArrayInputStream(buffer.toByteArray()));
            buffer.reset();
        } catch (SQLException e) {
            throw new IllegalStateException("snapshot write failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        flush();
    }
}
