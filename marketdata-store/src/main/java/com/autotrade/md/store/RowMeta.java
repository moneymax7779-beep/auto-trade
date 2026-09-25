package com.autotrade.md.store;

import java.time.LocalDate;

/** Columns a stored row carries beyond the event itself. */
public record RowMeta(long manifestId, LocalDate sessionDate, Long sourceHash64, String segment, String quoteSource) {
}
