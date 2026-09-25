package com.autotrade.tools.clone;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import com.autotrade.md.store.MdTable;

/**
 * Local files holding COPY rows per table, so the source is read once and the target load runs in
 * one transaction afterwards.
 */
final class Spool implements AutoCloseable {

    private static final int BUFFER_BYTES = 1 << 20;

    private final Path directory;
    private final Map<MdTable, OutputStream> outputs = new EnumMap<>(MdTable.class);
    private final Map<MdTable, Long> rows = new EnumMap<>(MdTable.class);
    private long bytes;

    Spool(Path directory) throws IOException {
        this.directory = Files.createDirectories(directory);
    }

    void write(MdTable table, byte[] row) throws IOException {
        OutputStream out = outputs.get(table);
        if (out == null) {
            out = new BufferedOutputStream(Files.newOutputStream(file(table)), BUFFER_BYTES);
            outputs.put(table, out);
        }
        out.write(row);
        rows.merge(table, 1L, Long::sum);
        bytes += row.length;
    }

    /** Closes the writers; afterwards tables can be read back with {@link #open(MdTable)}. */
    void finish() throws IOException {
        for (OutputStream out : outputs.values()) {
            out.close();
        }
    }

    Map<MdTable, Long> rows() {
        return rows;
    }

    long bytes() {
        return bytes;
    }

    InputStream open(MdTable table) throws IOException {
        return Files.newInputStream(file(table));
    }

    private Path file(MdTable table) {
        return directory.resolve(table.name().toLowerCase() + ".copy");
    }

    @Override
    public void close() throws IOException {
        finish();
        for (MdTable table : MdTable.values()) {
            Files.deleteIfExists(file(table));
        }
        try (var remaining = Files.list(directory)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        }
    }
}
