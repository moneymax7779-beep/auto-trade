package com.autotrade.tools.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.time.MarketTime;
import com.autotrade.instruments.Instrument;
import com.autotrade.instruments.InstrumentMaster;
import com.autotrade.instruments.InstrumentStore;
import com.autotrade.instruments.UpstoxInstrumentFile;
import com.autotrade.features.snapshot.SnapshotFlattener;
import com.autotrade.md.store.LoadManifest;
import com.autotrade.md.store.LoadManifests;
import com.autotrade.md.store.MdTable;
import com.autotrade.md.store.SequenceDigest;
import com.autotrade.md.store.StoredSessionSource;
import com.autotrade.md.store.TableChecks;
import com.autotrade.tools.MarketHoursGuard;
import com.autotrade.tools.CodeVersion;
import com.autotrade.tools.SourceDatabase;
import com.autotrade.tools.ToolArgs;
import com.autotrade.tools.clone.CloneResult;
import com.autotrade.tools.clone.Dataset;
import com.autotrade.tools.clone.SessionCloner;
import com.autotrade.md.zt.ZtQueries;
import com.autotrade.research.LifecycleStore;
import com.autotrade.md.zt.ZtSessionHistory;
import com.autotrade.md.zt.ZtSessionSource;
import com.autotrade.md.zt.ZtSourceKeys;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Dispatches CLI commands and sets the process exit code. */
@Component
public class ToolCommands implements ApplicationRunner, ExitCodeGenerator {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEFAULT_UNDERLYINGS = "NIFTY,SENSEX";
    static final String FEATURES_FILE = "config/features/features.v2.yaml";
    static final String EXCHANGE_FILE = "config/exchange/nse-bse-sessions.v2.yaml";

    private final DataSource target;
    private final SourceDatabase source;
    private final SessionCloner cloner;
    private final LoadManifests manifests;
    private int exitCode;

