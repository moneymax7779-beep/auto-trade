package com.autotrade.tools.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.autotrade.config.ThresholdConfig;
import com.autotrade.core.event.SessionEventSource;
import com.autotrade.core.history.SessionHistory;
import com.autotrade.features.config.FeatureConfig;
import com.autotrade.md.store.Runs;
import com.autotrade.md.zt.ZtSourceKeys;
import com.autotrade.research.Episode;
import com.autotrade.research.FrameSink;
import com.autotrade.research.FrameStats;
import com.autotrade.research.LifecycleReplay;
import com.autotrade.research.LifecycleReport;
import com.autotrade.research.LifecycleStore;
import com.autotrade.sim.CostModel;
import com.autotrade.sim.FillModel;
import com.autotrade.strategy.Strategies;
import com.autotrade.strategy.StrategyFactory;

import tools.jackson.databind.json.JsonMapper;

/** Replays sessions through features → early-confirm-runner → simulated fills, and reports. */
final class LifecycleCommand {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Request(List<LocalDate> sessions, List<String> underlyings, Path strategyFile, Path featuresFile,
                   Path exchangeFile, Path costsFile, boolean save, boolean heldOutAllowed, boolean descriptive,
                   String codeVersion) {
    }

    private LifecycleCommand() {
    }

