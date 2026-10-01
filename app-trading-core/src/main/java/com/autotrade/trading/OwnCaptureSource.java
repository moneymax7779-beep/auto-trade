package com.autotrade.trading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.md.store.LoadManifests;
import com.autotrade.md.store.StoredSessionSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Replays a session from auto-trade's own live capture (md.*): the same ticks, order books and auction data
 * the live session received, so a replay can reproduce live decisions exactly. The live feed also captures
 * INDIA_VIX under its own manifest; it is replayed with the traded underlyings whenever it was captured.
 *
 * <p>The reader holds one open cursor per md table for the whole replay, so it gets its own small read-only
 * pool (closed afterwards) instead of taking the session's connections.
 */
final class OwnCaptureSource implements SessionEventSource {

    static final String VIX = "INDIA_VIX";
    private static final int READER_POOL = 10;

    private final DataSource target;

    OwnCaptureSource(DataSource target) {
        this.target = target;
    }

    @Override
    public String name() {
        return "own-capture";
    }

    @Override
    public ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink)
            throws Exception {
        try (HikariDataSource reader = readerPool()) {
            List<String> all = new ArrayList<>(underlyings);
            if (!all.contains(VIX) && new LoadManifests(reader).active(session, VIX).isPresent()) {
                all.add(VIX);
            }
            return new StoredSessionSource(reader).replay(session, all, sink);
        }
    }

    private HikariDataSource readerPool() {
        if (!(target instanceof HikariDataSource main)) {
            throw new IllegalStateException("replay-source own needs the Hikari target data source");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(main.getJdbcUrl());
        config.setUsername(main.getUsername());
        config.setPassword(main.getPassword());
        config.setMaximumPoolSize(READER_POOL);
        config.setReadOnly(true);
        config.setPoolName("own-capture-reader");
        return new HikariDataSource(config);
    }
}