    public ToolCommands(DataSource target, SourceDatabase source, SessionCloner cloner) {
        this.target = target;
        this.source = source;
        this.cloner = cloner;
        this.manifests = new LoadManifests(target);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String[] args = arguments.getSourceArgs();
        try {
            ToolArgs parsed = ToolArgs.parse(args, Set.of("force", "all", "source", "verify-hashes", "save", "held-out"));
            exitCode = switch (parsed.command()) {
                case "sessions" -> sessions(parsed);
                case "clone" -> cloneSessions(parsed);
                case "manifests" -> listManifests(parsed);
                case "verify" -> verify(parsed);
                case "replay" -> replay(parsed);
                case "zt-sessions" -> ztSessions(parsed);
                case "features" -> features(parsed);
                case "simulate" -> simulate(parsed);
                case "instruments" -> instruments(parsed);
                case "lifecycle" -> lifecycle(parsed);
                default -> usage("unknown command: " + parsed.command());
            };
        } catch (IllegalArgumentException e) {
            exitCode = usage(e.getMessage());
        } catch (Exception e) {
            System.err.println("FAILED: " + e.getMessage());
            exitCode = 1;
        } finally {
            source.close();
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private int usage(String problem) {
        System.err.println(problem);
        System.err.println("""
                commands:
                  sessions                                   declared sessions, split and local load state
                  clone   --session D[,D] | --all  [--underlying NIFTY,SENSEX] [--what ticks,candles,cas,oi] [--force]
                  manifests                                  every load manifest
                  verify  --session D [--underlying ...] [--source]   re-digest stored rows (and the source)
                  replay  --session D [--underlying ...] [--from zt|own] [--verify-hashes] [--force]
                                                             stream a session in replay order and check it
                                                             (zt = read zt-tiger-v2 directly, the default)
                  zt-sessions [--force]                      sessions and tick counts available in zt-tiger-v2
                  features --session D [--underlying ...] [--at 10:00,14:52] [--out DIR] [--from zt|own] [--save] [--force]
                                                             feature snapshots per minute to CSV (default .local/features)
                  simulate --session D --underlying U --strike K --type CE|PE --at HH:mm [--stop-pct 15]
                           [--target-pct 30] [--exit-by 15:20] [--lots 1]   one long option trade, base and stressed fills
                  instruments --file NSE.json.gz[,BSE.json.gz] [--date D] [--underlying NIFTY,BANKNIFTY,SENSEX]
                                                             load an Upstox contract-master file into ref.instrument
                  lifecycle [--split TUNING|HELD_OUT | --session D[,D]] [--underlying ...] [--strategy FILE] [--save]
                            [--held-out] [--force]           replay the early-confirm-runner lifecycle and report
                                                             (no --split/--session: the strategy's evaluation window)
                  config-hash <file.yaml> [...]              hash and validate threshold files (no database)""");
        return 2;
    }

    private int sessions(ToolArgs args) throws SQLException {
        args.allowOnly(Set.of());
        String sql = "select s.session_date, s.split, coalesce(s.note, ''), "
                + "coalesce(string_agg(m.underlying || '#' || m.id, ' ' order by m.underlying), '') "
                + "from research.session_split s left join md.load_manifest m "
                + "on m.session_date = s.session_date and m.status = 'ACTIVE' "
                + "group by 1, 2, 3 order by 1";
        System.out.printf("%-12s %-9s %-18s %s%n", "session", "split", "note", "active loads");
        try (Connection connection = target.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                System.out.printf("%-12s %-9s %-18s %s%n", rs.getObject(1, LocalDate.class), rs.getString(2),
                        rs.getString(3), rs.getString(4));
            }
        }
        return 0;
    }

    private int cloneSessions(ToolArgs args) throws Exception {
        args.allowOnly(Set.of("session", "all", "underlying", "what", "force"));
        if (!args.flag("force") && MarketHoursGuard.isBlocked(ZonedDateTime.now(MarketTime.IST))) {
            System.err.println("refusing to read the source during market hours (09:00-15:50 IST); use --force");
            return 3;
        }
        List<LocalDate> sessions = args.flag("all") ? declaredSessions() : args.dates("session");
        if (sessions.isEmpty()) {
            return usage("clone needs --session or --all");
        }
        List<String> underlyings = args.upperList("underlying", DEFAULT_UNDERLYINGS);
        Set<Dataset> datasets = Dataset.parse(args.list("what", "ticks,candles,cas,oi"));
        int failures = 0;
        for (LocalDate session : sessions) {
            for (String underlying : underlyings) {
                try {
                    CloneResult result = cloner.cloneSession(session, underlying, datasets);
                    System.out.printf("OK   %s %-7s manifest %-4d %s  %ds  supersedes %s%n", session, underlying,
                            result.manifestId(), result.loadedCounts(), result.elapsed().toSeconds(),
                            result.superseded());
                } catch (Exception e) {
                    failures++;
                    System.out.printf("FAIL %s %-7s %s%n", session, underlying, e.getMessage());
                }
            }
            int chunks = cloner.compressSession(session);
            System.out.printf("     %s compressed %d chunks; database size %s%n", session, chunks, databaseSize());
        }
        return failures == 0 ? 0 : 1;
    }

    private int listManifests(ToolArgs args) throws SQLException {
        args.allowOnly(Set.of());
        System.out.printf("%-5s %-12s %-8s %-10s %-17s %s%n", "id", "session", "under", "status", "tool", "loaded");
        for (LoadManifest manifest : manifests.list()) {
            System.out.printf("%-5d %-12s %-8s %-10s %-17s %s%s%n", manifest.id(), manifest.sessionDate(),
                    manifest.underlying(), manifest.status(), manifest.toolVersion(), manifest.loadedCountsJson(),
                    manifest.error() == null ? "" : "  error: " + manifest.error());
        }
        return 0;
    }

    private int verify(ToolArgs args) throws SQLException {
        args.allowOnly(Set.of("session", "underlying", "source"));
        int problems = 0;
        for (LocalDate session : args.dates("session")) {
            for (String underlying : args.upperList("underlying", DEFAULT_UNDERLYINGS)) {
                Optional<LoadManifest> active = manifests.active(session, underlying);
                if (active.isEmpty()) {
                    System.out.printf("MISSING %s %s: no active load%n", session, underlying);
                    problems++;
                    continue;
                }
                problems += verifyManifest(active.get(), args.flag("source"));
            }
        }
        return problems == 0 ? 0 : 1;
    }

    private int verifyManifest(LoadManifest manifest, boolean againstSource) throws SQLException {
        JsonNode recorded = recordedDigests(manifest.id());
        Map<MdTable, SequenceDigest.Result> sourceDigests = againstSource ? sourceDigests(manifest) : Map.of();
        int problems = 0;
        try (Connection connection = target.getConnection()) {
            connection.setAutoCommit(false);
            for (MdTable table : MdTable.values()) {
                if (!table.isTick() || recorded.get(table.name()) == null) {
                    continue;
                }
                SequenceDigest.Result stored = TableChecks.digest(connection, table, manifest.id());
                String expected = recorded.get(table.name()).get("sha256").stringValue();
                boolean ok = stored.sha256().equals(expected);
                SequenceDigest.Result fromSource = sourceDigests.get(table);
                if (fromSource != null) {
                    ok &= fromSource.sameAs(stored);
                }
                System.out.printf("%s %s %-7s %-17s rows %-9d %s%n", ok ? "OK  " : "BAD ", manifest.sessionDate(),
                        manifest.underlying(), table.name(), stored.count(),
                        fromSource == null ? "(stored vs manifest)" : "(stored vs manifest vs source)");
                if (!ok) {
                    problems++;
                }
            }
            connection.rollback();
        }
        return problems;
    }

    private JsonNode recordedDigests(long manifestId) throws SQLException {
        try (Connection connection = target.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select loaded_digests::text from md.load_manifest where id = ?")) {
            statement.setLong(1, manifestId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return JSON.readTree(rs.getString(1));
            }
        }
    }

    /** Re-reads only (sequence, payload_hash) from the source; cheap compared with a clone. */
    private Map<MdTable, SequenceDigest.Result> sourceDigests(LoadManifest manifest) throws SQLException {
        Map<String, MdTable> tables = Map.of("CASH", MdTable.INDEX_TICK, "FUTURE", MdTable.FUTURE_TICK,
                "OPTION", MdTable.OPTION_TICK, "COMPONENT", MdTable.CONSTITUENT_TICK);
        Map<MdTable, SequenceDigest> digests = new EnumMap<>(MdTable.class);
        String sql = "select sequence_number, tick_type, payload_hash from market_tick_records "
                + "where underlying_key = ? and session_date = ? order by sequence_number";
        try (Connection connection = source.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(20_000);
                statement.setString(1, ZtSourceKeys.underlyingKey(manifest.underlying()));
                statement.setObject(2, manifest.sessionDate());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        MdTable table = tables.get(rs.getString(2));
                        digests.computeIfAbsent(table, t -> new SequenceDigest())
                                .add(rs.getLong(1), SequenceDigest.hash64(rs.getString(3)));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        Map<MdTable, SequenceDigest.Result> results = new EnumMap<>(MdTable.class);
        digests.forEach((table, digest) -> results.put(table, digest.result()));
        return results;
    }

    private int replay(ToolArgs args) throws Exception {
        args.allowOnly(Set.of("session", "underlying", "from", "force", "verify-hashes"));
        List<LocalDate> sessions = args.dates("session");
        if (sessions.size() != 1) {
            return usage("replay needs exactly one --session");
        }
        LocalDate session = sessions.getFirst();
        List<String> underlyings = args.upperList("underlying", DEFAULT_UNDERLYINGS);
        String from = args.get("from", "zt");
        SessionEventSource eventSource;
        ZtSessionSource zt = null;
        switch (from) {
            case "zt" -> {
                if (!args.flag("force") && MarketHoursGuard.isBlocked(ZonedDateTime.now(MarketTime.IST))) {
                    System.err.println("refusing to stream from zt-tiger-v2 during market hours (09:00-15:50 IST); "
                            + "use --force");
                    return 3;
                }
                zt = new ZtSessionSource(source.dataSource(), args.flag("verify-hashes"));
                eventSource = zt;
            }
            case "own" -> eventSource = new StoredSessionSource(target);
            default -> {
                return usage("--from must be zt or own");
            }
        }
        ReplayStats stats = new ReplayStats();
        Instant started = Instant.now();
        SessionEventSource.ReplayResult result = eventSource.replay(session, underlyings, stats);
        Duration elapsed = Duration.between(started, Instant.now());
        double seconds = Math.max(0.001, elapsed.toMillis() / 1000.0);
        System.out.printf("replay %s %s from %s: %,d events in %.1fs (%,.0f events/s), %d late beyond %ss window%n",
                session, underlyings, eventSource.name(), result.delivered(), seconds, result.delivered() / seconds,
                result.lateEvents(), ZtSessionSource.REORDER_WINDOW.toSeconds());
        stats.print(System.out);
        if (zt != null && !zt.unknownPayloadKeys().isEmpty()) {
            System.out.println("  unrecognised payload keys: " + zt.unknownPayloadKeys());
        }
        return stats.orderViolations() == 0 && result.delivered() > 0 ? 0 : 1;
    }

    private int features(ToolArgs args) throws Exception {
        args.allowOnly(Set.of("session", "underlying", "at", "out", "from", "force", "features-file", "exchange-file",
                "save"));
        List<LocalDate> sessions = args.dates("session");
        if (sessions.size() != 1) {
            return usage("features needs exactly one --session");
        }
        if (!args.flag("force") && MarketHoursGuard.isBlocked(ZonedDateTime.now(MarketTime.IST))) {
            System.err.println("refusing to read zt-tiger-v2 during market hours (09:00-15:50 IST); use --force");
            return 3;
        }
        SessionEventSource eventSource = args.get("from", "zt").equals("own")
                ? new StoredSessionSource(target) : new ZtSessionSource(source.dataSource(), false);
        List<LocalTime> printAt = args.list("at", "").stream().map(LocalTime::parse).toList();
        return FeaturesCommand.run(eventSource, new ZtSessionHistory(source.dataSource()), sessions.getFirst(),
                args.upperList("underlying", DEFAULT_UNDERLYINGS),
                Path.of(args.get("features-file", FEATURES_FILE)), Path.of(args.get("exchange-file", EXCHANGE_FILE)),
                Path.of(args.get("out", ".local/features")), printAt,
                args.flag("save") ? new FeaturesCommand.Persistence(target, CodeVersion.current()) : null);
    }

    private int simulate(ToolArgs args) throws Exception {
        args.allowOnly(Set.of("session", "underlying", "strike", "type", "at", "stop-pct", "target-pct", "exit-by",
                "lots", "from", "force", "costs-file"));
        if (!args.flag("force") && MarketHoursGuard.isBlocked(ZonedDateTime.now(MarketTime.IST))) {
            System.err.println("refusing to read zt-tiger-v2 during market hours (09:00-15:50 IST); use --force");
            return 3;
        }
        for (String required : List.of("session", "underlying", "strike", "type", "at")) {
            if (!args.has(required)) {
                return usage("simulate needs --" + required);
            }
        }
        SessionEventSource eventSource = args.get("from", "zt").equals("own")
                ? new StoredSessionSource(target) : new ZtSessionSource(source.dataSource(), false);
        return SimulateCommand.run(eventSource, new SimulateCommand.Request(
                LocalDate.parse(args.get("session", "")), args.get("underlying", "").toUpperCase(),
                Double.parseDouble(args.get("strike", "")), OptionTick.OptionType.valueOf(args.get("type", "").toUpperCase()),
                LocalTime.parse(args.get("at", "")), Double.parseDouble(args.get("stop-pct", "15")),
                Double.parseDouble(args.get("target-pct", "30")), LocalTime.parse(args.get("exit-by", "15:20")),
                Integer.parseInt(args.get("lots", "1")),
                Path.of(args.get("costs-file", "config/costs/india-index-options-costs.v1.yaml"))));
    }

    private int instruments(ToolArgs args) throws Exception {
        args.allowOnly(Set.of("file", "date", "underlying"));
        List<String> files = args.list("file", "");
        if (files.isEmpty()) {
            return usage("instruments needs --file");
        }
        LocalDate date = LocalDate.parse(args.get("date", LocalDate.now(MarketTime.IST).toString()));
        Set<String> underlyings = Set.copyOf(args.upperList("underlying", "NIFTY,BANKNIFTY,SENSEX"));
        InstrumentStore store = new InstrumentStore(target);
        for (String file : files) {
            Path path = Path.of(file);
            List<Instrument> loaded = UpstoxInstrumentFile.read(path, underlyings);
            long id = store.save(date, "UPSTOX:" + path.getFileName(), path, loaded);
            System.out.printf("snapshot %d: %,d index derivatives from %s (date %s)%n", id, loaded.size(), path, date);
        }
        InstrumentMaster master = store.latest(date);
        for (String underlying : new TreeSet<>(underlyings)) {
            List<Instrument> contracts = master.instruments(underlying);
            if (contracts.isEmpty()) {
                System.out.printf("  %-9s no contracts in the loaded files%n", underlying);
                continue;
            }
            Instrument sample = contracts.stream().filter(Instrument::isOption).findFirst().orElse(contracts.getFirst());
            List<LocalDate> expiries = master.optionExpiries(underlying, date);
            LocalDate nearest = expiries.isEmpty() ? null : expiries.getFirst();
            System.out.printf("  %-9s lot %d, freeze %d (max %d lots/order), option tick %.2f, strike step %s, "
                            + "next expiries %s%n", underlying, sample.lotSize(), sample.freezeQuantity(),
                    sample.maxLotsPerOrder(), sample.tickSize(),
                    nearest == null ? "-" : SnapshotFlattener.cell(master.strikeStep(underlying, nearest)),
                    expiries.stream().limit(5).toList());
        }
        return 0;
    }

    private int lifecycle(ToolArgs args) throws Exception {
        args.allowOnly(Set.of("split", "session", "underlying", "strategy", "save", "held-out", "force",
                "features-file", "exchange-file", "costs-file"));
        if (!args.flag("force") && MarketHoursGuard.isBlocked(ZonedDateTime.now(MarketTime.IST))) {
            System.err.println("refusing to read zt-tiger-v2 during market hours (09:00-15:50 IST); use --force");
            return 3;
        }
        List<LocalDate> sessions;
        if (args.has("split")) {
            String split = args.get("split", "").toUpperCase();
            sessions = LifecycleStore.splits(target).entrySet().stream().filter(e -> e.getValue().equals(split))
                    .map(Map.Entry::getKey).toList();
        } else {
            sessions = args.dates("session");
        }
        return LifecycleCommand.run(target, source.dataSource(), new ZtSessionSource(source.dataSource(), false),
                new ZtSessionHistory(source.dataSource()), new LifecycleCommand.Request(sessions,
                        args.upperList("underlying", DEFAULT_UNDERLYINGS),
                        Path.of(args.get("strategy", "config/strategy/early-confirm-runner.v3.yaml")),
                        Path.of(args.get("features-file", FEATURES_FILE)), Path.of(args.get("exchange-file", EXCHANGE_FILE)),
                        Path.of(args.get("costs-file", "config/costs/india-index-options-costs.v1.yaml")),
                        args.flag("save"), args.flag("held-out"), CodeVersion.current()));
    }

    private int ztSessions(ToolArgs args) throws SQLException {
        args.allowOnly(Set.of("force"));
        if (!args.flag("force") && MarketHoursGuard.isBlocked(ZonedDateTime.now(MarketTime.IST))) {
            System.err.println("refusing to scan zt-tiger-v2 during market hours (09:00-15:50 IST); use --force");
            return 3;
        }
        System.out.printf("%-12s %-18s %12s%n", "session", "underlying", "tick rows");
        try (Connection connection = source.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(ZtQueries.SESSIONS);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                System.out.printf("%-12s %-18s %,12d%n", rs.getObject(1, LocalDate.class), rs.getString(2),
                        rs.getLong(3));
            }
        }
        return 0;
    }

    private List<LocalDate> declaredSessions() throws SQLException {
        List<LocalDate> sessions = new ArrayList<>();
        try (Connection connection = target.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select session_date from research.session_split order by session_date");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                sessions.add(rs.getObject(1, LocalDate.class));
            }
        }
        return sessions;
    }

    private String databaseSize() throws SQLException {
        try (Connection connection = target.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select pg_size_pretty(pg_database_size(current_database()))");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
