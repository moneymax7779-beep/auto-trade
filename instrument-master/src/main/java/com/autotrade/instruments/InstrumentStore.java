package com.autotrade.instruments;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/** Saves and loads contract-master snapshots in {@code ref.instrument}. */
public final class InstrumentStore {

    private final DataSource dataSource;

    public InstrumentStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Stores a snapshot; loading the same file twice for the same date is a no-op that returns the
     * existing snapshot id.
     */
    public long save(LocalDate snapshotDate, String source, Path file, List<Instrument> instruments)
            throws SQLException, IOException {
        String sha = sha256(file);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> existing = existing(connection, snapshotDate, source, sha);
                if (existing.isPresent()) {
                    connection.rollback();
                    return existing.get();
                }
                long id;
                try (PreparedStatement statement = connection.prepareStatement(
                        "insert into ref.instrument_snapshot (snapshot_date, source, file_sha256, instruments) "
                                + "values (?, ?, ?, ?) returning id")) {
                    statement.setObject(1, snapshotDate);
                    statement.setString(2, source);
                    statement.setString(3, sha);
                    statement.setInt(4, instruments.size());
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "insert into ref.instrument (snapshot_id, instrument_key, exchange_token, segment, exchange, "
                                + "type, underlying, underlying_key, trading_symbol, expiry, strike, lot_size, "
                                + "tick_size, freeze_quantity, weekly) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (Instrument instrument : instruments) {
                        statement.setLong(1, id);
                        statement.setString(2, instrument.instrumentKey());
                        statement.setString(3, instrument.exchangeToken());
                        statement.setString(4, instrument.segment());
                        statement.setString(5, instrument.exchange());
                        statement.setString(6, instrument.type());
                        statement.setString(7, instrument.underlying());
                        statement.setString(8, instrument.underlyingKey());
                        statement.setString(9, instrument.tradingSymbol());
                        statement.setObject(10, instrument.expiry());
                        statement.setDouble(11, instrument.strike());
                        statement.setInt(12, instrument.lotSize());
                        statement.setDouble(13, instrument.tickSize());
                        statement.setLong(14, instrument.freezeQuantity());
                        statement.setBoolean(15, instrument.weekly());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * The newest snapshot taken on or before {@code date} per exchange file family (the source is
     * {@code UPSTOX:NSE-<date>.json.gz} or {@code UPSTOX:BSE-<date>.json.gz}; grouping on the whole string returned
     * every day's snapshot at once, so the master held each contract once per day loaded).
     */
    public InstrumentMaster latest(LocalDate date) throws SQLException {
        String sql = "select i.instrument_key, i.exchange_token, i.segment, i.exchange, i.type, i.underlying, "
                + "i.underlying_key, i.trading_symbol, i.expiry, i.strike, i.lot_size, i.tick_size, i.freeze_quantity, "
                + "i.weekly from ref.instrument i join ref.instrument_snapshot s on s.id = i.snapshot_id "
                + "where s.id in (select distinct on (split_part(source, '-', 1)) id from ref.instrument_snapshot "
                + "where snapshot_date <= ? order by split_part(source, '-', 1), snapshot_date desc, id desc)";
        List<Instrument> instruments = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, date);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    instruments.add(new Instrument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getObject(9, LocalDate.class), rs.getDouble(10), rs.getInt(11), rs.getDouble(12),
                            rs.getLong(13), rs.getBoolean(14)));
                }
            }
        }
        return new InstrumentMaster(instruments);
    }

    private static Optional<Long> existing(Connection connection, LocalDate date, String source, String sha)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from ref.instrument_snapshot where snapshot_date = ? and source = ? and file_sha256 = ?")) {
            statement.setObject(1, date);
            statement.setString(2, source);
            statement.setString(3, sha);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
