package com.autotrade.trading.activity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/**
 * Activity's own two connections (code review 23 Nov 2026): the engine's pool is six connections and runs PAPER-1, so the
 * activity writer and the activity pages never borrow from it. Each statement may run 5 s at most and a connection is
 * waited for 3 s at most, so a slow database costs activity rows, not the engine's time. Deliberately not a DataSource
 * bean: one would make Spring Boot stop creating the main one.
 */
public final class ActivityDb implements AutoCloseable {

    static final int POOL = 2;

    private final DataSource dataSource;
    private final HikariDataSource own;

    public ActivityDb(DataSource engine) {
        if (engine instanceof HikariDataSource h && h.getJdbcUrl() != null) {
            HikariConfig c = new HikariConfig();
            c.setJdbcUrl(h.getJdbcUrl());
            c.setUsername(h.getUsername());
            c.setPassword(h.getPassword());
            c.setPoolName("activity");
            c.setMaximumPoolSize(POOL);
            c.setMinimumIdle(0);
            c.setConnectionTimeout(3_000);
            c.setInitializationFailTimeout(-1);               // start even if the database is not up yet
            c.setConnectionInitSql("set statement_timeout = 5000");
            own = new HikariDataSource(c);
            dataSource = own;
        } else {
            own = null;                                       // tests: the given data source as it is
            dataSource = engine;
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        if (own != null) {
            own.close();
        }
    }
}
