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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StoredSessionSource.class);

    private final LoadManifests manifests;
    private final ReplayReader reader;
    private final ReplayCache cache;

    public StoredSessionSource(DataSource dataSource) {
        this(dataSource, null);
    }

    /** With {@code cache} (null: none) holding each day's ordered stream for later replays (22 Nov 2026). */
    public StoredSessionSource(DataSource dataSource, ReplayCache cache) {
        this.manifests = new LoadManifests(dataSource);
        this.reader = new ReplayReader(dataSource);
        this.cache = cache;
    }

    @Override
    public String name() {
        return "own-store";
    }

    @Override
    public ReplayResult replay(LocalDate session, List<String> underlyings, Consumer<MarketEvent> sink)
            throws Exception {
        // an underlying not captured that day (e.g. BANKNIFTY before 15 Oct 2026, when its recording resumed) is left
        // out of the replay; a day with none of them captured is an error
        List<Long> ids = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String underlying : underlyings) {
            manifests.active(session, underlying).ifPresentOrElse(m -> ids.add(m.id()), () -> missing.add(underlying));
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("no active load for " + session + " " + underlyings);
        }
        if (!missing.isEmpty()) {
            LOG.warn("own capture {}: no load for {}; replaying {} without them", session, missing, underlyings);
        }
        return new ReplayResult(cache == null ? reader.replay(ids, sink) : cache.replay(session, ids, reader, sink), 0);
    }
}
