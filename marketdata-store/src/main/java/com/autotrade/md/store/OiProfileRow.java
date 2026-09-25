package com.autotrade.md.store;

import java.time.Instant;
import java.time.LocalDate;

/** An option-chain OI profile; {@code profileJson} must be valid JSON. */
public record OiProfileRow(
        String underlying,
        String sourceId,
        LocalDate expiry,
        Instant receivedAt,
        Instant availableAt,
        String membershipHash,
        String payloadHash,
        String profileHash,
        String profileJson) {
}
