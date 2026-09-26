package com.autotrade.tools.command;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.core.time.MarketTime;
import com.autotrade.features.FeatureEngine;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.features.snapshot.FeatureSnapshot;
import com.autotrade.features.snapshot.SnapshotFlattener;
import com.autotrade.md.store.Runs;
import com.autotrade.md.store.SnapshotWriter;

import tools.jackson.databind.json.JsonMapper;

/** Replays a session through the feature engine and writes one CSV row per snapshot and underlying. */
final class FeaturesCommand {

    /** Columns printed to the console for the requested times; the CSV has every column. */
    private static final List<String> SUMMARY = List.of(
            "spot", "phase", "structure.orHigh", "structure.orLow", "structure.ema20", "structure.atr3m",
            "structure.distOrhAtr", "structure.orhClosesAbove", "futures.momentum1m", "futures.acceleration1m",
            "futures.basis", "futures.oiState", "futures.rvolTod", "futures.rvolSlope", "options.atmStrike",
            "options.ceOiChange3m", "options.peOiChange3m", "options.ceOiFlow", "options.peOiFlow",
            "options.callBarrierStrike", "options.putSupportStrike", "options.atmIv", "options.premiumResponseCe",
            "breadth.momentumBreadth", "breadth.dayBreadth", "regime.dteTradingDays", "regime.expectedMoveToExpiry",
            "regime.expectedMoveDaily", "regime.expectedMoveRemaining", "regime.expectedMoveMethod");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FeaturesCommand() {
    }

    /** Optional database target: snapshots go to feat.snapshot under a research.run. */
    record Persistence(DataSource dataSource, String codeVersion) {
    }

    static int run(SessionEventSource source, SessionHistory history, LocalDate session, List<String> underlyings,
                   Path featuresFile, Path exchangeFile, Path outputDirectory, List<LocalTime> printAt,
                   Persistence persistence) throws Exception {
        ThresholdConfig featuresConfig = ThresholdConfig.load(featuresFile);
        ThresholdConfig exchangeConfig = ThresholdConfig.load(exchangeFile);
        FeatureConfig config = FeatureConfig.from(featuresConfig, exchangeConfig);
        Files.createDirectories(outputDirectory);
        Runs runs = persistence == null ? null : new Runs(persistence.dataSource());
        Long runId = null;
        SnapshotWriter writer = null;
        if (runs != null) {
            Map<String, String> hashes = new LinkedHashMap<>();
            hashes.put(featuresFile.getFileName().toString(), featuresConfig.contentHash());
            hashes.put(exchangeFile.getFileName().toString(), exchangeConfig.contentHash());
            runId = runs.start("FEATURES", persistence.codeVersion(), source.name(), List.of(session), underlyings,
                    JSON.writeValueAsString(hashes));
            writer = new SnapshotWriter(persistence.dataSource(), runId);
        }
        Map<String, CsvSink> sinks = new LinkedHashMap<>();
        List<FeatureEngine> engines = new ArrayList<>();
        for (String underlying : underlyings) {
            CsvSink sink = new CsvSink(outputDirectory.resolve(session + "-" + underlying + ".csv"), printAt, writer);
            sinks.put(underlying, sink);
            engines.add(new FeatureEngine(underlying, session, config, history, sink));
        }
        Instant started = Instant.now();
        try {
            SessionEventSource.ReplayResult result = source.replay(session, underlyings, event -> {
                for (FeatureEngine engine : engines) {
                    engine.accept(event);
                }
            });
            engines.forEach(FeatureEngine::finish);
            if (writer != null) {
                writer.close();
                runs.finish(runId, JSON.writeValueAsString(Map.of("snapshots", writer.written())));
                System.out.printf("saved %,d snapshots to feat.snapshot as run %d%n", writer.written(), runId);
            }
            System.out.printf("features %s %s from %s: %,d events, %.1fs; features %s, exchange %s%n", session,
                    underlyings, source.name(), result.delivered(),
                    Duration.between(started, Instant.now()).toMillis() / 1000.0,
                    config.featuresHash().substring(0, 19), config.exchangeHash().substring(0, 19));
        } catch (Exception e) {
            if (runId != null) {
                runs.fail(runId, String.valueOf(e.getMessage()));
            }
            throw e;
        } finally {
            for (CsvSink sink : sinks.values()) {
                sink.close();
            }
        }
        for (Map.Entry<String, CsvSink> entry : sinks.entrySet()) {
            CsvSink sink = entry.getValue();
            System.out.printf("  %s: %d snapshots -> %s%n", entry.getKey(), sink.rows, sink.path);
            for (Map<String, Object> row : sink.printed) {
                System.out.printf("  %s %s%n", SnapshotFlattener.cell(row.get("time")), summary(row));
            }
        }
        return 0;
    }

    static Map<String, Object> jsonValues(Map<String, Object> row) {
        return SnapshotFlattener.jsonSafe(row);
    }

    private static String summary(Map<String, Object> row) {
        StringBuilder text = new StringBuilder();
        for (String column : SUMMARY) {
            String value = SnapshotFlattener.cell(row.get(column));
            if (!value.isEmpty()) {
                text.append("\n      ").append(column).append('=').append(value);
            }
        }
        return text.toString();
    }

    private static final class CsvSink implements Consumer<FeatureSnapshot>, AutoCloseable {

        private final Path path;
        private final BufferedWriter writer;
        private final SnapshotWriter database;
        private final Map<LocalTime, Boolean> printAt = new HashMap<>();
        private final List<Map<String, Object>> printed = new ArrayList<>();
        private boolean header;
        private int rows;

        CsvSink(Path path, List<LocalTime> printAt, SnapshotWriter database) throws IOException {
            this.path = path;
            this.database = database;
            this.writer = Files.newBufferedWriter(path);
            printAt.forEach(time -> this.printAt.put(time, true));
        }

        @Override
        public void accept(FeatureSnapshot snapshot) {
            Map<String, Object> row = SnapshotFlattener.flatten(snapshot);
            try {
                if (!header) {
                    writer.write(String.join(",", row.keySet()));
                    writer.newLine();
                    header = true;
                }
                List<String> cells = new ArrayList<>(row.size());
                for (Object value : row.values()) {
                    cells.add(SnapshotFlattener.cell(value));
                }
                writer.write(String.join(",", cells));
                writer.newLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            rows++;
            if (database != null) {
                database.add(snapshot.session(), snapshot.underlying(), snapshot.time(), snapshot.phase(),
                        snapshot.spot(), JSON.writeValueAsString(jsonValues(row)));
            }
            LocalTime time = snapshot.time().atZone(MarketTime.IST).toLocalTime();
            if (printAt.containsKey(time)) {
                printed.add(row);
            }
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
