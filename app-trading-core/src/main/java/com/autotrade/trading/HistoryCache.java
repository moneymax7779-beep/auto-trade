package com.autotrade.trading;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.time.LocalDate;
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
 * off otherwise and never for live sessions.
 *
 * <p>v2 (23 Nov 2026, user: "build it now"): the fingerprint covers only what a replay of session D can read, which
 * is data from before D, so loading a new day overnight no longer makes every earlier day's history miss. It covers:
 * <ul>
 * <li>the code: the bytes of every history class (a different build of the same source shares the cache, any change
 * to these classes does not), and how the history was composed (tick or bar replay, bar backfill);</li>
 * <li>auto-trade's own data before D: every capture/clone load dated before D (id, status, underlying, finish time,
 * loaded row counts), the insert/update/delete counters of the md chunks that start before D (a reload, repair,
 * compression or decompression changes them), the live feature-snapshot runs covering a session before D (id, status,
 * finish time; for one still running, its row count before D), and the one-minute bar fetches starting before D
 * ({@code hist.fetch_log});</li>
 * <li>zt-tiger-v2's data before D: the source database's identity, and the row count, latest bar and integer price
 * sum of {@code market_candles}, and the row count and integer IV sum of {@code option_tick_snapshots}, before D.</li>
 * </ul>
 * Any change to one of these gives a new fingerprint, so a stale answer is never served; the old files simply stop
 * being read. Requests for any other session than D pass through uncached. A fingerprint that cannot be computed
 * turns the cache off for that run.
 */
final class HistoryCache {

    private static final Logger log = LoggerFactory.getLogger(HistoryCache.class);
    /** One switch for both replay caches: earlier sessions' history under history/, the day's ticks under ticks/. */
    static final String ENV = "AUTOTRADE_REPLAY_CACHE_DIR";

    private static final List<Class<?>> CODE = List.of(OwnSessionHistory.class, ZtSessionHistory.class,
            BarSessionHistory.class, CombinedSessionHistory.class, BackfilledSessionHistory.class, SessionHistories.class,
            CachingSessionHistory.class, MarketTime.class, HistoryCache.class, com.autotrade.md.zt.ZtSourceKeys.class);

    private HistoryCache() {
    }

