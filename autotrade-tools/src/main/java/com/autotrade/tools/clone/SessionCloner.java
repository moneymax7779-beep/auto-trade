package com.autotrade.tools.clone;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.MarketEvent;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;
import com.autotrade.core.time.MarketTime;
import com.autotrade.md.store.CandleRow;
import com.autotrade.md.store.CopyLoader;
import com.autotrade.md.store.LoadManifests;
import com.autotrade.md.store.MdRowEncoder;
import com.autotrade.md.store.MdTable;
import com.autotrade.md.store.OiProfileRow;
import com.autotrade.md.store.RowMeta;
import com.autotrade.md.store.SequenceDigest;
import com.autotrade.md.store.SessionPhaseRow;
import com.autotrade.md.store.TableChecks;
import com.autotrade.md.zt.SourceTickRow;
import com.autotrade.md.zt.ZtQueries;
import com.autotrade.md.zt.ZtSourceKeys;
import com.autotrade.md.zt.ZtTickParser;
import com.autotrade.tools.SourceDatabase;
import com.autotrade.tools.ToolProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Copies one session of one underlying from zt-tiger-v2 into auto-trade's own database.
 *
 * <p>The source is read once, in sequence order, into local spool files; every payload is checked
 * against its SHA-256. The spool is then loaded in a single target transaction, re-counted and
 * re-digested from the stored rows, and committed only if everything matches the source. The new
 * manifest becomes ACTIVE in that same transaction, superseding any earlier load of the session.
 */
@Component
public class SessionCloner {

    /** Bump when parsing or mapping changes, so manifests show which logic loaded a session. */
    public static final String TOOL_VERSION = "session-cloner/1";