    static int run(DataSource target, DataSource sourceDb, SessionEventSource source, SessionHistory history,
                   Request request) throws Exception {
        ThresholdConfig strategyConfig = ThresholdConfig.load(request.strategyFile());
        ThresholdConfig featuresConfig = ThresholdConfig.load(request.featuresFile());
        ThresholdConfig exchangeConfig = ThresholdConfig.load(request.exchangeFile());
        ThresholdConfig costConfig = ThresholdConfig.load(request.costsFile());
        StrategyFactory strategies = Strategies.create(strategyConfig);
        for (String section : strategies.requiredFeatureSections()) {
            if (!featuresConfig.has(section)) {
                System.err.println(request.strategyFile() + " needs feature section '" + section + "', which "
                        + request.featuresFile() + " lacks");
                return 2;
            }
        }
        List<LocalDate> requested = request.sessions();
        if (requested.isEmpty()) {
            if (!strategyConfig.has("evaluation.sessions_from")) {
                System.err.println("no --split/--session and " + request.strategyFile()
                        + " has no evaluation.sessions_from");
                return 2;
            }
            LocalDate from = LocalDate.parse(strategyConfig.getString("evaluation.sessions_from"));
            requested = sessionsFrom(sourceDb, request.underlyings().getFirst(), from);
            System.out.printf("evaluation window from %s (strategy file): %s%n", from, requested);
        }

        Map<LocalDate, String> splits = LifecycleStore.splits(target);
        List<LocalDate> heldOut = requested.stream().filter(d -> "HELD_OUT".equals(splits.get(d))).toList();
        boolean descriptive = false;
        if (!heldOut.isEmpty() && request.descriptive()) {
            // A descriptive run may only look at held-out sessions this strategy family has already
            // consumed: it cannot spend fresh held-out data, and its results are not evidence.
            List<LocalDate> used = LifecycleStore.heldOutAlreadyUsed(target, strategies.id(), heldOut);
            if (!used.containsAll(heldOut)) {
                List<LocalDate> fresh = heldOut.stream().filter(d -> !used.contains(d)).toList();
                System.err.println("--descriptive only covers held-out sessions already consumed by " + strategies.id()
                        + "; " + fresh + " are still unseen");
                return 2;
            }
            descriptive = true;
        } else if (!heldOut.isEmpty()) {
            if (!request.heldOutAllowed() || !request.save()) {
                System.err.println("sessions " + heldOut + " are HELD_OUT; pass --held-out --save to use them "
                        + "(once per strategy configuration)");
                return 2;
            }
            List<LocalDate> used = LifecycleStore.heldOutAlreadyUsed(target, strategies.id(), heldOut);
            if (!used.isEmpty()) {
                System.err.println("held-out sessions " + used + " were already used by " + strategies.id()
                        + "; a new version needs held-out sessions it has never seen");
                return 2;
            }
        }
        List<LocalDate> sessions = new ArrayList<>();
        for (LocalDate session : requested) {
            // Own-database replays (recorded live sessions) do not depend on what zt-tiger-v2 still holds.
            if (!source.name().contains("zt") || hasTicks(sourceDb, session, request.underlyings())) {
                sessions.add(session);
            } else {
                System.out.printf("skip %s: zt-tiger-v2 no longer holds its ticks (archived to the external drive)%n",
                        session);
            }
        }
        if (sessions.isEmpty()) {
            System.err.println("no replayable sessions");
            return 1;
        }

        Map<String, String> configs = new LinkedHashMap<>();
        configs.put(request.strategyFile().getFileName().toString(), strategyConfig.contentHash());
        configs.put(request.featuresFile().getFileName().toString(), featuresConfig.contentHash());
        configs.put(request.exchangeFile().getFileName().toString(), exchangeConfig.contentHash());
        configs.put(request.costsFile().getFileName().toString(), costConfig.contentHash());
        Runs runs = new Runs(target);
        Long runId = request.save() ? runs.start(descriptive ? "LIFECYCLE_DESCRIPTIVE" : "LIFECYCLE", request.codeVersion(), source.name(), sessions,
                request.underlyings(), JSON.writeValueAsString(configs)) : null;
        LifecycleStore store = runId == null ? null : new LifecycleStore(target, runId);
        if (store != null && !heldOut.isEmpty() && !descriptive) {
            store.claimHeldOut(strategies.id(), strategies.configHash(), heldOut);
        }

        List<FillModel> models = List.of(FillModel.from(costConfig, "base"), FillModel.from(costConfig, "stressed"));
        LifecycleReplay replay = new LifecycleReplay(new LifecycleReplay.Settings(
                FeatureConfig.from(featuresConfig, exchangeConfig), history, strategies, models,
                CostModel.from(costConfig), strategies.premiumStopPct()));
        FrameStats frames = new FrameStats("base");
        FrameSink sink = store == null ? frames : FrameSink.both(frames, store);
        List<Episode> episodes = new ArrayList<>();
        Instant started = Instant.now();
        try {
            for (LocalDate session : sessions) {
                LifecycleReplay.SessionResult result = replay.run(source, session, request.underlyings(), sink);
                episodes.addAll(result.episodes());
                if (store != null) {
                    store.episodes(result.episodes());
                }
                double baseNet = result.episodes().stream().filter(e -> e.lane().equals("base"))
                        .mapToDouble(Episode::net).sum();
                long baseCount = result.episodes().stream().filter(e -> e.lane().equals("base")).count();
                System.out.printf("  %s [%s]: %,d events, %d episodes (base), base net ₹%,.0f%n", session,
                        splits.getOrDefault(session, "?"), result.events(), baseCount, baseNet);
            }
            if (store != null) {
                store.close();
                runs.finish(runId, JSON.writeValueAsString(Map.of("episodes", episodes.size())));
            }
        } catch (Exception e) {
            if (runId != null) {
                runs.fail(runId, String.valueOf(e.getMessage()));
            }
            throw e;
        }
        Map<String, String> header = new LinkedHashMap<>();
        header.put("Run", runId == null ? "not saved" : String.valueOf(runId));
        header.put("Code", request.codeVersion());
        header.put("Strategy", strategies.version() + " " + strategies.configHash().substring(0, 19));
        header.put("Configs", configs.toString());
        header.put("Sessions", sessions + (heldOut.isEmpty() ? "" : descriptive
                ? " (includes held-out " + heldOut + " already consumed for " + strategies.id() + ")"
                : " (includes held-out " + heldOut + ", now consumed for " + strategies.id() + ")"));
        if (descriptive) {
            header.put("Status", "DESCRIPTIVE: behaviour check on sessions this family has already seen; the P&L is "
                    + "not evidence for or against the strategy");
        }
        header.put("Underlyings", request.underlyings().toString());
        header.put("Sizing", "research default from the strategy file (rules.intended_lots)");
        header.put("Elapsed", Duration.between(started, Instant.now()).toSeconds() + " s");
        String report = LifecycleReport.render("Lifecycle replay: " + strategies.id(), header, episodes, frames);
        Path reports = Files.createDirectories(Path.of(".local", "reports"));
        Path file = reports.resolve("lifecycle-" + (runId == null ? "unsaved-" + System.currentTimeMillis() : "run-" + runId) + ".md");
        Files.writeString(file, report);
        System.out.println(report);
        System.out.println("report: " + file);
        return 0;
    }

    /** Sessions zt-tiger-v2 holds index ticks for, on or after {@code from}. */
    private static List<LocalDate> sessionsFrom(DataSource source, String underlying, LocalDate from)
            throws SQLException {
        List<LocalDate> sessions = new ArrayList<>();
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("select distinct session_date "
                     + "from market_tick_records where underlying_key = ? and tick_type = 'CASH' and session_date >= ? "
                     + "order by session_date")) {
            statement.setString(1, ZtSourceKeys.underlyingKey(underlying));
            statement.setObject(2, from);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    sessions.add(rs.getObject(1, LocalDate.class));
                }
            }
        }
        return sessions;
    }

    /** True when zt-tiger-v2 still has index ticks for the session (archived sessions keep only IV rows). */
    private static boolean hasTicks(DataSource source, LocalDate session, List<String> underlyings) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("select 1 from market_tick_records "
                     + "where underlying_key = ? and session_date = ? and tick_type = 'CASH' limit 1")) {
            statement.setString(1, ZtSourceKeys.underlyingKey(underlyings.getFirst()));
            statement.setObject(2, session);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
