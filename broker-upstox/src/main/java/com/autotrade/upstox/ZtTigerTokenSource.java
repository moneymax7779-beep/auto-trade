package com.autotrade.upstox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import javax.sql.DataSource;

import com.autotrade.core.time.MarketTime;

/**
 * Reads the Upstox access token of zt-tiger-v2's primary Upstox account from its database
 * ({@code trading_accounts}), read-only, so the account holder signs in once (in zt-tiger-v2) for both
 * applications. The token is kept in memory only and never written or logged. auto-trade never logs
 * in with zt-tiger-v2's app: a new login would invalidate the token zt-tiger-v2 is using.
 */
public final class ZtTigerTokenSource {

    private static final String SQL = "select access_token, broker_user_id, token_acquired_at, token_expiry "
            + "from trading_accounts where broker_type = 'UPSTOX' and active and primary_account "
            + "and access_token is not null and access_token <> '' order by token_acquired_at desc nulls last limit 1";

    private final DataSource ztDatabase;

    public ZtTigerTokenSource(DataSource ztDatabase) {
        this.ztDatabase = ztDatabase;
    }

    /** The token if zt-tiger-v2 holds one that is still valid at {@code now}. */
    public Optional<UpstoxToken> current(Instant now) {
        try (Connection connection = ztDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            Timestamp acquired = rs.getTimestamp(3);
            Timestamp expiry = rs.getTimestamp(4);
            Instant issuedAt = acquired == null ? now : MarketTime.fromIstLocal(acquired.toLocalDateTime());
            UpstoxToken token = UpstoxToken.issued(rs.getString(1), rs.getString(2), issuedAt);
            if (expiry != null) {
                LocalDateTime local = expiry.toLocalDateTime();
                Instant stated = MarketTime.fromIstLocal(local);
                if (stated.isBefore(token.expiresAt())) {
                    token = new UpstoxToken(token.accessToken(), token.userId(), token.issuedAt(), stated);
                }
            }
            return token.validAt(now) ? Optional.of(token) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("could not read zt-tiger-v2's Upstox token", e);
        }
    }
}
