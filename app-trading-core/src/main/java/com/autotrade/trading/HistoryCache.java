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
            CachingSessionHistory.class, MarketTime.class, HistoryCache.class);

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
            String fingerprint = fingerprint(target, zt, shape, session);
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

    static String fingerprint(DataSource target, DataSource zt, String shape, LocalDate session)
            throws SQLException, IOException {
        StringBuilder text = new StringBuilder("v2|").append(shape).append('|').append(session).append('|');
        for (Class<?> type : CODE) {
            text.append(type.getName()).append('=').append(classHash(type)).append('|');
        }
        try (Connection c = target.getConnection()) {
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
            text.append("liveRunning=").append(one(c, "select coalesce(sum((select count(*) from feat.snapshot s where "
                    + "s.run_id = r.id and s.session_date < ?)), 0) from research.run r where r.kind = 'LIVE_FEATURES' "
                    + "and r.status = 'RUNNING'", session)).append('|');
            text.append("barFetches=").append(one(c, "select count(*) || ':' || coalesce(max(id), 0) from hist.fetch_log "
                    + "where from_date < ?", session)).append('|');
        }
        if (zt != null) {
            try (Connection c = zt.getConnection()) {
                text.append("zt=").append(one(c, "select current_database() || ':' || (select oid from pg_database where "
                        + "datname = current_database()) || ':' || (select count(*) || ':' || coalesce(max(bar_start)::text, "
                        + "'') || ':' || coalesce(sum(round(close_price * 100)::bigint), 0) from market_candles where "
                        + "bar_start < ?) || ':' || (select count(*) || ':' || coalesce(sum(round(coalesce("
                        + "implied_volatility, 0) * 1000000)::bigint), 0) from option_tick_snapshots where captured_at < ?)",
                        session, session));
            }
        }
        return sha256(text.toString());
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
