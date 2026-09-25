package com.autotrade.md.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

/** research.run bookkeeping. JSON arguments are passed as already-serialised strings. */
public final class Runs {

    private final DataSource dataSource;

    public Runs(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public long start(String kind, String codeVersion, String source, List<LocalDate> sessions,
                      List<String> underlyings, String configJson) throws SQLException {
        String sql = "insert into research.run (kind, status, code_version, source, sessions, underlyings, config) "
                + "values (?, 'RUNNING', ?, ?, ?, ?, ?::jsonb) returning id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kind);
            statement.setString(2, codeVersion);
            statement.setString(3, source);
            statement.setArray(4, connection.createArrayOf("date", sessions.toArray()));
            statement.setArray(5, connection.createArrayOf("text", underlyings.toArray()));
            statement.setString(6, configJson);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void finish(long id, String notesJson) throws SQLException {
        update(id, "DONE", notesJson, null);
    }

    public void fail(long id, String error) throws SQLException {
        update(id, "FAILED", "{}", error);
    }

    private void update(long id, String status, String notesJson, String error) throws SQLException {
        String sql = "update research.run set status = ?, finished_at = now(), notes = ?::jsonb, error = ? where id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, notesJson);
            statement.setString(3, error);
            statement.setLong(4, id);
            statement.executeUpdate();
        }
    }
}
