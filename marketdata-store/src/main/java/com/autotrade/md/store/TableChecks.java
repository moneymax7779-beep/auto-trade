package com.autotrade.md.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Post-load checks computed from the stored rows of one manifest. */
public final class TableChecks {

    private static final int FETCH_SIZE = 20_000;

    private TableChecks() {
    }

    public static long count(Connection connection, MdTable table, long manifestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from " + table.qualifiedName() + " where manifest_id = ?")) {
            statement.setLong(1, manifestId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Recomputes the sequence digest of a tick table. The connection must not be in autocommit mode. */
    public static SequenceDigest.Result digest(Connection connection, MdTable table, long manifestId)
            throws SQLException {
        if (!table.isTick()) {
            throw new IllegalArgumentException(table + " has no source sequence");
        }
        SequenceDigest digest = new SequenceDigest();
        try (PreparedStatement statement = connection.prepareStatement(
                "select src_seq, src_hash64 from " + table.qualifiedName() + " where manifest_id = ? order by src_seq")) {
            statement.setFetchSize(FETCH_SIZE);
            statement.setLong(1, manifestId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    digest.add(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        return digest.result();
    }
}