    private static final Logger log = LoggerFactory.getLogger(SessionCloner.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int FETCH_SIZE = 10_000;
    private static final long PROGRESS_EVERY = 500_000;

    private static final Map<String, MdTable> TICK_TABLES = Map.of(
            "CASH", MdTable.INDEX_TICK,
            "FUTURE", MdTable.FUTURE_TICK,
            "OPTION", MdTable.OPTION_TICK,
            "COMPONENT", MdTable.CONSTITUENT_TICK);

    private final DataSource target;
    private final SourceDatabase source;
    private final LoadManifests manifests;
    private final Path spoolRoot;

    public SessionCloner(DataSource target, SourceDatabase source, ToolProperties properties) {
        this.target = target;
        this.source = source;
        this.manifests = new LoadManifests(target);
        this.spoolRoot = Path.of(properties.spoolDir());
    }

    public CloneResult cloneSession(LocalDate session, String underlying, Set<Dataset> datasets) throws Exception {
        Instant started = Instant.now();
        String sourceKey = ZtSourceKeys.underlyingKey(underlying);
        long manifestId = manifests.start("CLONE", session, underlying, source.name(), source.redactedDetail(),
                datasets.stream().map(Dataset::label).toList(), TOOL_VERSION);
        log.info("clone {} {} -> manifest {}", session, underlying, manifestId);

        Map<String, Object> sourceCounts = new TreeMap<>();
        Map<String, Object> sourceDigests = new TreeMap<>();
        Map<String, Object> notes = new LinkedHashMap<>();
        ZtTickParser parser = new ZtTickParser(underlying);
        Spool spool = new Spool(spoolRoot.resolve(session + "-" + underlying + "-" + manifestId));
        try (spool) {
            DataSource sourceDataSource = source.dataSource();
            Map<MdTable, SequenceDigest> digests =
                    spoolTicks(sourceDataSource, session, underlying, sourceKey, manifestId, parser, spool,
                            sourceCounts);
            digests.forEach((table, digest) -> sourceDigests.put(table.name(), digest.result()));
            notes.put("unknownPayloadKeys", parser.unknownKeys());
            notes.put("constituentWeightChanges", parser.weightChanges());

            if (datasets.contains(Dataset.CANDLES)) {
                spoolCandles(sourceDataSource, session, underlying, sourceKey, parser.weights().keySet(), manifestId,
                        spool, sourceCounts);
            }
            if (datasets.contains(Dataset.CAS)) {
                spoolSessionPhases(sourceDataSource, session, underlying, sourceKey, manifestId, spool, sourceCounts);
            }
            if (datasets.contains(Dataset.OI)) {
                spoolOiProfiles(sourceDataSource, session, underlying, manifestId, spool, sourceCounts);
            }
            spool.finish();
            notes.put("spoolBytes", spool.bytes());

            List<Long> superseded = load(manifestId, session, underlying, parser, spool, sourceCounts, digests, notes);
            for (Long old : superseded) {
                try (Connection connection = target.getConnection()) {
                    long deleted = manifests.deleteRows(connection, old);
                    log.info("deleted {} rows of superseded manifest {}", deleted, old);
                }
            }
            Map<String, Long> loaded = new TreeMap<>();
            spool.rows().forEach((table, count) -> loaded.put(table.name(), count));
            Duration elapsed = Duration.between(started, Instant.now());
            log.info("clone {} {} ACTIVE as manifest {} in {}s", session, underlying, manifestId, elapsed.toSeconds());
            return new CloneResult(manifestId, session, underlying, loaded, superseded, spool.bytes(), elapsed);
        } catch (Exception e) {
            notes.put("failedAfterSeconds", Duration.between(started, Instant.now()).toSeconds());
            manifests.fail(manifestId, String.valueOf(e.getMessage()), json(notes));
            log.error("clone {} {} FAILED (manifest {}): {}", session, underlying, manifestId, e.getMessage());
            throw e;
        }
    }

    /** Compresses every md chunk that holds data for the session. Returns the number of chunks compressed. */
    public int compressSession(LocalDate session) throws SQLException {
        String chunksSql = "select format('%I.%I', chunk_schema, chunk_name) from timescaledb_information.chunks "
                + "where hypertable_schema = 'md' and range_start < ? and range_end > ?";
        List<String> chunks = new ArrayList<>();
        try (Connection connection = target.getConnection();
             PreparedStatement statement = connection.prepareStatement(chunksSql)) {
            statement.setObject(1, MarketTime.sessionEnd(session).atOffset(ZoneOffset.UTC));
            statement.setObject(2, MarketTime.sessionStart(session).atOffset(ZoneOffset.UTC));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    chunks.add(rs.getString(1));
                }
            }
            for (String chunk : chunks) {
                try (PreparedStatement compress = connection.prepareStatement(
                        "select compress_chunk(?::regclass, if_not_compressed => true)")) {
                    compress.setString(1, chunk);
                    compress.execute();
                }
            }
        }
        return chunks.size();
    }

    private Map<MdTable, SequenceDigest> spoolTicks(DataSource sourceDataSource, LocalDate session, String underlying,
                                                    String sourceKey, long manifestId, ZtTickParser parser,
                                                    Spool spool, Map<String, Object> sourceCounts)
            throws SQLException, IOException {
        Map<String, long[]> expected = new TreeMap<>();
        try (Connection connection = sourceDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ZtQueries.TICK_COUNTS)) {
            statement.setString(1, sourceKey);
            statement.setObject(2, session);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    expected.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                }
            }
        }
        if (expected.isEmpty()) {
            throw new IllegalStateException("source has no ticks for " + underlying + " on " + session);
        }
        for (String tickType : expected.keySet()) {
            if (!TICK_TABLES.containsKey(tickType)) {
                throw new IllegalStateException("source has unsupported tick_type " + tickType);
            }
        }
        expected.forEach((type, values) -> sourceCounts.put(TICK_TABLES.get(type).name(), values[0]));

        Map<MdTable, SequenceDigest> digests = new EnumMap<>(MdTable.class);
        MdRowEncoder encoder = new MdRowEncoder();
        long rows = 0;
        try (Connection connection = sourceDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(ZtQueries.TICKS_BY_SEQUENCE)) {
                statement.setFetchSize(FETCH_SIZE);
                statement.setString(1, sourceKey);
                statement.setObject(2, session);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        SourceTickRow row = ZtQueries.tickRow(rs);
                        long hash64 = SequenceDigest.hash64(parser.verifiedHash(row));
                        MarketEvent event = parser.parse(row);
                        RowMeta meta = new RowMeta(manifestId, session, hash64, row.exchangeSegment(), row.quoteSource());
                        MdTable table = TICK_TABLES.get(row.tickType());
                        spool.write(table, encode(encoder, event, meta));
                        digests.computeIfAbsent(table, t -> new SequenceDigest()).add(row.sequence(), hash64);
                        if (++rows % PROGRESS_EVERY == 0) {
                            log.info("  {} {}: {} ticks read", session, underlying, rows);
                        }
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        for (Map.Entry<String, long[]> entry : expected.entrySet()) {
            MdTable table = TICK_TABLES.get(entry.getKey());
            SequenceDigest digest = digests.get(table);
            if (digest == null) {
                throw new IllegalStateException("source stream returned no " + entry.getKey() + " rows");
            }
            SequenceDigest.Result result = digest.result();
            long[] values = entry.getValue();
            if (result.count() != values[0] || result.firstSequence() != values[1]
                    || result.lastSequence() != values[2] || !result.strictlyIncreasing()) {
                throw new IllegalStateException("source stream for " + entry.getKey() + " read " + result.count()
                        + " rows, count query says " + values[0]);
            }
        }
        log.info("  {} {}: {} ticks spooled, all payload hashes verified", session, underlying, rows);
        return digests;
    }

    private static byte[] encode(MdRowEncoder encoder, MarketEvent event, RowMeta meta) {
        return switch (event) {
            case IndexTick tick -> encoder.index(tick, meta);
            case FutureTick tick -> encoder.future(tick, meta);
            case OptionTick tick -> encoder.option(tick, meta);
            case ConstituentTick tick -> encoder.constituent(tick, meta);
            case SessionPhaseEvent phase -> throw new IllegalArgumentException("not a tick: " + phase);
        };
    }

    private void spoolCandles(DataSource sourceDataSource, LocalDate session, String underlying, String sourceKey,
                              Set<String> constituents, long manifestId, Spool spool,
                              Map<String, Object> sourceCounts) throws SQLException, IOException {
        List<String> keys = new ArrayList<>();
        keys.add(sourceKey);
        for (String symbol : new TreeSet<>(constituents)) {
            keys.add(ZtSourceKeys.COMPONENT_CANDLE_PREFIX + symbol);
        }
        String where = " from market_candles where underlying_key = any(?) and bar_start >= ? and bar_start < ?";
        String sql = "select underlying_key, timeframe, source, bar_start, open_price, high_price, low_price, "
                + "close_price, volume, volume_complete, volume_source, volume_contract_symbol, volume_contract_expiry, "
                + "mss" + where + " order by underlying_key, timeframe, source, bar_start";
        MdRowEncoder encoder = new MdRowEncoder();
        long rows = 0;
        try (Connection connection = sourceDataSource.getConnection()) {
            connection.setAutoCommit(false);
            long expected = count(connection, "select count(*)" + where, keys, session);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(FETCH_SIZE);
                bindKeysAndDay(connection, statement, keys, session);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        String instrument = key.equals(sourceKey) ? underlying
                                : key.substring(ZtSourceKeys.COMPONENT_CANDLE_PREFIX.length());
                        CandleRow candle = new CandleRow(underlying, instrument, rs.getString(2), rs.getString(3),
                                MarketTime.fromIstLocal(rs.getObject(4, LocalDateTime.class)), rs.getDouble(5),
                                rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), ZtQueries.nullableDouble(rs, 9),
                                ZtQueries.nullableBoolean(rs, 10), rs.getString(11), rs.getString(12),
                                rs.getObject(13, LocalDate.class), ZtQueries.nullableInt(rs, 14));
                        spool.write(MdTable.CANDLE, encoder.candle(candle, manifestId, session));
                        rows++;
                    }
                }
            } finally {
                connection.rollback();
            }
            requireEqual("candles", rows, expected);
        }
        sourceCounts.put(MdTable.CANDLE.name(), rows);
        log.info("  {} {}: {} candles spooled ({} instruments)", session, underlying, rows, keys.size());
    }

    private void spoolSessionPhases(DataSource sourceDataSource, LocalDate session, String underlying,
                                    String sourceKey, long manifestId, Spool spool,
                                    Map<String, Object> sourceCounts) throws SQLException, IOException {
        MdRowEncoder encoder = new MdRowEncoder();
        long rows = 0;
        try (Connection connection = sourceDataSource.getConnection()) {
            connection.setAutoCommit(false);
            long expected;
            try (PreparedStatement statement =
                         connection.prepareStatement("select count(*)" + ZtQueries.SESSION_PHASE_WHERE)) {
                bindUnderlyingAndDay(statement, sourceKey, session);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    expected = rs.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(ZtQueries.SESSION_PHASES)) {
                statement.setFetchSize(FETCH_SIZE);
                bindUnderlyingAndDay(statement, sourceKey, session);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        SessionPhaseRow row = ZtQueries.sessionPhaseRow(rs, underlying);
                        spool.write(MdTable.SESSION_PHASE_EVENT, encoder.sessionPhase(row, manifestId, session));
                        rows++;
                    }
                }
            } finally {
                connection.rollback();
            }
            requireEqual("session phase events", rows, expected);
        }
        sourceCounts.put(MdTable.SESSION_PHASE_EVENT.name(), rows);
        log.info("  {} {}: {} session-phase (CAS) events spooled", session, underlying, rows);
    }

    private void spoolOiProfiles(DataSource sourceDataSource, LocalDate session, String underlying, long manifestId,
                                 Spool spool, Map<String, Object> sourceCounts) throws SQLException, IOException {
        String sql = "select id, expiry, received_at_ms, available_at_ms, membership_hash, payload_hash, profile_hash, "
                + "profile_json from oi_profile_snapshots where underlying = ? and session_date = ? "
                + "order by received_at_ms, id";
        MdRowEncoder encoder = new MdRowEncoder();
        long rows = 0;
        try (Connection connection = sourceDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(500);
                statement.setString(1, underlying);
                statement.setObject(2, session);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        Long availableMs = ZtQueries.nullableLong(rs, 4);
                        OiProfileRow profile = new OiProfileRow(underlying, rs.getString(1),
                                rs.getObject(2, LocalDate.class), Instant.ofEpochMilli(rs.getLong(3)),
                                availableMs == null ? null : Instant.ofEpochMilli(availableMs), rs.getString(5),
                                rs.getString(6), rs.getString(7), rs.getString(8));
                        spool.write(MdTable.OI_PROFILE, encoder.oiProfile(profile, manifestId, session));
                        rows++;
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        sourceCounts.put(MdTable.OI_PROFILE.name(), rows);
        log.info("  {} {}: {} OI profiles spooled", session, underlying, rows);
    }

    private List<Long> load(long manifestId, LocalDate session, String underlying, ZtTickParser parser, Spool spool,
                            Map<String, Object> sourceCounts, Map<MdTable, SequenceDigest> sourceDigests,
                            Map<String, Object> notes) throws SQLException, IOException {
        try (Connection connection = target.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (MdTable table : spool.rows().keySet()) {
                    try (InputStream rows = spool.open(table)) {
                        long copied = CopyLoader.copy(connection, table, rows);
                        log.info("  loaded {} rows into {}", copied, table.qualifiedName());
                    }
                }
                Map<String, Object> loadedCounts = new TreeMap<>();
                Map<String, Object> loadedDigests = new TreeMap<>();
                for (MdTable table : MdTable.values()) {
                    long count = TableChecks.count(connection, table, manifestId);
                    Object expected = sourceCounts.get(table.name());
                    if (expected == null && count == 0) {
                        continue;
                    }
                    loadedCounts.put(table.name(), count);
                    requireEqual(table.name(), count, expected == null ? 0L : ((Number) expected).longValue());
                    if (table.isTick()) {
                        SequenceDigest.Result stored = TableChecks.digest(connection, table, manifestId);
                        SequenceDigest.Result read = sourceDigests.get(table).result();
                        if (!stored.sameAs(read)) {
                            throw new IllegalStateException(table + " digest mismatch: source " + read.sha256()
                                    + " stored " + stored.sha256());
                        }
                        loadedDigests.put(table.name(), stored);
                    }
                }
                writeWeights(connection, manifestId, session, underlying, parser);
                Map<String, Object> sourceDigestJson = new TreeMap<>();
                sourceDigests.forEach((table, digest) -> sourceDigestJson.put(table.name(), digest.result()));
                List<Long> superseded = manifests.activate(connection, manifestId, json(sourceCounts),
                        json(loadedCounts), json(sourceDigestJson), json(loadedDigests), json(notes));
                connection.commit();
                return superseded;
            } catch (SQLException | IOException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static void writeWeights(Connection connection, long manifestId, LocalDate session, String underlying,
                                     ZtTickParser parser) throws SQLException {
        String sql = "insert into ref.index_weight (session_date, underlying, symbol, weight_pct, weight_source, "
                + "manifest_id) values (?, ?, ?, ?, ?, ?) on conflict (session_date, underlying, symbol) do update "
                + "set weight_pct = excluded.weight_pct, weight_source = excluded.weight_source, "
                + "manifest_id = excluded.manifest_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, Double> weight : parser.weights().entrySet()) {
                statement.setObject(1, session);
                statement.setString(2, underlying);
                statement.setString(3, weight.getKey());
                statement.setDouble(4, weight.getValue());
                statement.setString(5, parser.weightSources().get(weight.getKey()));
                statement.setLong(6, manifestId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static long count(Connection connection, String sql, List<String> keys, LocalDate session)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindKeysAndDay(connection, statement, keys, session);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** market_candles.bar_start is zone-less IST wall-clock time. */
    private static void bindKeysAndDay(Connection connection, PreparedStatement statement, List<String> keys,
                                       LocalDate session) throws SQLException {
        statement.setArray(1, connection.createArrayOf("text", keys.toArray()));
        statement.setObject(2, session.atStartOfDay());
        statement.setObject(3, session.plusDays(1).atStartOfDay());
    }

    private static void bindUnderlyingAndDay(PreparedStatement statement, String sourceKey, LocalDate session)
            throws SQLException {
        statement.setString(1, sourceKey);
        statement.setObject(2, MarketTime.sessionStart(session).atOffset(ZoneOffset.UTC));
        statement.setObject(3, MarketTime.sessionEnd(session).atOffset(ZoneOffset.UTC));
    }

    private static void requireEqual(String what, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException(what + ": " + actual + " rows, source has " + expected);
        }
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }




}
