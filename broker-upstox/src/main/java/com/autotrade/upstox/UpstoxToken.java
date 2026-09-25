package com.autotrade.upstox;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import com.autotrade.core.time.MarketTime;

/** An Upstox access token. It stays valid until 03:30 IST on the day after it was issued. */
public record UpstoxToken(String accessToken, String userId, Instant issuedAt, Instant expiresAt) {

    private static final LocalTime EXPIRY = LocalTime.of(3, 30);

    public static UpstoxToken issued(String accessToken, String userId, Instant issuedAt) {
        ZonedDateTime issued = issuedAt.atZone(MarketTime.IST);
        ZonedDateTime expiry = issued.toLocalDate().atTime(EXPIRY).atZone(MarketTime.IST);
        if (!expiry.isAfter(issued)) {
            expiry = expiry.plusDays(1);
        }
        return new UpstoxToken(accessToken, userId, issuedAt, expiry.toInstant());
    }

    public boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "UpstoxToken[user=" + userId + ", expires=" + expiresAt + "]";
    }
}