    /** {@code history} wrapped in the cache for {@code session} when it is on; {@code history} itself otherwise. */
    static SessionHistory wrap(SessionHistory history, DataSource target, DataSource zt, String shape, LocalDate session) {
        String dir = System.getenv(ENV);
        if (dir == null || dir.isBlank()) {
            return history;
        }
        try {
            long started = System.nanoTime();
            String fingerprint = fingerprint(target, zt, shape, session, Path.of(dir, "history", "memo"));
            log.info("history cache on: {} (fingerprint {} for {}, {} ms)", dir, fingerprint.substring(0, 16), session,
                    (System.nanoTime() - started) / 1_000_000);
            return new CachingSessionHistory(history, new CachingSessionHistory.FileStore(Path.of(dir, "history")),
                    fingerprint, session);
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

    /**
     * v3 (review of 24 Nov 2026): v2 plus the database's start and statistics-reset times (table counters restart from zero
     * after a crash, so a counter value alone can repeat), the stored one-minute bars the history reads (per index and
     * kind, before D: count and last load time), the live feature-snapshot rows before D (count and last snapshot), and
     * zt-tiger-v2's two history tables as a whole-row hash before D. That hash takes seconds, so on the zt_archive copy it
     * is remembered under {@code memo} keyed by that database's start time, statistics reset and table counters (any
     * write or restart recomputes it); on any other zt database (the live one) it falls back to those counters alone, so
     * a replay never runs heavy reads against the live capture. The stored-bar aggregate is remembered the same way, keyed by
     * auto-trade's own start time, statistics reset and {@code hist.candle}'s write counters.
     */
    static String fingerprint(DataSource target, DataSource zt, String shape, LocalDate session, Path memo)
            throws SQLException, IOException {
        StringBuilder text = new StringBuilder("v3|").append(shape).append('|').append(session).append('|');
        for (Class<?> type : CODE) {
            appendClassHash(text, type);
        }
        try (Connection c = target.getConnection()) {
            String server = one(c, "select pg_postmaster_start_time()::text || ':' || coalesce((select "
                    + "stats_reset::text from pg_stat_database where datname = current_database()), '')");
            text.append("server=").append(server).append('|');
            text.append("loads=").append(one(c, "select coalesce(md5(string_agg(id || ':' || status || ':' || "
                    + "session_date || ':' || coalesce(underlying, '') || ':' || coalesce(finished_at::text, '') || ':' || "
                    + "loaded_counts::text, ',' order by id)), '') from md.load_manifest where session_date < ?", session))
                    .append('|');
            text.append("chunks=").append(one(c, "select coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) || ':' || "
                    + "count(*) from pg_stat_user_tables where relid in (select format('%I.%I', chunk_schema, chunk_name)"
                    + "::regclass from timescaledb_information.chunks where hypertable_schema = 'md' and range_start < ?)",
                    session)).append('|');
            text.append("liveRuns=").append(one(c, "select coalesce(md5(string_agg(id || ':' || status || ':' || "
                    + "coalesce(finished_at::text, ''), ',' order by id)), '') from research.run where kind = 'LIVE_FEATURES' "
                    + "and exists (select 1 from unnest(sessions) d where d < ?)", session)).append('|');
            text.append("liveRows=").append(one(c, "select count(*) || ':' || coalesce(max(s.snap_time)::text, '') from "
                    + "feat.snapshot s join research.run r on r.id = s.run_id and r.kind = 'LIVE_FEATURES' "
                    + "where s.session_date < ?", session)).append('|');
            text.append("barFetches=").append(one(c, "select count(*) || ':' || coalesce(max(id), 0) from hist.fetch_log "
                    + "where from_date < ?", session)).append('|');
            // the bar aggregate scans hist.candle (2 s idle, 20+ s under a replay batch): remembered per day, keyed by the
            // database's start and statistics reset and hist.candle's write counters, so any write or restart recomputes it
            String candleWrites = one(c, "select coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) || ':' || count(*) "
                    + "from pg_stat_user_tables where schemaname = 'hist' and relname = 'candle'");
            text.append("bars=").append(memo(memo, "bars|" + server + "|" + candleWrites + "|" + session, () -> one(c,
                    "select coalesce(string_agg(underlying || ':' || kind || ':' || n || ':' || "
                    + "coalesce(m::text, ''), ',' order by underlying, kind), '') from (select underlying, kind, count(*) n, "
                    + "max(loaded_at) m from hist.candle where kind in ('INDEX', 'FUTURE') and bar_start < ? "
                    + "group by 1, 2) x", session))).append('|');
        }
        if (zt != null) {
            try (Connection c = zt.getConnection()) {
                String identity = one(c, "select current_database() || ':' || (select oid from pg_database where "
                        + "datname = current_database()) || ':' || pg_postmaster_start_time()::text || ':' || coalesce((select "
                        + "stats_reset::text from pg_stat_database where datname = current_database()), '') || ':' || "
                        + "(select coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) || ':' || count(*) from "
                        + "pg_stat_user_tables where relname in ('market_candles', 'option_tick_snapshots', "
                        + "'market_tick_records'))");
                text.append("zt=").append(identity).append('|');
                if (identity.startsWith("zt_archive:")) {
                    text.append("ztRows=").append(memo(memo, identity + "|" + session, () -> one(c,
                            "select (select count(*) || ':' || coalesce(sum(hashtext(t::text)::bigint), 0) from market_candles t "
                                    + "where bar_start < ?) || ':' || (select count(*) || ':' || coalesce(sum(hashtext(t::text)"
                                    + "::bigint), 0) from option_tick_snapshots t where captured_at < ?)", session, session)));
                }
            }
        }
        return sha256(text.toString());
    }

    private interface SqlValue {
        String get() throws SQLException;
    }

    /** {@code value}, remembered in {@code dir} under {@code key} (computed when absent or unreadable). */
    private static String memo(Path dir, String key, SqlValue value) throws SQLException {
        if (dir == null) {
            return value.get();
        }
        Path file = dir.resolve(sha256(key) + ".txt");
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                String v = java.nio.file.Files.readString(file).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (IOException e) {
            log.warn("history cache memo unreadable ({}); recomputing", e.toString());
        }
        String v = value.get();
        try {
            java.nio.file.Files.createDirectories(dir);
            Path tmp = java.nio.file.Files.createTempFile(dir, "memo", ".tmp");
            try {
                java.nio.file.Files.writeString(tmp, v);
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            log.warn("history cache memo not stored ({})", e.toString());
        }
        return v;
    }

    private static String one(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return String.valueOf(rs.getString(1));
            }
        }
    }


    /** The class's bytes and, recursively, its nested classes' (as the tick cache does). */
    private static void appendClassHash(StringBuilder text, Class<?> type) throws IOException {
        String name = type.getName();
        try (InputStream in = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new IOException("class bytes not found for " + name);
            }
            text.append(name).append('=').append(sha256(in.readAllBytes())).append('|');
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            appendClassHash(text, nested);
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
