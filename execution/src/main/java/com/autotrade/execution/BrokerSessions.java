package com.autotrade.execution;

import java.util.Optional;

import com.autotrade.account.BrokerAccount;

/**
 * Where a route that reads the real broker (DRY_RUN with {@code orders.dry_run_market: BROKER}) finds an account's
 * session: its app's key and the day's access token from the daily login. Read on every call, so a login during the
 * day takes effect at once and an expired session is noticed at once. Never logged.
 */
public interface BrokerSessions {

    /** No real sessions: such a route's reads fail as a lost session (entries refused BROKER_SESSION). */
    BrokerSessions NONE = account -> Optional.empty();

    /** An app key and an access token; {@link #toString()} never shows the token. */
    record Access(String apiKey, String accessToken) {

        @Override
        public String toString() {
            return "Access[apiKey=" + apiKey + ", accessToken=<redacted>]";
        }
    }

    /** The account's session now, or empty when it has none (no login today, or it expired). */
    Optional<Access> access(BrokerAccount account);
}
