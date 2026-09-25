package com.autotrade.md.store;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Builds rows in PostgreSQL COPY text format: tab-separated, {@code \N} for null, backslash escapes.
 * One instance is reused for many rows; {@link #endRow()} returns the finished line.
 */
public final class CopyTextRow {

    private final StringBuilder line = new StringBuilder(512);
    private boolean first = true;

    public CopyTextRow nullValue() {
        separator();
        line.append("\\N");
        return this;
    }

    public CopyTextRow text(String value) {
        if (value == null) {
            return nullValue();
        }
        separator();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> line.append("\\\\");
                case '\t' -> line.append("\\t");
                case '\n' -> line.append("\\n");
                case '\r' -> line.append("\\r");
                default -> line.append(c);
            }
        }
        return this;
    }

    public CopyTextRow int64(long value) {
        separator();
        line.append(value);
        return this;
    }

    public CopyTextRow int64(Long value) {
        return value == null ? nullValue() : int64(value.longValue());
    }

    public CopyTextRow int32(Integer value) {
        if (value == null) {
            return nullValue();
        }
        separator();
        line.append(value.intValue());
        return this;
    }

    public CopyTextRow float64(double value) {
        separator();
        appendDouble(value);
        return this;
    }

    public CopyTextRow float64(Double value) {
        return value == null ? nullValue() : float64(value.doubleValue());
    }

    public CopyTextRow bool(boolean value) {
        separator();
        line.append(value ? 't' : 'f');
        return this;
    }

    public CopyTextRow bool(Boolean value) {
        return value == null ? nullValue() : bool(value.booleanValue());
    }

    public CopyTextRow timestamp(Instant value) {
        if (value == null) {
            return nullValue();
        }
        separator();
        line.append(value);
        return this;
    }

    public CopyTextRow date(LocalDate value) {
        if (value == null) {
            return nullValue();
        }
        separator();
        line.append(value);
        return this;
    }

    public CopyTextRow float64Array(double[] values) {
        separator();
        line.append('{');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            appendDouble(values[i]);
        }
        line.append('}');
        return this;
    }

    public CopyTextRow int64Array(long[] values) {
        separator();
        line.append('{');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(values[i]);
        }
        line.append('}');
        return this;
    }

    public CopyTextRow int32Array(int[] values) {
        separator();
        line.append('{');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(values[i]);
        }
        line.append('}');
        return this;
    }

    /** Finishes the row and resets the builder for the next one. */
    public byte[] endRow() {
        line.append('\n');
        byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
        line.setLength(0);
        first = true;
        return bytes;
    }

    private void separator() {
        if (!first) {
            line.append('\t');
        }
        first = false;
    }

    private void appendDouble(double value) {
        if (Double.isNaN(value)) {
            line.append("NaN");
        } else if (Double.isInfinite(value)) {
            line.append(value > 0 ? "Infinity" : "-Infinity");
        } else {
            line.append(value);
        }
    }
}
