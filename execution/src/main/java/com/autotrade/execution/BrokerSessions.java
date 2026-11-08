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

    /**
     * An app key and an access token, plus what else a broker's session carries ({@code extra}: Kotak's sid and base URL;
     * empty for Kite); {@link #toString()} never shows the token or the extras' values.
     */
    record Access(String apiKey, String accessToken, java.util.Map<String, String> extra) {

        public Access {
            extra = extra == null ? java.util.Map.of() : java.util.Map.copyOf(extra);
        }

        public Access(String apiKey, String accessToken) {
            this(apiKey, accessToken, java.util.Map.of());
        }

        @Override
        public String toString() {
            return "Access[apiKey=" + apiKey + ", accessToken=<redacted>, extra=" + extra.keySet() + "]";
        }
    }

    /** The account's session now, or empty when it has none (no login today, or it expired). */
    Optional<Access> access(BrokerAccount account);
}
