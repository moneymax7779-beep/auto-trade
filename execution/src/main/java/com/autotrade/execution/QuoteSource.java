package com.autotrade.execution;

import java.time.Instant;
import java.time.LocalDate;

/** The best ask of one option contract at a moment; NaN when unknown (replay: stored ticks; live: the broker, L2). */
@FunctionalInterface
public interface QuoteSource {

    double askAt(LocalDate day, String underlying, long token, Instant at);
}
