package com.autotrade.md.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/** Data access for md.load_manifest. JSON arguments are passed as already-serialised strings. */
public final class LoadManifests {

    private static final String COLUMNS = "id, kind, session_date, underlying, source_name, datasets, status, "
            + "tool_version, started_at, finished_at, loaded_counts::text, error";

    private final DataSource dataSource;

    public LoadManifests(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public long start(String kind, LocalDate sessionDate, String underlying, String sourceName, String sourceDetail,
                      List<String> datasets, String toolVersion) throws SQLException {
        String sql = "insert into md.load_manifest (kind, session_date, underlying, source_name, source_detail, "
                + "datasets, status, tool_version) values (?, ?, ?, ?, ?, ?, 'RUNNING', ?) returning id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kind);
            statement.setObject(2, sessionDate);
            statement.setString(3, underlying);
            statement.setString(4, sourceName);
            statement.setString(5, sourceDetail);
            statement.setArray(6, connection.createArrayOf("text", datasets.toArray()));
            statement.setString(7, toolVersion);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Makes {@code id} the active load for its session and underlying, superseding the previous
     * active one, inside the caller's transaction. Returns the superseded manifest ids.
     */
    public List<Long> activate(Connection transaction, long id, String sourceCounts, String loadedCounts,
                               String sourceDigests, String loadedDigests, String notes) throws SQLException {
        List<Long> superseded = new ArrayList<>();
        String supersede = "update md.load_manifest m set status = 'SUPERSEDED' from md.load_manifest n "
                + "where n.id = ? and m.session_date = n.session_date and m.underlying = n.underlying "
                + "and m.status = 'ACTIVE' and m.id <> n.id returning m.id";
        try (PreparedStatement statement = transaction.prepareStatement(supersede)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    superseded.add(rs.getLong(1));
                }
            }
        }
        String activate = "update md.load_manifest set status = 'ACTIVE', finished_at = now(), "
                + "source_counts = ?::jsonb, loaded_counts = ?::jsonb, source_digests = ?::jsonb, "
                + "loaded_digests = ?::jsonb, notes = ?::jsonb where id = ? and status = 'RUNNING'";
        try (PreparedStatement statement = transaction.prepareStatement(activate)) {
            statement.setString(1, sourceCounts);
            statement.setString(2, loadedCounts);
            statement.setString(3, sourceDigests);
            statement.setString(4, loadedDigests);
            statement.setString(5, notes);
            statement.setLong(6, id);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("manifest " + id + " is not RUNNING");
            }
        }
        return superseded;
    }

    public void fail(long id, String error, String notes) throws SQLException {
        String sql = "update md.load_manifest set status = 'FAILED', finished_at = now(), error = ?, "
                + "notes = ?::jsonb where id = ? and status = 'RUNNING'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, error);
            statement.setString(2, notes);
            statement.setLong(3, id);
            statement.executeUpdate();
        }
    }

    /** Deletes every market-data row written under a manifest (used for superseded and failed loads). */
    public long deleteRows(Connection connection, long manifestId) throws SQLException {
        long deleted = 0;
        for (MdTable table : MdTable.values()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from " + table.qualifiedName() + " where manifest_id = ?")) {
                statement.setLong(1, manifestId);
                deleted += statement.executeUpdate();
            }
        }
        return deleted;
    }

    public Optional<LoadManifest> active(LocalDate sessionDate, String underlying) throws SQLException {
        String sql = "select " + COLUMNS + " from md.load_manifest where session_date = ? and underlying = ? "
                + "and status = 'ACTIVE'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionDate);
            statement.setString(2, underlying);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<LoadManifest> list() throws SQLException {
        String sql = "select " + COLUMNS + " from md.load_manifest order by session_date, underlying, id";
        List<LoadManifest> manifests = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                manifests.add(map(rs));
            }
        }
        return manifests;
    }

    private static LoadManifest map(ResultSet rs) throws SQLException {
        Array datasets = rs.getArray(6);
        return new LoadManifest(
                rs.getLong(1),
                rs.getString(2),
                rs.getObject(3, LocalDate.class),
                rs.getString(4),
                rs.getString(5),
                Arrays.stream((Object[]) datasets.getArray()).map(String::valueOf).toList(),
                rs.getString(7),
                rs.getString(8),
                instant(rs, 9),
                instant(rs, 10),
                rs.getString(11),
                rs.getString(12));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
