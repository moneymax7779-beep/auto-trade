package com.autotrade.core.history;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * One earlier session's nearest-expiry ATM implied volatility per IST minute (fraction, 0.12 = 12%).
 * {@code expiryDay} is true when the session was the nearest contract's expiry day, whose IV is not
 * comparable with other days (near-zero time value inflates it).
 */
public record IvSession(LocalDate session, boolean expiryDay, Map<LocalTime, Double> atmIvByMinute) {
}
