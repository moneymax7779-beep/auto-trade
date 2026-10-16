package com.autotrade.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * One writer per broker account (docs/LIVE-TRADING-PLAN.md section 1). A Postgres advisory lock for the account, held
 * on a connection kept open for as long as the worker trades the account, plus exec.account_lease: each takeover raises
 * the epoch by one and the worker's orders carry it (fencing). If the connection drops, Postgres releases the lock; a
 * later heartbeat that no longer finds this epoch tells the worker it lost the account, and it stops trading it.
 */
public final class AccountLease implements AutoCloseable {

    private static final int LOCK_CLASS = 7101;            // advisory-lock key space of the execution workers

    private final Connection connection;
    private final long accountId;
    private final long epoch;
    private boolean lost;

    private AccountLease(Connection connection, long accountId, long epoch) {
        this.connection = connection;
        this.accountId = accountId;
        this.epoch = epoch;
    }

    /** The lease when no other worker holds the account; empty otherwise (the connection is not kept). */
    public static Optional<AccountLease> tryAcquire(DataSource dataSource, long accountId, String owner) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(true);
            try (PreparedStatement lock = connection.prepareStatement("select pg_try_advisory_lock(?, ?)")) {
                lock.setInt(1, LOCK_CLASS);
                lock.setInt(2, Math.toIntExact(accountId));
                try (ResultSet rs = lock.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        connection.close();
                        return Optional.empty();
                    }
                }
            }
            try (PreparedStatement upsert = connection.prepareStatement("insert into exec.account_lease (broker_account_id, "
                    + "owner, epoch, acquired_at, heartbeat_at) values (?, ?, 1, now(), now()) on conflict (broker_account_id) "
                    + "do update set owner = excluded.owner, epoch = exec.account_lease.epoch + 1, acquired_at = now(), "
                    + "heartbeat_at = now() returning epoch")) {
                upsert.setLong(1, accountId);
                upsert.setString(2, owner);
                try (ResultSet rs = upsert.executeQuery()) {
                    rs.next();
                    return Optional.of(new AccountLease(connection, accountId, rs.getLong(1)));
                }
            }
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    public long epoch() {
        return epoch;
    }

    public long accountId() {
        return accountId;
    }

    /** Renews the lease; false when it is lost (the connection dropped, or another worker took a newer epoch). */
    public synchronized boolean heartbeat() {
        if (lost) {
            return false;
        }
        try (PreparedStatement beat = connection.prepareStatement("update exec.account_lease set heartbeat_at = now() "
                + "where broker_account_id = ? and epoch = ?")) {
            beat.setLong(1, accountId);
            beat.setLong(2, epoch);
            lost = beat.executeUpdate() != 1;
        } catch (SQLException e) {
            lost = true;
        }
        return !lost;
    }

    @Override
    public synchronized void close() {
        try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?, ?)")) {
            unlock.setInt(1, LOCK_CLASS);
            unlock.setInt(2, Math.toIntExact(accountId));
            unlock.executeQuery().close();
        } catch (SQLException ignored) {
            // a dropped connection has released the lock already
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing left to release
        }
        lost = true;
    }
}
