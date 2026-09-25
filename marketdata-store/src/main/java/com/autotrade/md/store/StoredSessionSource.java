package com.autotrade.md.store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.SessionEventSource;

/** Sessions stored in auto-trade's own database (active load manifests only). */
public final class StoredSessionSource implements SessionEventSource {

    private final LoadManifests manifests;
    private final ReplayReader reader;

    public StoredSessionSource(DataSource dataSource) {
        this.manifests = new LoadManifests(dataSource);
        this.reader = new ReplayReader(dataSource);
    }

    @Override
    public String name() {
        return "own-store";
    }

    @Override
    public ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink)
            throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String underlying : underlyings) {
            LoadManifest manifest = manifests.active(session, underlying).orElseThrow(
                    () -> new IllegalStateException("no active load for " + session + " " + underlying));
            ids.add(manifest.id());
        }
        return new ReplayResult(reader.replay(ids, sink), 0);
    }
}
