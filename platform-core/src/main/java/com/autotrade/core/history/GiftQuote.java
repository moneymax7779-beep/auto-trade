package com.autotrade.core.history;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One GIFT Nifty futures quote (NSE IX, NIFTY FUTIDX) as fetched: the exchange's quote time, the time it was fetched
 * (what point-in-time use filters on), the contract expiry, last price and the exchange's day change against the
 * previous settlement.
 */
public record GiftQuote(Instant exchangeTime, Instant fetchedAt, LocalDate expiry, double last, double change,
                        double changePct, long contractsTraded) {
}
