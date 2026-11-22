package com.autotrade.trading;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autotrade.core.history.BackfilledSessionHistory;
import com.autotrade.core.history.CachingSessionHistory;
import com.autotrade.core.history.CombinedSessionHistory;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.BarSessionHistory;
import com.autotrade.md.store.OwnSessionHistory;
import com.autotrade.md.store.SessionHistories;
import com.autotrade.md.zt.ZtSessionHistory;

/**
 * Replay-only history cache (user, 22 Nov 2026): on when {@code AUTOTRADE_REPLAY_CACHE_DIR} names a directory,
 * off otherwise and never for live sessions. The fingerprint covers what the cached answers are read from:
 * <ul>
 * <li>the code: the bytes of every history class (a different build of the same source shares the cache, any change
 * to these classes does not);</li>
 * <li>how the history was composed (tick or bar replay, bar backfill);</li>
 * <li>auto-trade's own data: every capture/clone load (id, status, session, underlying), the live feature-snapshot
 * runs (and the row count of any still running), the one-minute bars ({@code hist.fetch_log} and the table's
 * insert/update/delete counters), and the md tables' counters;</li>
 * <li>zt-tiger-v2's data: the source database's identity and its tables' row counters (a restored or dropped
 * {@code zt_archive} changes them; partitions of those tables, if any, are not listed separately).</li>
 * </ul>
 * Any change to one of these gives a new fingerprint, so a stale answer is never served; the old files simply stop
 * being read. A fingerprint that cannot be computed turns the cache off for that run.
 */
final class HistoryCache {

    private static final Logger log = LoggerFactory.getLogger(HistoryCache.class);
    /** One switch for both replay caches: earlier sessions' history under history/, the day's ticks under ticks/. */
    static final String ENV = "AUTOTRADE_REPLAY_CACHE_DIR";

    private static final List<Class<?>> CODE = List.of(OwnSessionHistory.class, ZtSessionHistory.class,
            BarSessionHistory.class, CombinedSessionHistory.class, BackfilledSessionHistory.class, SessionHistories.class,
            CachingSessionHistory.class, MarketTime.class, HistoryCache.class);

    private HistoryCache() {
    }

    /** {@code history} wrapped in the cache when it is on; {@code history} itself otherwise. */
    static SessionHistory wrap(SessionHistory history, DataSource target, DataSource zt, String shape) {
        String dir = System.getenv(ENV);
        if (dir == null || dir.isBlank()) {
            return history;
        }
        try {
            String fingerprint = fingerprint(target, zt, shape);
            log.info("history cache on: {} (fingerprint {})", dir, fingerprint.substring(0, 16));
            return new CachingSessionHistory(history, new CachingSessionHistory.FileStore(Path.of(dir, "history")),
                    fingerprint);
        } catch (RuntimeException | SQLException | IOException e) {
            log.warn("history cache off for this run: no fingerprint ({})", e.toString());
            return history;
        }
    }

    /** Logs how the cache did, when {@code history} is one. */
    static void report(SessionHistory history) {
        if (history instanceof CachingSessionHistory c) {
            log.info("history cache: {} hits, {} misses, {} store errors", c.hits(), c.misses(), c.storeErrors());
        }
    }

    static String fingerprint(DataSource target, DataSource zt, String shape) throws SQLException, IOException {
        StringBuilder text = new StringBuilder("v1|").append(shape).append('|');
        for (Class<?> type : CODE) {
            text.append(type.getName()).append('=').append(classHash(type)).append('|');
        }
        try (Connection c = target.getConnection(); Statement s = c.createStatement()) {
            text.append("manifests=").append(one(s, "select coalesce(md5(string_agg(id || ':' || status || ':' || "
                    + "session_date || ':' || coalesce(underlying, ''), ',' order by id)), '') from md.load_manifest"))
                    .append('|');
            text.append("liveRuns=").append(one(s, "select coalesce(md5(string_agg(id || ':' || status || ':' || "
                    + "coalesce(finished_at::text, ''), ',' order by id)), '') from research.run where kind = 'LIVE_FEATURES'"))
                    .append('|');
            text.append("liveRunning=").append(one(s, "select coalesce(sum((select count(*) from feat.snapshot s "
                    + "where s.run_id = r.id)), 0) from research.run r where r.kind = 'LIVE_FEATURES' and r.status = 'RUNNING'"))
                    .append('|');
            text.append("barFetches=").append(one(s, "select count(*) || ':' || coalesce(max(id), 0) from hist.fetch_log"))
                    .append('|');
            text.append("tableChanges=").append(one(s, "select coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) || ':' "
                    + "|| count(*) from pg_stat_user_tables where relid in (select format('%I.%I', chunk_schema, chunk_name)"
                    + "::regclass from timescaledb_information.chunks where hypertable_schema = 'md') "
                    + "or (schemaname, relname) in (('hist', 'candle'), ('md', 'load_manifest'))"))
                    .append('|');
        }
        if (zt != null) {
            try (Connection c = zt.getConnection(); Statement s = c.createStatement()) {
                // the tables ZtSessionHistory reads; timescale's own catalog tables change with its background jobs
                text.append("zt=").append(one(s, "select current_database() || ':' || (select oid from pg_database where "
                        + "datname = current_database()) || ':' || coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) "
                        + "|| ':' || count(*) from pg_stat_user_tables where relname in ('market_candles', "
                        + "'option_tick_snapshots', 'market_tick_records')"));
            }
        }
        return sha256(text.toString());
    }

    private static String one(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return String.valueOf(rs.getString(1));
        }
    }

    private static String classHash(Class<?> type) throws IOException {
        String name = type.getName();
        try (InputStream in = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new IOException("class bytes not found for " + name);
            }
            return sha256(in.readAllBytes());
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
