package com.autotrade.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Read-only connection to the zt-tiger-v2 database, created only when a command needs it.
 * Read-only is enforced by the server ({@code default_transaction_read_only=on}) and checked on
 * first use, so no statement from this tool can change that instance.
 */
@Component
public class SourceDatabase implements AutoCloseable {

    private final ToolProperties.Source properties;
    private HikariDataSource dataSource;

    public SourceDatabase(ToolProperties properties) {
        this.properties = properties.source();
    }

    public String name() {
        return properties.name();
    }

    /** The JDBC URL without credentials, for manifests and logs. */
    public String redactedDetail() {
        return properties.url() + " user=" + properties.username() + " (read-only)";
    }

    public synchronized DataSource dataSource() throws SQLException {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(withReadOnlyOption(properties.url()));
            config.setUsername(properties.username());
            config.setPassword(resolvePassword(properties));
            config.setReadOnly(true);
            // Direct replay holds two cursors (ticks, session phases) per underlying.
            config.setMaximumPoolSize(8);
            config.setPoolName("zt-source");
            config.addDataSourceProperty("ApplicationName", "autotrade-readonly");
            dataSource = new HikariDataSource(config);
            assertReadOnly(dataSource);
        }
        return dataSource;
    }

    /** Reads the password from the configured file when present, without logging it. */
    static String resolvePassword(ToolProperties.Source source) {
        if (source.passwordFile() != null && !source.passwordFile().isBlank()) {
            Path file = Path.of(source.passwordFile());
            if (Files.isReadable(file)) {
                try {
                    for (String line : Files.readAllLines(file)) {
                        int equals = line.indexOf('=');
                        if (equals > 0 && line.substring(0, equals).trim().equals(source.passwordKey())) {
                            return line.substring(equals + 1).trim();
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read source password file " + file, e);
                }
                throw new IllegalStateException("source password file " + file + " has no " + source.passwordKey());
            }
        }
        return source.password();
    }

    static String withReadOnlyOption(String url) {
        String option = "options=-c%20default_transaction_read_only%3Don";
        return url + (url.contains("?") ? "&" : "?") + option;
    }

    private static void assertReadOnly(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("show default_transaction_read_only")) {
            rs.next();
            if (!"on".equals(rs.getString(1))) {
                throw new SQLException("source connection is not read-only; refusing to continue");
            }
        }
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
