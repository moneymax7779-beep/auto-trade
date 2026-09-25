package com.autotrade.core.history;

import java.time.LocalDate;

/** One session's high, low and close of an underlying's spot value over continuous trading. */
public record DailyBar(LocalDate session, double open, double high, double low, double close) {
}
