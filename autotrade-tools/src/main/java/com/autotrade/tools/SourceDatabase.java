package com.autotrade.tools;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.autotrade.md.zt.ZtReadOnlyDataSource;
import com.zaxxer.hikari.HikariDataSource;

/** Read-only connection to the zt-tiger-v2 database, created only when a command needs it. */
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
            // Direct replay holds two cursors (ticks, session phases) per underlying.
            dataSource = ZtReadOnlyDataSource.open(settings(properties));
        }
        return dataSource;
    }

    static ZtReadOnlyDataSource.Settings settings(ToolProperties.Source source) {
        return new ZtReadOnlyDataSource.Settings(source.url(), source.username(), source.password(),
                source.passwordFile(), source.passwordKey(), "autotrade-readonly", 8);
    }

    static String withReadOnlyOption(String url) {
        return ZtReadOnlyDataSource.withReadOnlyOption(url);
    }

    static String resolvePassword(ToolProperties.Source source) {
        return ZtReadOnlyDataSource.resolvePassword(settings(source));
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
