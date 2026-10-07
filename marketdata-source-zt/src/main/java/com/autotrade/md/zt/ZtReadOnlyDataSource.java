package com.autotrade.md.zt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Connection pool to zt-tiger-v2's database that cannot write: the server is told to make every
 * transaction read-only ({@code default_transaction_read_only=on}) and that is verified on first use.
 */
public final class ZtReadOnlyDataSource {

    public record Settings(String url, String username, String password, String passwordFile, String passwordKey,
                           String applicationName, int poolSize) {
    }

    private ZtReadOnlyDataSource() {
    }

    public static HikariDataSource open(Settings settings) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(withReadOnlyOption(settings.url()));
        config.setUsername(settings.username());
        config.setPassword(resolvePassword(settings));
        config.setReadOnly(true);
        config.setMaximumPoolSize(settings.poolSize());
        // zt-tiger-v2 allows the reader role 6 connections in all: open them on demand, keep at most one idle
        config.setMinimumIdle(Math.min(1, settings.poolSize()));
        config.setPoolName("zt-source");
        config.addDataSourceProperty("ApplicationName", settings.applicationName());
        HikariDataSource dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("show default_transaction_read_only")) {
            rs.next();
            if (!"on".equals(rs.getString(1))) {
                dataSource.close();
                throw new SQLException("source connection is not read-only; refusing to continue");
            }
        }
        return dataSource;
    }

    public static String withReadOnlyOption(String url) {
        String option = "options=-c%20default_transaction_read_only%3Don";
        return url + (url.contains("?") ? "&" : "?") + option;
    }

    /** The password from {@code passwordFile} (key {@code passwordKey}) when that file exists, else {@code password}. */
    public static String resolvePassword(Settings settings) {
        if (settings.passwordFile() != null && !settings.passwordFile().isBlank()) {
            Path file = Path.of(settings.passwordFile());
            if (Files.isReadable(file)) {
                try {
                    for (String line : Files.readAllLines(file)) {
                        int equals = line.indexOf('=');
                        if (equals > 0 && line.substring(0, equals).trim().equals(settings.passwordKey())) {
                            return line.substring(equals + 1).trim();
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read source password file " + file, e);
                }
                throw new IllegalStateException("source password file " + file + " has no " + settings.passwordKey());
            }
        }
        return settings.password();
    }
}
