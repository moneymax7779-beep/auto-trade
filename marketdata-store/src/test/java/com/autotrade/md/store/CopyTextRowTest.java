package com.autotrade.md.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class CopyTextRowTest {

    @Test
    void escapesTextAndWritesNulls() {
        CopyTextRow row = new CopyTextRow();

        String line = new String(row.text("a\tb\\c\nd").nullValue().int64(7L).float64((Double) null).endRow(),
                StandardCharsets.UTF_8);

        assertThat(line).isEqualTo("a\\tb\\\\c\\nd\t\\N\t7\t\\N\n");
    }

    @Test
    void writesTimestampsDatesArraysAndBooleans() {
        CopyTextRow row = new CopyTextRow();

        String line = new String(row
                .timestamp(Instant.parse("2026-09-02T03:47:05.497885Z"))
                .date(LocalDate.of(2026, 9, 8))
                .float64Array(new double[] {88.7, 88.65})
                .int64Array(new long[] {325, 1235})
                .int32Array(new int[0])
                .bool(true)
                .float64(Double.NaN)
                .endRow(), StandardCharsets.UTF_8);

        assertThat(line).isEqualTo("2026-09-02T03:47:05.497885Z\t2026-09-08\t{88.7,88.65}\t{325,1235}\t{}\tt\tNaN\n");
    }

    @Test
    void resetsBetweenRows() {
        CopyTextRow row = new CopyTextRow();
        row.text("first").endRow();

        assertThat(new String(row.text("second").endRow(), StandardCharsets.UTF_8)).isEqualTo("second\n");
    }
}
