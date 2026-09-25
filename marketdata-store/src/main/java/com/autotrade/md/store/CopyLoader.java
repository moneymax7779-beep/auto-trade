package com.autotrade.md.store;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

/** Streams COPY text rows into one md table on the given connection. */
public final class CopyLoader {

    private static final int BUFFER_BYTES = 1 << 16;

    private CopyLoader() {
    }

    public static long copy(Connection connection, MdTable table, InputStream rows) throws SQLException, IOException {
        CopyManager copyManager = connection.unwrap(PGConnection.class).getCopyAPI();
        return copyManager.copyIn(table.copySql(), rows, BUFFER_BYTES);
    }
}
